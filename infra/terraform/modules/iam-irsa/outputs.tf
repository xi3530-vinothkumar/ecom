output "role_arns" {
  description = "ServiceAccount name => IAM role ARN (eks.amazonaws.com/role-arn annotation)."
  value       = { for k, r in aws_iam_role.sa : k => r.arn }
}
