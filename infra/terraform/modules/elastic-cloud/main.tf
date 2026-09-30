# Elastic Cloud (Elasticsearch Service) deployment on AWS, reachable ONLY from the VPC over AWS PrivateLink.
# Auth: API key created out-of-band (see infra/terraform/README.md), stored in ecom/<env>/search/elasticsearch.
# The deployment's `elastic` superuser password is never read or output here.

resource "aws_security_group" "this" {
  name        = "${var.name}-elastic-privatelink"
  description = "Elastic Cloud PrivateLink HTTPS from EKS nodes/pods only"
  vpc_id      = var.vpc_id

  ingress {
    from_port       = 443
    to_port         = 443
    protocol        = "tcp"
    security_groups = var.allowed_security_group_ids
  }
}

resource "aws_vpc_endpoint" "this" {
  vpc_id              = var.vpc_id
  service_name        = var.privatelink_service_name
  vpc_endpoint_type   = "Interface"
  subnet_ids          = var.subnet_ids
  security_group_ids  = [aws_security_group.this.id]
  private_dns_enabled = false

  tags = {
    Name = "${var.name}-elastic-privatelink"
  }
}

# <alias>.es.vpce.<region>.aws.elastic-cloud.com resolves to the endpoint ENIs inside the VPC.
resource "aws_route53_zone" "this" {
  name = local.privatelink_domain

  vpc {
    vpc_id = var.vpc_id
  }
}

resource "aws_route53_record" "wildcard" {
  zone_id = aws_route53_zone.this.zone_id
  name    = "*.${local.privatelink_domain}"
  type    = "CNAME"
  ttl     = 60
  records = [aws_vpc_endpoint.this.dns_entry[0].dns_name]
}

# Only traffic arriving through this VPC endpoint is accepted (no public access).
resource "ec_deployment_traffic_filter" "this" {
  name   = "${var.name}-privatelink"
  region = local.ec_region
  type   = "vpce"

  rule {
    source = aws_vpc_endpoint.this.id
  }
}

resource "ec_deployment" "this" {
  name                   = var.name
  alias                  = var.name
  region                 = local.ec_region
  version                = var.elasticsearch_version
  deployment_template_id = var.deployment_template_id
  traffic_filter         = [ec_deployment_traffic_filter.this.id]

  elasticsearch = {
    hot = {
      size        = var.size
      zone_count  = var.zone_count
      autoscaling = {}
    }
  }

  kibana = {
    size       = "1g"
    zone_count = 1
  }
}

locals {
  ec_region          = "aws-${var.region}"
  privatelink_domain = "vpce.${var.region}.aws.elastic-cloud.com"
}
