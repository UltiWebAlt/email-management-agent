terraform {
  required_version = ">= 1.6.0"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = ">= 5.0"
    }
    archive = {
      source  = "hashicorp/archive"
      version = ">= 2.4.0"
    }
  }
}

provider "aws" {
  region = var.aws_region
}

locals {
  tags = merge(var.tags, {
    Project = var.name
  })
}

data "aws_region" "current" {}

data "aws_caller_identity" "current" {}

data "aws_vpc" "selected" {
  id      = var.vpc_id
  default = var.vpc_id == null ? true : null
}

data "aws_subnets" "selected" {
  count = length(var.subnet_ids) == 0 ? 1 : 0

  filter {
    name   = "vpc-id"
    values = [data.aws_vpc.selected.id]
  }
}

locals {
  subnet_ids = length(var.subnet_ids) > 0 ? var.subnet_ids : data.aws_subnets.selected[0].ids
}

data "aws_subnet" "selected" {
  for_each = toset(local.subnet_ids)

  id = each.value
}

locals {
  persistent_volume_subnet_ids = var.enable_persistent_model_volume ? [
    for subnet_id, subnet in data.aws_subnet.selected : subnet_id
    if subnet.availability_zone == var.persistent_volume_az
  ] : []
  instance_subnet_ids = var.enable_persistent_model_volume ? local.persistent_volume_subnet_ids : local.subnet_ids
}

data "aws_ami" "ubuntu_gpu" {
  most_recent = true
  owners      = ["099720109477"]

  filter {
    name = "name"
    values = [
      "ubuntu/images/hvm-ssd/ubuntu-jammy-22.04-amd64-server-*"
    ]
  }
}

resource "aws_cloudwatch_log_group" "ollama" {
  name              = "/aws/ec2/${var.name}"
  retention_in_days = var.log_retention_days
  tags              = local.tags
}

resource "aws_security_group" "instance" {
  name        = "${var.name}-instance-sg"
  description = "Ollama EC2 instance security group"
  vpc_id      = data.aws_vpc.selected.id

  dynamic "ingress" {
    for_each = var.enable_ssh ? [1] : []
    content {
      description = "SSH from admin CIDRs"
      from_port   = 22
      to_port     = 22
      protocol    = "tcp"
      cidr_blocks = var.admin_cidrs
    }
  }

  dynamic "ingress" {
    for_each = var.enable_alb ? [1] : []
    content {
      description     = "Ollama from ALB"
      from_port       = 11434
      to_port         = 11434
      protocol        = "tcp"
      security_groups = [aws_security_group.alb[0].id]
    }
  }

  dynamic "ingress" {
    for_each = !var.enable_alb && length(var.admin_cidrs) > 0 ? [1] : []
    content {
      description = "Ollama from admin CIDRs"
      from_port   = 11434
      to_port     = 11434
      protocol    = "tcp"
      cidr_blocks = var.admin_cidrs
    }
  }

  dynamic "ingress" {
    for_each = var.enable_tailscale && var.enable_tailscale_direct_connections ? [1] : []
    content {
      description = "Tailscale encrypted peer-to-peer UDP"
      from_port   = 41641
      to_port     = 41641
      protocol    = "udp"
      cidr_blocks = ["0.0.0.0/0"]
    }
  }

  egress {
    description = "All outbound"
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = merge(local.tags, {
    Name = "${var.name}-instance-sg"
  })
}

resource "aws_security_group" "alb" {
  count = var.enable_alb ? 1 : 0

  name        = "${var.name}-alb-sg"
  description = "Ollama ALB security group"
  vpc_id      = data.aws_vpc.selected.id

  ingress {
    description = "HTTP from admin CIDRs"
    from_port   = 80
    to_port     = 80
    protocol    = "tcp"
    cidr_blocks = var.admin_cidrs
  }

  dynamic "ingress" {
    for_each = var.acm_certificate_arn == null ? [] : [1]
    content {
      description = "HTTPS from admin CIDRs"
      from_port   = 443
      to_port     = 443
      protocol    = "tcp"
      cidr_blocks = var.admin_cidrs
    }
  }

  egress {
    description = "All outbound"
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = merge(local.tags, {
    Name = "${var.name}-alb-sg"
  })
}

resource "aws_iam_role" "ec2" {
  name = "${var.name}-ec2-role"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect = "Allow"
      Principal = {
        Service = "ec2.amazonaws.com"
      }
      Action = "sts:AssumeRole"
    }]
  })

  tags = local.tags
}

