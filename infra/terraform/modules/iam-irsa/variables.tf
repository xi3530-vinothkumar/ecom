variable "env" {
  type = string
}

variable "namespace" {
  type    = string
  default = "ecom"
}

variable "oidc_provider_arn" {
  type = string
}

variable "oidc_provider_host" {
  description = "OIDC issuer without https:// (oidc.eks.<region>.amazonaws.com/id/XXXX)."
  type        = string
}

variable "msk_cluster_arn" {
  type = string
}

variable "images_bucket_arn" {
  type = string
}
