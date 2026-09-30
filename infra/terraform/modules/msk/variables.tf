variable "name" {
  type = string
}

variable "vpc_id" {
  type = string
}

variable "subnet_ids" {
  description = "Exactly 3 subnets in 3 AZs."
  type        = list(string)
}

variable "allowed_security_group_ids" {
  type = list(string)
}

variable "allowed_cidr_blocks" {
  type    = list(string)
  default = []
}

variable "kafka_version" {
  type    = string
  default = "3.8.x"
}

variable "broker_instance_type" {
  type    = string
  default = "kafka.m7g.large"
}

variable "broker_volume_size" {
  type    = number
  default = 100
}