resource "aws_iam_role_policy" "ec2" {
  name = "${var.name}-ec2-policy"
  role = aws_iam_role.ec2.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = concat(
      [
        {
          Effect = "Allow"
          Action = [
            "logs:CreateLogStream",
            "logs:PutLogEvents",
            "logs:DescribeLogStreams"
          ]
          Resource = "${aws_cloudwatch_log_group.ollama.arn}:*"
        }
      ],
      var.enable_persistent_model_volume ? [
        {
          Effect = "Allow"
          Action = [
            "ec2:AttachVolume",
            "ec2:DescribeVolumes",
            "ec2:DescribeInstances"
          ]
          Resource = "*"
        }
      ] : [],
      var.enable_tailscale ? [
        {
          Effect = "Allow"
          Action = [
            "ssm:GetParameter"
          ]
          Resource = "arn:aws:ssm:${var.aws_region}:${data.aws_caller_identity.current.account_id}:parameter${var.tailscale_auth_key_parameter_name}"
        }
      ] : []
    )
  })
}

resource "aws_iam_instance_profile" "ec2" {
  name = "${var.name}-ec2-profile"
  role = aws_iam_role.ec2.name
}

resource "aws_ebs_volume" "model_cache" {
  count = var.enable_persistent_model_volume ? 1 : 0

  availability_zone = var.persistent_volume_az
  size              = var.persistent_model_volume_gb
  type              = "gp3"
  encrypted         = true

  tags = merge(local.tags, {
    Name = "${var.name}-model-cache"
  })

  lifecycle {
    precondition {
      condition     = var.persistent_volume_az != null
      error_message = "persistent_volume_az must be set when enable_persistent_model_volume is true."
    }
  }
}

resource "aws_launch_template" "ollama" {
  name_prefix   = "${var.name}-"
  image_id      = var.ami_id != null ? var.ami_id : data.aws_ami.ubuntu_gpu.id
  instance_type = var.primary_instance_type
  key_name      = var.key_name

  update_default_version = true

  iam_instance_profile {
    name = aws_iam_instance_profile.ec2.name
  }

  vpc_security_group_ids = [aws_security_group.instance.id]

  user_data = base64encode(templatefile("${path.module}/user_data.sh", {
    ollama_model                      = var.ollama_model
    log_group_name                    = aws_cloudwatch_log_group.ollama.name
    aws_region                        = var.aws_region
    enable_persistent_model_volume    = var.enable_persistent_model_volume
    model_volume_id                   = var.enable_persistent_model_volume ? aws_ebs_volume.model_cache[0].id : ""
    model_volume_device               = var.model_volume_device
    enable_tailscale                  = var.enable_tailscale
    tailscale_auth_key_parameter_name = var.enable_tailscale ? var.tailscale_auth_key_parameter_name : ""
    tailscale_hostname                = coalesce(var.tailscale_hostname, var.name)
  }))

  block_device_mappings {
    device_name = var.root_device_name

    ebs {
      volume_size           = var.root_volume_gb
      volume_type           = "gp3"
      encrypted             = true
      delete_on_termination = true
    }
  }

  metadata_options {
    http_endpoint               = "enabled"
    http_tokens                 = "required"
    instance_metadata_tags      = "enabled"
    http_put_response_hop_limit = 2
  }

  monitoring {
    enabled = var.enable_detailed_monitoring
  }

  tag_specifications {
    resource_type = "instance"

    tags = merge(local.tags, {
      Name = var.name
    })
  }

  tags = local.tags
}

