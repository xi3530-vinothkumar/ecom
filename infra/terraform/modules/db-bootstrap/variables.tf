variable "databases" {
  description = "svc => { database, secret_name }"
  type = map(object({
    database    = string
    secret_name = string
  }))
}
