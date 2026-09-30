variable "topics_file" {
  description = "Path to ecom-common-events/src/main/resources/kafka-topics.yaml."
  type        = string
}

variable "replication_factor" {
  type    = number
  default = 3
}

variable "min_insync_replicas" {
  type    = number
  default = 2
}

variable "dlt_retention_ms" {
  description = "DLTs keep records longer than main topics (30 days) to leave room for triage + replay."
  type        = number
  default     = 2592000000
}
