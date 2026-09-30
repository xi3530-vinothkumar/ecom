# Secrets Manager entries ONLY. Values are set out-of-band (aws secretsmanager put-secret-value ...),
# so they never appear in Terraform state. External Secrets Operator syncs them into the cluster.

resource "aws_secretsmanager_secret" "this" {
  for_each                = var.secrets
  name                    = each.key
  description             = each.value
  recovery_window_in_days = var.recovery_window_in_days
}
