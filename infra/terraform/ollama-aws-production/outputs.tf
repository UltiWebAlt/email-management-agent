output "name" {
  value = var.name
}

output "asg_name" {
  value = aws_autoscaling_group.ollama.name
}

output "instance_security_group_id" {
  value = aws_security_group.instance.id
}

output "alb_dns_name" {
  value       = var.enable_alb ? aws_lb.ollama[0].dns_name : null
  description = "ALB DNS name, if enabled."
}

output "alb_zone_id" {
  value       = var.enable_alb ? aws_lb.ollama[0].zone_id : null
  description = "ALB Route53 zone ID, if enabled."
}

output "model_cache_volume_id" {
  value       = var.enable_persistent_model_volume ? aws_ebs_volume.model_cache[0].id : null
  description = "Persistent model cache EBS volume ID, if enabled."
}

output "cloudwatch_log_group" {
  value = aws_cloudwatch_log_group.ollama.name
}

output "tailscale_hostname" {
  value       = var.enable_tailscale ? coalesce(var.tailscale_hostname, var.name) : null
  description = "Tailscale hostname for the Ollama node, if enabled."
}
