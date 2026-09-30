variable "env" {
  type = string
}

variable "cluster_name" {
  type = string
}

variable "kubernetes_version" {
  type    = string
  default = "1.31"
}

variable "vpc_id" {
  type = string
}

variable "private_subnet_ids" {
  type = list(string)
}

variable "endpoint_public_access" {
  type    = bool
  default = true
}

variable "endpoint_public_access_cidrs" {
  description = "CIDRs allowed to reach the public API endpoint (office/VPN + CI). PLACEHOLDER."
  type        = list(string)
  default     = ["0.0.0.0/0"]
}

variable "node_instance_types" {
  type    = list(string)
  default = ["m6i.large"]
}

variable "node_capacity_type" {
  type    = string
  default = "ON_DEMAND"
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

variable "node_disk_size" {
  type    = number
  default = 50
}

variable "chart_versions" {
  type = object({
    aws_load_balancer_controller = string
    external_secrets             = string
    metrics_server               = string
    cluster_autoscaler           = string
  })
  default = {
    aws_load_balancer_controller = "1.10.1"
    external_secrets             = "0.12.1"
    metrics_server               = "3.12.2"
    cluster_autoscaler           = "9.43.2"
  }
}
