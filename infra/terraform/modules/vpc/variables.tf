variable "name" {
  type = string
}

variable "cluster_name" {
  description = "EKS cluster name, used for subnet discovery tags."
  type        = string
}

variable "region" {
  type = string
}

variable "cidr" {
  type    = string
  default = "10.0.0.0/16"
}

variable "az_count" {
  type    = number
  default = 3
}

variable "single_nat_gateway" {
  description = "One NAT for all AZs (cheaper, dev only) vs one per AZ."
  type        = bool
  default     = false
}

variable "flow_logs_bucket_arn" {
  type    = string
  default = null
}
