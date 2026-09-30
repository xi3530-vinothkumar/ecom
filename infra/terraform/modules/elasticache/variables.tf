variable "name" {
  type = string
}

variable "vpc_id" {
  type = string
}

variable "subnet_ids" {
  type = list(string)
}

variable "allowed_security_group_ids" {
  type = list(string)
}

variable "engine_version" {
  type    = string
  default = "7.1"
}

variable "node_type" {
  type    = string
  default = "cache.t4g.small"
}

variable "auth_token" {
  description = "Redis AUTH token (16-128 chars). Supply via TF_VAR_redis_auth_token from the same value stored in ecom/<env>/redis."
  type        = string
  sensitive   = true
}

variable "snapshot_retention_days" {
  type    = number
  default = 1
}
