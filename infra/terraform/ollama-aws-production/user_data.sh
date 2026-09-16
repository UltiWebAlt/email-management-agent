#!/usr/bin/env bash
set -euxo pipefail

export DEBIAN_FRONTEND=noninteractive

AWS_REGION="${aws_region}"
LOG_GROUP="${log_group_name}"
OLLAMA_MODEL="${ollama_model}"
ENABLE_PERSISTENT_MODEL_VOLUME="${enable_persistent_model_volume}"
MODEL_VOLUME_ID="${model_volume_id}"
MODEL_VOLUME_DEVICE="${model_volume_device}"
ENABLE_TAILSCALE="${enable_tailscale}"
TAILSCALE_AUTH_KEY_PARAMETER_NAME="${tailscale_auth_key_parameter_name}"
TAILSCALE_HOSTNAME="${tailscale_hostname}"

apt-get update
apt-get install -y \
  curl \
  ca-certificates \
  gnupg \
  lsb-release \
  unzip \
  jq \
  xfsprogs \
  nvme-cli \
  amazon-cloudwatch-agent \
  awscli \
  ubuntu-drivers-common

# Install recommended NVIDIA driver.
ubuntu-drivers install || true

if [ "$ENABLE_TAILSCALE" = "true" ]; then
  curl -fsSL https://pkgs.tailscale.com/stable/ubuntu/jammy.noarmor.gpg \
    | tee /usr/share/keyrings/tailscale-archive-keyring.gpg >/dev/null
  curl -fsSL https://pkgs.tailscale.com/stable/ubuntu/jammy.tailscale-keyring.list \
    | tee /etc/apt/sources.list.d/tailscale.list
  apt-get update
  apt-get install -y tailscale

  set +x
  TAILSCALE_AUTH_KEY="$(aws ssm get-parameter \
    --region "$AWS_REGION" \
    --name "$TAILSCALE_AUTH_KEY_PARAMETER_NAME" \
    --with-decryption \
    --query 'Parameter.Value' \
    --output text)"
  tailscale up --auth-key="$TAILSCALE_AUTH_KEY" --hostname="$TAILSCALE_HOSTNAME"
  unset TAILSCALE_AUTH_KEY
  set -x
fi

# Install Ollama.
curl -fsSL https://ollama.com/install.sh | sh

# Optional persistent model volume.
if [ "$ENABLE_PERSISTENT_MODEL_VOLUME" = "true" ]; then
  INSTANCE_ID="$(curl -fsS -H 'X-aws-ec2-metadata-token: ' http://169.254.169.254/latest/meta-data/instance-id || true)"

  TOKEN="$(curl -X PUT -fsS 'http://169.254.169.254/latest/api/token' -H 'X-aws-ec2-metadata-token-ttl-seconds: 21600')"
  INSTANCE_ID="$(curl -fsS -H "X-aws-ec2-metadata-token: $TOKEN" http://169.254.169.254/latest/meta-data/instance-id)"

  aws ec2 attach-volume \
    --region "$AWS_REGION" \
    --volume-id "$MODEL_VOLUME_ID" \
    --instance-id "$INSTANCE_ID" \
    --device "$MODEL_VOLUME_DEVICE"

  aws ec2 wait volume-in-use \
    --region "$AWS_REGION" \
    --volume-ids "$MODEL_VOLUME_ID"

  # Nitro instances often expose EBS as NVMe. Find the actual device by volume ID.
  VOLUME_ID_NODASH="$(echo "$MODEL_VOLUME_ID" | tr -d '-')"
  REAL_DEVICE=""
  for attempt in $(seq 1 30); do
    for dev in /dev/nvme*n1; do
      if nvme id-ctrl -v "$dev" 2>/dev/null | grep -q "$VOLUME_ID_NODASH"; then
        REAL_DEVICE="$dev"
        break 2
      fi
    done
    sleep 5
  done

  if [ -z "$REAL_DEVICE" ]; then
    echo "Attached model volume was not available as an NVMe device." >&2
    exit 1
  fi

  if ! blkid "$REAL_DEVICE"; then
    mkfs.xfs -f "$REAL_DEVICE"
  fi

  mkdir -p /usr/share/ollama
  UUID="$(blkid -s UUID -o value "$REAL_DEVICE")"
  grep -q "$UUID" /etc/fstab || echo "UUID=$UUID /usr/share/ollama xfs defaults,nofail 0 2" >> /etc/fstab
  mount -a
  chown -R ollama:ollama /usr/share/ollama || true
fi

mkdir -p /etc/systemd/system/ollama.service.d

cat >/etc/systemd/system/ollama.service.d/override.conf <<'EOF'
[Service]
Environment="OLLAMA_HOST=0.0.0.0:11434"
Environment="OLLAMA_KEEP_ALIVE=10m"
EOF

systemctl daemon-reload
systemctl enable ollama
systemctl restart ollama

# CloudWatch agent config.
cat >/opt/aws/amazon-cloudwatch-agent/etc/amazon-cloudwatch-agent.json <<EOF
{
  "logs": {
    "logs_collected": {
      "files": {
        "collect_list": [
          {
            "file_path": "/var/log/cloud-init-output.log",
            "log_group_name": "$LOG_GROUP",
            "log_stream_name": "{instance_id}/cloud-init-output.log"
          },
          {
            "file_path": "/var/log/syslog",
            "log_group_name": "$LOG_GROUP",
            "log_stream_name": "{instance_id}/syslog"
          }
        ]
      }
    }
  }
}
EOF

systemctl enable amazon-cloudwatch-agent
systemctl restart amazon-cloudwatch-agent || true

# Pull model after Ollama is listening.
for i in $(seq 1 60); do
  if curl -fsS http://127.0.0.1:11434/api/tags >/dev/null; then
    break
  fi
  sleep 5
done

ollama pull "$OLLAMA_MODEL"

# Warm model metadata.
curl -fsS http://127.0.0.1:11434/api/tags || true
