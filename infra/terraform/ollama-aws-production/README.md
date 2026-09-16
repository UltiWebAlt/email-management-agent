# Production-grade Ollama on AWS

This Terraform stack provisions a cost-controlled Ollama deployment on AWS using:

- EC2 Auto Scaling Group
- GPU Spot instances
- Mixed instance policy
- Optional Application Load Balancer
- Optional HTTPS via ACM certificate ARN
- Optional persistent EBS model volume mounted at `/usr/share/ollama`
- Security group restricted to your CIDRs
- CloudWatch log group
- Optional scheduled scale-up / scale-down via EventBridge + Lambda
- Bootstrapped NVIDIA driver + Ollama install
- Optional Tailscale node for private Ollama access

## Default cost posture

The default is intentionally cheap:

```hcl
min_size         = 0
desired_capacity = 0
max_size         = 1
```

That means no EC2 spend until you scale the ASG up.

## Files

```text
main.tf
variables.tf
outputs.tf
user_data.sh
lambda_scale_asg.py
terraform.tfvars.example
README.md
```

## Usage

```bash
cp terraform.tfvars.example terraform.tfvars
vi terraform.tfvars
terraform init
terraform apply
```

## Start the GPU instance manually

```bash
aws autoscaling set-desired-capacity \
  --auto-scaling-group-name "$(terraform output -raw asg_name)" \
  --desired-capacity 1
```

## Stop the GPU instance manually

```bash
aws autoscaling set-desired-capacity \
  --auto-scaling-group-name "$(terraform output -raw asg_name)" \
  --desired-capacity 0
```

## Test direct instance access

If `enable_alb = false`, get the instance IP:

```bash
aws ec2 describe-instances \
  --filters "Name=tag:Name,Values=$(terraform output -raw name)" "Name=instance-state-name,Values=running" \
  --query "Reservations[].Instances[].PublicIpAddress" \
  --output text
```

Then:

```bash
curl http://INSTANCE_PUBLIC_IP:11434/api/generate \
  -d '{
    "model": "llama3.1:8b",
    "prompt": "Explain BTRFS snapshots in one paragraph.",
    "stream": false
  }'
```

## Test ALB access

If `enable_alb = true`:

```bash
curl http://$(terraform output -raw alb_dns_name)/api/tags
```

If HTTPS is enabled with `acm_certificate_arn`, use:

```bash
curl https://YOUR_DOMAIN/api/tags
```

## Private access with Tailscale

Tailscale can expose Ollama privately without an ALB, a public Ollama security-group rule, or SSH. The auth key is read at boot from an AWS Systems Manager SecureString and is not stored in Terraform state.

1. In the Tailscale admin console, create a **tagged, reusable, ephemeral** auth key. Use a dedicated tag such as `tag:ollama`; if device approval is enabled, make the key pre-approved. Grant your user/device access to `tag:ollama:11434` in your tailnet policy.
2. Store the key in Parameter Store. This command reads it without echoing it:

```bash
read -rs TAILSCALE_AUTH_KEY
aws ssm put-parameter \
  --region us-east-1 \
  --name /ollama/tailscale-auth-key \
  --type SecureString \
  --value "$TAILSCALE_AUTH_KEY" \
  --overwrite
unset TAILSCALE_AUTH_KEY
```

3. Configure Terraform for Tailnet-only access:

```hcl
enable_tailscale                    = true
tailscale_auth_key_parameter_name   = "/ollama/tailscale-auth-key"
tailscale_hostname                  = "ollama-spot"
enable_tailscale_direct_connections = true

enable_alb = false
enable_ssh = false
admin_cidrs = []
```

After the instance starts, use MagicDNS (if enabled) from this computer:

```bash
curl http://$(terraform output -raw tailscale_hostname):11434/api/tags
```

If MagicDNS is disabled, find the node's Tailscale IP with `tailscale status` and use that address instead. The ephemeral key ensures a terminated ASG instance is removed from the tailnet shortly after it goes offline.

## Security notes

Do not expose Ollama publicly. Restrict `admin_cidrs` to your IP or VPN when using an ALB or direct access. Tailscale traffic reaches the instance over its encrypted overlay and does not require a public Ollama `11434` ingress rule.

Recommended:

- `admin_cidrs = ["YOUR_PUBLIC_IP/32"]`
- Or use a private subnet + VPN/Tailscale instead of public access
- Put auth in front of Ollama if exposing beyond trusted networks

## Persistent model cache

Set:

```hcl
enable_persistent_model_volume = true
persistent_model_volume_gb     = 150
```

This creates an EBS volume and mounts it to `/usr/share/ollama`.

Set `persistent_volume_az` to the AZ of one of the configured subnets. When this mode is enabled, the ASG launches only in matching-AZ subnets and requires `max_size = 1`, because the EBS volume can attach to only one instance.

Cold starts install NVIDIA drivers and download the configured model. The default `health_check_grace_period_seconds = 2700` allows 45 minutes before the ASG may replace an unhealthy instance. Increase it for larger models or slower package mirrors.

## Scheduled scaling

Set:

```hcl
enable_scheduled_scaling = true
scale_up_cron_expression   = "cron(0 13 ? * MON-FRI *)"
scale_down_cron_expression = "cron(0 23 ? * MON-FRI *)"
```

AWS cron expressions are UTC.
