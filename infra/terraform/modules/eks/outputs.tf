output "cluster_name" {
  value = aws_eks_cluster.this.name
}

output "cluster_arn" {
  value = aws_eks_cluster.this.arn
}

output "cluster_endpoint" {
  value = aws_eks_cluster.this.endpoint
}

output "cluster_certificate_authority_data" {
  value = aws_eks_cluster.this.certificate_authority[0].data
}

output "cluster_security_group_id" {
  description = "EKS-managed cluster SG, attached to managed-node-group nodes and therefore to pod traffic. Data stores allow ingress from it."
  value       = aws_eks_cluster.this.vpc_config[0].cluster_security_group_id
}

output "oidc_provider_arn" {
  value = aws_iam_openid_connect_provider.this.arn
}

output "oidc_provider_host" {
  value = local.oidc_provider_host
}

output "external_secrets_role_arn" {
  value = aws_iam_role.external_secrets.arn
}

output "external_secrets_role_name" {
  value = aws_iam_role.external_secrets.name
}
