variable "name" {
  description = "Deployment name and alias (also the URL prefix)."
  type        = string
}

variable "region" {
  description = "AWS region; the Elastic Cloud region is aws-<region>."
  type        = string
}

variable "vpc_id" {
  type = string
}

variable "subnet_ids" {
  description = "Subnets for the PrivateLink endpoint ENIs."
  type        = list(string)
}

variable "allowed_security_group_ids" {
  type = list(string)
}

variable "privatelink_service_name" {
  description = "Elastic Cloud PrivateLink service for the region (see Elastic docs, 'AWS PrivateLink traffic filters')."
  type        = string
  default     = "com.amazonaws.vpce.eu-west-1.vpce-svc-01f2afe87944eb12b"
}

variable "elasticsearch_version" {
  type    = string
  default = "9.5.4"
}

variable "deployment_template_id" {
  type    = string
  default = "aws-storage-optimized"
}

variable "size" {
  description = "Hot tier RAM per zone, e.g. 4g."
  type        = string
  default     = "4g"
}

variable "zone_count" {
  description = "2 in dev/staging, 3 in prod."
  type        = number
  default     = 2
}
