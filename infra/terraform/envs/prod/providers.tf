provider "aws" {
  region              = var.region
  allowed_account_ids = [var.aws_account_id]

  default_tags {
    tags = {
      Project     = "ecom"
      Environment = var.env
      ManagedBy   = "terraform"
    }
  }
}

provider "helm" {
  kubernetes {
    host                   = module.eks.cluster_endpoint
    cluster_ca_certificate = base64decode(module.eks.cluster_certificate_authority_data)
    exec {
      api_version = "client.authentication.k8s.io/v1beta1"
      command     = "aws"
      args        = ["eks", "get-token", "--cluster-name", module.eks.cluster_name, "--region", var.region]
    }
  }
}

# Used only when enable_db_bootstrap = true (needs network reach to the private RDS endpoint).
provider "postgresql" {
  host            = module.rds.address
  port            = module.rds.port
  username        = module.rds.master_username
  password        = var.enable_db_bootstrap ? jsondecode(data.aws_secretsmanager_secret_version.rds_master[0].secret_string)["password"] : ""
  sslmode         = "require"
  superuser       = false # RDS master is rds_superuser, not a real superuser
  connect_timeout = 15
}

# Used only when enable_kafka_topics = true (needs network reach to MSK :9098). IAM auth via the caller's AWS creds.
provider "kafka" {
  bootstrap_servers = compact(split(",", module.msk.bootstrap_brokers_sasl_iam))
  tls_enabled       = true
  sasl_mechanism    = "aws-iam"
  sasl_aws_region   = var.region
}

# Elastic Cloud: credentials from EC_API_KEY (Elastic Cloud organization API key; CI secret).
provider "ec" {}
