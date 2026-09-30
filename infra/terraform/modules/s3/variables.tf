variable "bucket_name" {
  type = string
}

variable "force_destroy" {
  type    = bool
  default = false
}

variable "upload_allowed_origins" {
  description = "Browser origins allowed to PUT via pre-signed URLs (storefront/admin UI). PLACEHOLDER."
  type        = list(string)
}

variable "cdn_aliases" {
  description = "Custom CNAMEs (e.g. images.example.com); requires acm_certificate_arn in us-east-1."
  type        = list(string)
  default     = []
}

variable "acm_certificate_arn" {
  type    = string
  default = null
}
