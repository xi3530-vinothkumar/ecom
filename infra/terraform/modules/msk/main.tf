# MSK, 3 brokers across 3 AZs, IAM auth only (SASL/IAM over TLS, port 9098).
# Topics are managed by modules/kafka-topics; auto-create is off.

resource "aws_security_group" "this" {
  name        = "${var.name}-msk"
  description = "MSK IAM listener from EKS nodes/pods only"
  vpc_id      = var.vpc_id

  ingress {
    description     = "SASL/IAM TLS"
    from_port       = 9098
    to_port         = 9098
    protocol        = "tcp"
    security_groups = var.allowed_security_group_ids
  }

  dynamic "ingress" {
    for_each = length(var.allowed_cidr_blocks) > 0 ? [1] : []
    content {
      description = "SASL/IAM TLS from admin/runner CIDRs (kafka-topics)"
      from_port   = 9098
      to_port     = 9098
      protocol    = "tcp"
      cidr_blocks = var.allowed_cidr_blocks
    }
  }
}

resource "aws_msk_configuration" "this" {
  name           = "${var.name}-config"
  kafka_versions = [var.kafka_version]

  server_properties = <<-PROPS
    auto.create.topics.enable=false
    default.replication.factor=3
    min.insync.replicas=2
    num.partitions=6
    unclean.leader.election.enable=false
    log.retention.hours=168
  PROPS
}

resource "aws_cloudwatch_log_group" "this" {
  name              = "/aws/msk/${var.name}"
  retention_in_days = 14
}

resource "aws_msk_cluster" "this" {
  cluster_name           = var.name
  kafka_version          = var.kafka_version
  number_of_broker_nodes = 3

  broker_node_group_info {
    instance_type   = var.broker_instance_type
    client_subnets  = var.subnet_ids
    security_groups = [aws_security_group.this.id]
    storage_info {
      ebs_storage_info {
        volume_size = var.broker_volume_size
      }
    }
  }

  configuration_info {
    arn      = aws_msk_configuration.this.arn
    revision = aws_msk_configuration.this.latest_revision
  }

  client_authentication {
    unauthenticated = false
    sasl {
      iam = true
    }
  }

  encryption_info {
    encryption_in_transit {
      client_broker = "TLS"
      in_cluster    = true
    }
  }

  open_monitoring {
    prometheus {
      jmx_exporter {
        enabled_in_broker = true
      }
      node_exporter {
        enabled_in_broker = true
      }
    }
  }

  logging_info {
    broker_logs {
      cloudwatch_logs {
        enabled   = true
        log_group = aws_cloudwatch_log_group.this.name
      }
    }
  }
}