resource "aws_lb" "ollama" {
  count = var.enable_alb ? 1 : 0

  name               = substr(replace("${var.name}-alb", "_", "-"), 0, 32)
  internal           = var.alb_internal
  load_balancer_type = "application"
  security_groups    = [aws_security_group.alb[0].id]
  subnets            = local.subnet_ids

  idle_timeout = var.alb_idle_timeout_seconds

  tags = local.tags
}

resource "aws_lb_target_group" "ollama" {
  count = var.enable_alb ? 1 : 0

  name        = substr(replace("${var.name}-tg", "_", "-"), 0, 32)
  port        = 11434
  protocol    = "HTTP"
  vpc_id      = data.aws_vpc.selected.id
  target_type = "instance"

  health_check {
    enabled             = true
    path                = "/api/tags"
    protocol            = "HTTP"
    matcher             = "200"
    interval            = 30
    timeout             = 10
    healthy_threshold   = 2
    unhealthy_threshold = 5
  }

  tags = local.tags
}

resource "aws_lb_listener" "http" {
  count = var.enable_alb ? 1 : 0

  load_balancer_arn = aws_lb.ollama[0].arn
  port              = 80
  protocol          = "HTTP"

  default_action {
    type             = var.acm_certificate_arn == null ? "forward" : "redirect"
    target_group_arn = var.acm_certificate_arn == null ? aws_lb_target_group.ollama[0].arn : null

    dynamic "redirect" {
      for_each = var.acm_certificate_arn == null ? [] : [1]
      content {
        port        = "443"
        protocol    = "HTTPS"
        status_code = "HTTP_301"
      }
    }
  }
}

resource "aws_lb_listener" "https" {
  count = var.enable_alb && var.acm_certificate_arn != null ? 1 : 0

  load_balancer_arn = aws_lb.ollama[0].arn
  port              = 443
  protocol          = "HTTPS"
  certificate_arn   = var.acm_certificate_arn
  ssl_policy        = "ELBSecurityPolicy-TLS13-1-2-2021-06"

  default_action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.ollama[0].arn
  }
}

resource "aws_autoscaling_group" "ollama" {
  name                = "${var.name}-asg"
  min_size            = var.min_size
  max_size            = var.max_size
  desired_capacity    = var.desired_capacity
  vpc_zone_identifier = local.instance_subnet_ids

  health_check_type         = var.enable_alb ? "ELB" : "EC2"
  health_check_grace_period = var.health_check_grace_period_seconds

  target_group_arns = var.enable_alb ? [aws_lb_target_group.ollama[0].arn] : []

  mixed_instances_policy {
    instances_distribution {
      on_demand_base_capacity                  = var.on_demand_base_capacity
      on_demand_percentage_above_base_capacity = var.on_demand_percentage
      spot_allocation_strategy                 = var.spot_allocation_strategy
    }

    launch_template {
      launch_template_specification {
        launch_template_id = aws_launch_template.ollama.id
        version            = "$Latest"
      }

      dynamic "override" {
        for_each = var.instance_types
        content {
          instance_type = override.value
        }
      }
    }
  }

  dynamic "instance_refresh" {
    for_each = var.enable_instance_refresh ? [1] : []
    content {
      strategy = "Rolling"
      preferences {
        min_healthy_percentage = 0
      }
    }
  }

  tag {
    key                 = "Name"
    value               = var.name
    propagate_at_launch = true
  }

  lifecycle {
    ignore_changes = [desired_capacity]

    precondition {
      condition     = !var.enable_tailscale || var.tailscale_auth_key_parameter_name != null
      error_message = "tailscale_auth_key_parameter_name must be set when enable_tailscale is true."
    }

    precondition {
      condition     = !var.enable_persistent_model_volume || length(local.persistent_volume_subnet_ids) > 0
      error_message = "No configured subnet is in persistent_volume_az. Choose a subnet in that AZ or disable the persistent model volume."
    }

    precondition {
      condition     = !var.enable_persistent_model_volume || var.max_size == 1
      error_message = "max_size must be 1 when enable_persistent_model_volume is true because an EBS volume can attach to only one instance."
    }
  }
}

