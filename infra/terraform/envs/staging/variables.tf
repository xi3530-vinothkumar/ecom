variable "env" {
  type = string
}

variable "aws_account_id" {
  description = "PLACEHOLDER: target AWS account (guards against applying into the wrong account)."
  type        = string
}

variable "region" {
  type    = string
  default = "eu-west-1"
}

variable "vpc_cidr" {
  type = string
}

variable "single_nat_gateway" {
  type    = bool
  default = false
}

variable "kubernetes_version" {
  type    = string
  default = "1.31"
}

variable "eks_public_access_cidrs" {
  description = "PLACEHOLDER: office/VPN/CI egress CIDRs allowed to reach the EKS API."
  type        = list(string)
}

variable "node_instance_types" {
  type    = list(string)
  default = ["m6i.large"]
}

variable "node_min_size" {
  type    = number
  default = 2
}

variable "node_desired_size" {
  type    = number
  default = 3
}

variable "node_max_size" {
  type    = number
  default = 6
}

variable "admin_runner_cidrs" {
  description = "Self-hosted runner / bastion CIDRs allowed to RDS:5432 and MSK:9098 (db-bootstrap, kafka-topics)."
  type        = list(string)
  default     = []
}

variable "rds_instance_class" {
  type    = string
  default = "db.t4g.medium"
}

variable "rds_multi_az" {
  type    = bool
  default = false
}

variable "rds_backup_retention_days" {
  type    = number
  default = 7
}

variable "deletion_protection" {
  type    = bool
  default = false
}

variable "redis_node_type" {
  type    = string
  default = "cache.t4g.small"
}

variable "redis_auth_token" {
  description = "Set via TF_VAR_redis_auth_token (same value as secret ecom/<env>/redis auth_token). Never commit."
  type        = string
  sensitive   = true
}

variable "msk_broker_instance_type" {
  type    = string
  default = "kafka.t3.small"
}

variable "elasticsearch_version" {
  type    = string
  default = "9.5.4"
}

variable "elasticsearch_size" {
  description = "Elastic Cloud hot tier RAM per zone."
  type        = string
  default     = "4g"
}

variable "elasticsearch_zone_count" {
  type    = number
  default = 2
}

variable "images_upload_allowed_origins" {
  description = "PLACEHOLDER: storefront/admin origins, e.g. https://shop.example.com."
  type        = list(string)
}

variable "images_cdn_aliases" {
  type    = list(string)
  default = []
}

variable "images_cdn_acm_certificate_arn" {
  description = "us-east-1 ACM cert for images_cdn_aliases; null => *.cloudfront.net."
  type        = string
  default     = null
}

variable "secret_recovery_window_days" {
  type    = number
  default = 7
}

variable "enable_db_bootstrap" {
  description = "Create the 6 DBs + roles via the postgresql provider (needs VPC reach). Default: use the bootstrap Job."
  type        = bool
  default     = false
}

variable "enable_kafka_topics" {
  description = "Manage topics via the Mongey/kafka provider (needs VPC reach to MSK :9098)."
  type        = bool
  default     = false
}

variable "github_repo" {
  description = "PLACEHOLDER: owner/repo."
  type        = string
}

variable "github_environments" {
  type = list(string)
}

variable "create_github_oidc_provider" {
  type    = bool
  default = true
}

variable "state_bucket_name" {
  description = "PLACEHOLDER: same bucket as in backend.hcl."
  type        = string
}

variable "lock_table_name" {
  description = "PLACEHOLDER: same DynamoDB table as in backend.hcl."
  type        = string
}
