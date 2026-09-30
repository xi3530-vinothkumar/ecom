variable "secrets" {
  description = "secret name => description (documents the expected JSON keys)."
  type        = map(string)
}

variable "recovery_window_in_days" {
  type    = number
  default = 7
}