data "archive_file" "scale_lambda_zip" {
  count = var.enable_scheduled_scaling ? 1 : 0

  type        = "zip"
  source_file = "${path.module}/lambda_scale_asg.py"
  output_path = "${path.module}/.terraform/${var.name}-scale-lambda.zip"
}

resource "aws_iam_role" "scale_lambda" {
  count = var.enable_scheduled_scaling ? 1 : 0

  name = "${var.name}-scale-lambda-role"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect = "Allow"
      Principal = {
        Service = "lambda.amazonaws.com"
      }
      Action = "sts:AssumeRole"
    }]
  })

  tags = local.tags
}

resource "aws_iam_role_policy" "scale_lambda" {
  count = var.enable_scheduled_scaling ? 1 : 0

  name = "${var.name}-scale-lambda-policy"
  role = aws_iam_role.scale_lambda[0].id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect = "Allow"
        Action = [
          "autoscaling:SetDesiredCapacity",
          "autoscaling:DescribeAutoScalingGroups"
        ]
        Resource = "*"
      },
      {
        Effect = "Allow"
        Action = [
          "logs:CreateLogGroup",
          "logs:CreateLogStream",
          "logs:PutLogEvents"
        ]
        Resource = "*"
      }
    ]
  })
}

resource "aws_lambda_function" "scale_asg" {
  count = var.enable_scheduled_scaling ? 1 : 0

  function_name = "${var.name}-scale-asg"
  role          = aws_iam_role.scale_lambda[0].arn
  runtime       = "python3.12"
  handler       = "lambda_scale_asg.handler"
  filename      = data.archive_file.scale_lambda_zip[0].output_path

  source_code_hash = data.archive_file.scale_lambda_zip[0].output_base64sha256
  timeout          = 30

  environment {
    variables = {
      ASG_NAME = aws_autoscaling_group.ollama.name
    }
  }

  tags = local.tags
}

resource "aws_cloudwatch_event_rule" "scale_up" {
  count = var.enable_scheduled_scaling ? 1 : 0

  name                = "${var.name}-scale-up"
  schedule_expression = var.scale_up_cron_expression
  tags                = local.tags
}

resource "aws_cloudwatch_event_rule" "scale_down" {
  count = var.enable_scheduled_scaling ? 1 : 0

  name                = "${var.name}-scale-down"
  schedule_expression = var.scale_down_cron_expression
  tags                = local.tags
}

resource "aws_cloudwatch_event_target" "scale_up" {
  count = var.enable_scheduled_scaling ? 1 : 0

  rule = aws_cloudwatch_event_rule.scale_up[0].name
  arn  = aws_lambda_function.scale_asg[0].arn

  input = jsonencode({
    desired_capacity = var.scheduled_scale_up_capacity
  })
}

resource "aws_cloudwatch_event_target" "scale_down" {
  count = var.enable_scheduled_scaling ? 1 : 0

  rule = aws_cloudwatch_event_rule.scale_down[0].name
  arn  = aws_lambda_function.scale_asg[0].arn

  input = jsonencode({
    desired_capacity = 0
  })
}

resource "aws_lambda_permission" "allow_scale_up_eventbridge" {
  count = var.enable_scheduled_scaling ? 1 : 0

  statement_id  = "AllowExecutionFromEventBridgeScaleUp"
  action        = "lambda:InvokeFunction"
  function_name = aws_lambda_function.scale_asg[0].function_name
  principal     = "events.amazonaws.com"
  source_arn    = aws_cloudwatch_event_rule.scale_up[0].arn
}

resource "aws_lambda_permission" "allow_scale_down_eventbridge" {
  count = var.enable_scheduled_scaling ? 1 : 0

  statement_id  = "AllowExecutionFromEventBridgeScaleDown"
  action        = "lambda:InvokeFunction"
  function_name = aws_lambda_function.scale_asg[0].function_name
  principal     = "events.amazonaws.com"
  source_arn    = aws_cloudwatch_event_rule.scale_down[0].arn
}
