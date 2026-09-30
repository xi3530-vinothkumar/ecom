# Non-secret per-env values. PLACEHOLDERS marked REPLACE_ME. Secrets: TF_VAR_redis_auth_token.
env            = "dev"
aws_account_id = "111111111111" # REPLACE_ME
region         = "eu-west-1"
vpc_cidr       = "10.10.0.0/16"

single_nat_gateway      = true
eks_public_access_cidrs = ["203.0.113.0/24"] # REPLACE_ME: office/VPN/CI egress
node_instance_types     = ["t3.large"]
node_min_size           = 2
node_desired_size       = 2
node_max_size           = 4

rds_instance_class        = "db.t4g.medium"
rds_multi_az              = false
rds_backup_retention_days = 1
deletion_protection       = false

redis_node_type          = "cache.t4g.small"
msk_broker_instance_type = "kafka.t3.small"
elasticsearch_size       = "4g"
elasticsearch_zone_count = 2

images_upload_allowed_origins = ["https://dev.shop.example.com"] # REPLACE_ME
secret_recovery_window_days   = 0

github_repo                 = "REPLACE_ME_ORG/ecom"
github_environments         = ["dev"]
create_github_oidc_provider = true

state_bucket_name = "REPLACE_ME-ecom-terraform-state"
lock_table_name   = "REPLACE_ME-ecom-terraform-locks"
