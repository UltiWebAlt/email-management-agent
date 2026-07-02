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

## Security notes

Do not expose Ollama publicly. Restrict `admin_cidrs` to your IP, VPN, or Tailscale subnet.

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

Note: this stack attaches the volume to one instance at a time. Keep `max_size = 1` when using this mode.

## Scheduled scaling

Set:

```hcl
enable_scheduled_scaling = true
scale_up_cron_expression   = "cron(0 13 ? * MON-FRI *)"
scale_down_cron_expression = "cron(0 23 ? * MON-FRI *)"
```

AWS cron expressions are UTC.
