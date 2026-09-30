output "cluster_name" {
  value = module.eks.cluster_name
}

output "cluster_endpoint" {
  value = module.eks.cluster_endpoint
}

output "rds_endpoint" {
  description = "DB_URL = jdbc:postgresql://<rds_address>:5432/<svc>db?sslmode=require"
  value       = module.rds.endpoint
}

output "rds_address" {
  value = module.rds.address
}

output "rds_master_user_secret_arn" {
  value = module.rds.master_user_secret_arn
}

output "msk_bootstrap_brokers_sasl_iam" {
  description = "KAFKA_BOOTSTRAP_SERVERS (port 9098, SASL_SSL + AWS_MSK_IAM)."
  value       = module.msk.bootstrap_brokers_sasl_iam
}

output "redis_primary_endpoint" {
  description = "REDIS_HOST (TLS + AUTH)."
  value       = module.elasticache.primary_endpoint
}

output "elasticsearch_url" {
  description = "ELASTICSEARCH_URL (Elastic Cloud over PrivateLink; API key from ecom/<env>/search/elasticsearch)."
  value       = module.elastic_cloud.elasticsearch_url
}

output "ecr_registry" {
  description = "IMAGE_REGISTRY for spring-boot:build-image / kustomize."
  value       = module.ecr.registry
}

output "ecr_repository_urls" {
  value = module.ecr.repository_urls
}

output "images_bucket" {
  description = "IMAGES_BUCKET"
  value       = module.s3.bucket_name
}

output "cloudfront_domain_name" {
  description = "IMAGES_CDN_BASE_URL = https://<this>"
  value       = module.s3.cloudfront_domain_name
}

output "irsa_role_arns" {
  description = "ServiceAccount => role ARN for the eks.amazonaws.com/role-arn annotation."
  value       = module.iam_irsa.role_arns
}

output "external_secrets_role_arn" {
  value = module.eks.external_secrets_role_arn
}

output "github_deploy_role_arn" {
  value = module.github_oidc.deploy_role_arn
}

output "github_terraform_role_arn" {
  value = module.github_oidc.terraform_role_arn
}

output "github_terraform_plan_role_arn" {
  value = module.github_oidc.terraform_plan_role_arn
}

output "secret_arns" {
  value = module.secrets.secret_arns
}

output "kafka_topics" {
  value = var.enable_kafka_topics ? module.kafka_topics[0].topic_names : []
}
