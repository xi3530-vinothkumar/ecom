locals {
  name         = "ecom-${var.env}"
  cluster_name = "ecom-${var.env}"

  services     = ["user", "catalog", "inventory", "cart", "order", "payment", "notification", "search"]
  db_services  = ["user", "catalog", "inventory", "order", "payment", "notification"]
  repositories = concat(["ecom-gateway"], [for s in local.services : "ecom-${s}-service"])

  # Contract with infra/k8s ExternalSecrets (remoteRef.key / property).
  secrets = merge(
    { for s in local.db_services : "ecom/${var.env}/${s}/db" => "JSON {\"username\":\"${s}\",\"password\":\"...\"} for database ${s}db" },
    {
      "ecom/${var.env}/user/jwt"             = "JSON {\"private_key_pem\":\"-----BEGIN PRIVATE KEY-----...\"} RS256 PKCS#8 signing key"
      "ecom/${var.env}/payment/psp"          = "JSON {\"api_key\":\"sk_...\",\"webhook_secret\":\"whsec_...\"}"
      "ecom/${var.env}/redis"                = "JSON {\"auth_token\":\"...\"} ElastiCache AUTH token (same value as TF_VAR_redis_auth_token)"
      "ecom/${var.env}/search/elasticsearch" = "JSON {\"api_key\":\"...\"} Elastic Cloud API key (encoded id:key) limited to the products index"
    },
  )

  data_store_client_sgs = [module.eks.cluster_security_group_id]
}

module "vpc" {
  source             = "../../modules/vpc"
  name               = local.name
  cluster_name       = local.cluster_name
  region             = var.region
  cidr               = var.vpc_cidr
  single_nat_gateway = var.single_nat_gateway
}

module "eks" {
  source                       = "../../modules/eks"
  env                          = var.env
  cluster_name                 = local.cluster_name
  kubernetes_version           = var.kubernetes_version
  vpc_id                       = module.vpc.vpc_id
  private_subnet_ids           = module.vpc.private_subnet_ids
  endpoint_public_access_cidrs = var.eks_public_access_cidrs
  node_instance_types          = var.node_instance_types
  node_min_size                = var.node_min_size
  node_desired_size            = var.node_desired_size
  node_max_size                = var.node_max_size
}

module "secrets" {
  source                  = "../../modules/secrets"
  secrets                 = local.secrets
  recovery_window_in_days = var.secret_recovery_window_days
}

module "rds" {
  source                     = "../../modules/rds"
  name                       = local.name
  vpc_id                     = module.vpc.vpc_id
  subnet_ids                 = module.vpc.data_subnet_ids
  allowed_security_group_ids = local.data_store_client_sgs
  allowed_cidr_blocks        = var.admin_runner_cidrs
  instance_class             = var.rds_instance_class
  multi_az                   = var.rds_multi_az
  deletion_protection        = var.deletion_protection
  backup_retention_days      = var.rds_backup_retention_days
}

data "aws_secretsmanager_secret_version" "rds_master" {
  count     = var.enable_db_bootstrap ? 1 : 0
  secret_id = module.rds.master_user_secret_arn
}

module "db_bootstrap" {
  count  = var.enable_db_bootstrap ? 1 : 0
  source = "../../modules/db-bootstrap"
  databases = {
    for s in local.db_services : s => {
      database    = "${s}db"
      secret_name = "ecom/${var.env}/${s}/db"
    }
  }
  depends_on = [module.secrets]
}

module "elasticache" {
  source                     = "../../modules/elasticache"
  name                       = local.name
  vpc_id                     = module.vpc.vpc_id
  subnet_ids                 = module.vpc.data_subnet_ids
  allowed_security_group_ids = local.data_store_client_sgs
  node_type                  = var.redis_node_type
  auth_token                 = var.redis_auth_token
}

module "msk" {
  source                     = "../../modules/msk"
  name                       = local.name
  vpc_id                     = module.vpc.vpc_id
  subnet_ids                 = module.vpc.data_subnet_ids
  allowed_security_group_ids = local.data_store_client_sgs
  allowed_cidr_blocks        = var.admin_runner_cidrs
  broker_instance_type       = var.msk_broker_instance_type
}

module "kafka_topics" {
  count       = var.enable_kafka_topics ? 1 : 0
  source      = "../../modules/kafka-topics"
  topics_file = "${path.root}/../../../../ecom-common-events/src/main/resources/kafka-topics.yaml"
  depends_on  = [module.msk]
}

module "elastic_cloud" {
  source                     = "../../modules/elastic-cloud"
  name                       = local.name
  region                     = var.region
  vpc_id                     = module.vpc.vpc_id
  subnet_ids                 = module.vpc.data_subnet_ids
  allowed_security_group_ids = local.data_store_client_sgs
  elasticsearch_version      = var.elasticsearch_version
  size                       = var.elasticsearch_size
  zone_count                 = var.elasticsearch_zone_count
}

module "ecr" {
  source       = "../../modules/ecr"
  repositories = local.repositories
  force_delete = !var.deletion_protection
}

module "s3" {
  source                 = "../../modules/s3"
  bucket_name            = "ecom-${var.env}-product-images-${var.aws_account_id}"
  force_destroy          = !var.deletion_protection
  upload_allowed_origins = var.images_upload_allowed_origins
  cdn_aliases            = var.images_cdn_aliases
  acm_certificate_arn    = var.images_cdn_acm_certificate_arn
}

module "iam_irsa" {
  source             = "../../modules/iam-irsa"
  env                = var.env
  oidc_provider_arn  = module.eks.oidc_provider_arn
  oidc_provider_host = module.eks.oidc_provider_host
  msk_cluster_arn    = module.msk.cluster_arn
  images_bucket_arn  = module.s3.bucket_arn
}

module "github_oidc" {
  source               = "../../modules/github-oidc"
  env                  = var.env
  github_repo          = var.github_repo
  github_environments  = var.github_environments
  create_oidc_provider = var.create_github_oidc_provider
  ecr_repository_arns  = module.ecr.repository_arns
  eks_cluster_name     = module.eks.cluster_name
  eks_cluster_arn      = module.eks.cluster_arn
  state_bucket_name    = var.state_bucket_name
  lock_table_name      = var.lock_table_name
}

# Lets ESO sync the RDS-managed master secret (named rds!db-..., outside ecom/<env>/*) for the DB bootstrap Job.
resource "aws_iam_role_policy" "external_secrets_rds_master" {
  name = "read-rds-master-secret"
  role = module.eks.external_secrets_role_name
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect   = "Allow"
      Action   = ["secretsmanager:GetSecretValue", "secretsmanager:DescribeSecret"]
      Resource = module.rds.master_user_secret_arn
    }]
  })
}
