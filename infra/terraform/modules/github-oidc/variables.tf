variable "env" {
  type = string
}

variable "github_repo" {
  description = "owner/repo, e.g. \"ORG/ecom\". PLACEHOLDER."
  type        = string
}

variable "github_environments" {
  description = "GitHub Environment names whose jobs may assume the deploy/terraform roles."
  type        = list(string)
}

variable "create_oidc_provider" {
  description = "false when several envs share one AWS account (the provider is account-global; create it in one env only)."
  type        = bool
  default     = true
}

variable "ecr_repository_arns" {
  type = list(string)
}

variable "eks_cluster_name" {
  type = string
}

variable "eks_cluster_arn" {
  type = string
}

variable "state_bucket_name" {
  type = string
}

variable "lock_table_name" {
  type = string
}
