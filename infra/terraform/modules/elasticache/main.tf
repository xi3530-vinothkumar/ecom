# Redis 7, cluster-mode off, 1 primary + 1 replica, Multi-AZ. Shared by Cart and the Gateway rate limiter
# (separate key prefixes). TLS in transit + AUTH token => services need SPRING_DATA_REDIS_SSL_ENABLED=true
# and SPRING_DATA_REDIS_PASSWORD (from secret ecom/<env>/redis, key auth_token).

resource "aws_elasticache_subnet_group" "this" {
  name       = var.name
  subnet_ids = var.subnet_ids
}

resource "aws_security_group" "this" {
  name        = "${var.name}-redis"
  description = "Redis from EKS nodes/pods only"
  vpc_id      = var.vpc_id

  ingress {
    from_port       = 6379
    to_port         = 6379
    protocol        = "tcp"
    security_groups = var.allowed_security_group_ids
  }
}

resource "aws_elasticache_parameter_group" "this" {
  name   = "${var.name}-redis7"
  family = "redis7"
}

resource "aws_elasticache_replication_group" "this" {
  replication_group_id = var.name
  description          = "ecom Redis (cart + gateway rate limiter)"
  engine               = "redis"
  engine_version       = var.engine_version
  node_type            = var.node_type
  port                 = 6379
  parameter_group_name = aws_elasticache_parameter_group.this.name

  num_cache_clusters         = 2
  automatic_failover_enabled = true
  multi_az_enabled           = true

  subnet_group_name  = aws_elasticache_subnet_group.this.name
  security_group_ids = [aws_security_group.this.id]

  at_rest_encryption_enabled = true
  transit_encryption_enabled = true
  auth_token                 = var.auth_token

  snapshot_retention_limit   = var.snapshot_retention_days
  auto_minor_version_upgrade = true
  apply_immediately          = true
}
