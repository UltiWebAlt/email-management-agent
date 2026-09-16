variable "aws_region" {
  description = "AWS region."
  type        = string
  default     = "us-east-1"
}

variable "name" {
  description = "Name prefix for all resources."
  type        = string
  default     = "ollama-spot"
}

variable "tags" {
  description = "Common resource tags."
  type        = map(string)
  default     = {}
}

variable "vpc_id" {
  description = "VPC ID. Leave null to use the default VPC."
  type        = string
  default     = null
}

variable "subnet_ids" {
  description = "Subnet IDs. Leave empty to use all subnets in the selected/default VPC."
  type        = list(string)
  default     = []
}

variable "key_name" {
  description = "EC2 key pair name."
  type        = string
  default     = null
}

variable "enable_ssh" {
  description = "Whether to allow SSH ingress from admin_cidrs."
  type        = bool
  default     = true
}

variable "enable_tailscale" {
  description = "Install and join the EC2 instance to a Tailnet using an auth key stored in AWS Systems Manager Parameter Store."
  type        = bool
  default     = false
}

variable "tailscale_auth_key_parameter_name" {
  description = "Name of the SecureString SSM parameter containing the Tailscale auth key. Required when enable_tailscale is true."
  type        = string
  default     = null
}

variable "tailscale_hostname" {
  description = "Optional hostname for the Tailscale node. Defaults to name."
  type        = string
  default     = null
}

variable "enable_tailscale_direct_connections" {
  description = "Allow encrypted Tailscale UDP traffic on port 41641 for direct peer-to-peer connections."
  type        = bool
  default     = true
}

variable "admin_cidrs" {
  description = "CIDR blocks allowed to reach SSH/Ollama/ALB. Use your IP /32, VPN CIDR, or Tailscale subnet."
  type        = list(string)
}

variable "ami_id" {
  description = "Optional AMI override."
  type        = string
  default     = null
}

variable "ollama_model" {
  description = "Model to pull on boot."
  type        = string
  default     = "llama3.1:8b"
}

variable "primary_instance_type" {
  description = "Launch template default instance type."
  type        = string
  default     = "g4dn.xlarge"
}

variable "instance_types" {
  description = "Mixed instance policy override instance types."
  type        = list(string)
  default = [
    "g4dn.xlarge",
    "g5.xlarge"
  ]
}

variable "root_device_name" {
  description = "Root block device name."
  type        = string
  default     = "/dev/sda1"
}

variable "root_volume_gb" {
  description = "Root volume size."
  type        = number
  default     = 100
}

variable "min_size" {
  description = "ASG minimum size."
  type        = number
  default     = 0
}

variable "max_size" {
  description = "ASG maximum size."
  type        = number
  default     = 1
}

variable "desired_capacity" {
  description = "Initial ASG desired capacity."
  type        = number
  default     = 0
}

variable "on_demand_base_capacity" {
  description = "On-demand base capacity. Keep 0 for cheapest."
  type        = number
  default     = 0
}

variable "on_demand_percentage" {
  description = "On-demand percentage above base. Keep 0 for cheapest."
  type        = number
  default     = 0
}

variable "spot_allocation_strategy" {
  description = "Spot allocation strategy."
  type        = string
  default     = "price-capacity-optimized"
}

variable "enable_alb" {
  description = "Whether to create an Application Load Balancer."
  type        = bool
  default     = true
}

variable "alb_internal" {
  description = "Whether the ALB is internal."
  type        = bool
  default     = false
}

variable "alb_idle_timeout_seconds" {
  description = "ALB idle timeout. Long generations may need more than the default 60s."
  type        = number
  default     = 300
}

variable "acm_certificate_arn" {
  description = "Optional ACM certificate ARN. If provided, HTTPS listener is created and HTTP redirects to HTTPS."
  type        = string
  default     = null
}

variable "enable_persistent_model_volume" {
  description = "Create and mount a persistent EBS volume at /usr/share/ollama."
  type        = bool
  default     = false
}

variable "persistent_model_volume_gb" {
  description = "Persistent model EBS volume size."
  type        = number
  default     = 150
}

variable "persistent_volume_az" {
  description = "AZ for persistent model EBS volume. Required if enable_persistent_model_volume = true and must match a configured subnet."
  type        = string
  default     = null
}

variable "model_volume_device" {
  description = "Linux device path requested for model EBS volume."
  type        = string
  default     = "/dev/sdf"
}

variable "enable_detailed_monitoring" {
  description = "Enable detailed EC2 monitoring."
  type        = bool
  default     = false
}

variable "health_check_grace_period_seconds" {
  description = "Seconds to allow package installation, GPU driver setup, and model download before ASG health checks can replace an instance."
  type        = number
  default     = 2700

  validation {
    condition     = var.health_check_grace_period_seconds >= 900
    error_message = "health_check_grace_period_seconds must be at least 900 seconds."
  }
}

variable "log_retention_days" {
  description = "CloudWatch log retention."
  type        = number
  default     = 14
}

variable "enable_instance_refresh" {
  description = "Enable ASG instance refresh on launch template changes."
  type        = bool
  default     = false
}

variable "enable_scheduled_scaling" {
  description = "Create EventBridge + Lambda scheduled scaling."
  type        = bool
  default     = false
}

variable "scale_up_cron_expression" {
  description = "UTC EventBridge cron expression for scheduled scale-up."
  type        = string
  default     = "cron(0 13 ? * MON-FRI *)"
}

variable "scale_down_cron_expression" {
  description = "UTC EventBridge cron expression for scheduled scale-down."
  type        = string
  default     = "cron(0 23 ? * MON-FRI *)"
}

variable "scheduled_scale_up_capacity" {
  description = "Desired capacity used by scheduled scale-up."
  type        = number
  default     = 1
}
