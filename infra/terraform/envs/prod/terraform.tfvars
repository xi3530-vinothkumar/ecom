# Non-secret per-env values. PLACEHOLDERS marked REPLACE_ME. Secrets: TF_VAR_redis_auth_token.
env            = "prod"
aws_account_id = "333333333333" # REPLACE_ME
region         = "eu-west-1"
vpc_cidr       = "10.30.0.0/16"

single_nat_gateway      = false
eks_public_access_cidrs = ["203.0.113.0/24"] # REPLACE_ME
node_instance_types     = ["m6i.xlarge"]
node_min_size           = 3
node_desired_size       = 4
node_max_size           = 10

rds_instance_class        = "db.m7g.large"
rds_multi_az              = true
rds_backup_retention_days = 14
deletion_protection       = true

redis_node_type          = "cache.m7g.large"
msk_broker_instance_type = "kafka.m7g.large"
elasticsearch_size       = "8g"
elasticsearch_zone_count = 3

images_upload_allowed_origins = ["https://shop.example.com"] # REPLACE_ME
secret_recovery_window_days   = 30

github_repo                 = "REPLACE_ME_ORG/ecom"
github_environments         = ["production"]
create_github_oidc_provider = true

state_bucket_name = "REPLACE_ME-ecom-terraform-state"
lock_table_name   = "REPLACE_ME-ecom-terraform-locks"
