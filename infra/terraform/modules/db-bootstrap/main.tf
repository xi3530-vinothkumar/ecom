# Optional: creates the 6 logical databases + per-service login roles on the shared RDS instance.
# Requires network reach to the private RDS endpoint (self-hosted runner in the VPC, or a bastion tunnel),
# and the service secrets ecom/<env>/<svc>/db must already hold {"username","password"}.
# NOTE: role passwords end up in Terraform state (encrypted S3 backend). The alternative that keeps them
# out of state is the bootstrap Job in infra/terraform/README.md (default: enable_db_bootstrap = false).

terraform {
  required_providers {
    postgresql = {
      source = "cyrilgdn/postgresql"
    }
    aws = {
      source = "hashicorp/aws"
    }
  }
}

data "aws_secretsmanager_secret_version" "db" {
  for_each  = var.databases
  secret_id = each.value.secret_name
}

locals {
  creds = { for svc, d in var.databases : svc => jsondecode(data.aws_secretsmanager_secret_version.db[svc].secret_string) }
}

resource "postgresql_role" "svc" {
  for_each         = var.databases
  name             = local.creds[each.key].username
  login            = true
  password         = local.creds[each.key].password
  connection_limit = 50
}

resource "postgresql_database" "svc" {
  for_each          = var.databases
  name              = each.value.database
  owner             = postgresql_role.svc[each.key].name
  encoding          = "UTF8"
  lc_collate        = "en_US.UTF-8"
  lc_ctype          = "en_US.UTF-8"
  template          = "template0"
  allow_connections = true
}

# Only the owning role may connect to its database.
resource "postgresql_grant" "revoke_public_connect" {
  for_each    = var.databases
  database    = postgresql_database.svc[each.key].name
  role        = "public"
  object_type = "database"
  privileges  = []
}
