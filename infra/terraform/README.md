# ecom — AWS infrastructure (Terraform)

Creates the platform that `infra/k8s` assumes exists (PLAN §6a). One state per env (S3 + DynamoDB lock).

```
modules/
  vpc           3 AZ: public (ALB/NAT), private (EKS), data (RDS/Redis/MSK/Elastic PrivateLink endpoint, no internet route)
  eks           cluster + managed node group + OIDC; vpc-cni with NetworkPolicy enforcement ON;
                helm add-ons: aws-load-balancer-controller, external-secrets, metrics-server, cluster-autoscaler
  rds           ONE Postgres 16 instance (Multi-AZ in prod), master password managed by RDS/Secrets Manager
  db-bootstrap  optional: 6 DBs + per-service roles via cyrilgdn/postgresql (needs VPC reach)
  elasticache   Redis 7, primary + replica, Multi-AZ, TLS + AUTH
  msk           3 brokers, IAM auth only (SASL_SSL, port 9098), auto.create.topics.enable=false
  kafka-topics  topics + .dlt twins from ecom-common-events/src/main/resources/kafka-topics.yaml (RF=3, min.insync=2)
  elastic-cloud Elasticsearch 9 on Elastic Cloud (elastic/ec provider), reachable only via AWS PrivateLink
                (VPC endpoint + private DNS zone + vpce traffic filter); search-service authenticates with an API key
  ecr           ecom-gateway + ecom-<svc>-service, immutable tags, scan_on_push, keep last 30
  s3            private product-images bucket + CloudFront (OAC)
  iam-irsa      role ecom-<env>-<sa> per ServiceAccount in namespace ecom (least privilege)
  github-oidc   GitHub OIDC + roles ecom-<env>-github-deploy / -github-terraform / -github-terraform-plan
  secrets       Secrets Manager entries only (values set out-of-band)
envs/{dev,staging,prod}   identical composition; per-env values in terraform.tfvars
```

## Placeholders to fill (grep `REPLACE_ME`)
- `terraform.tfvars`: `aws_account_id`, `eks_public_access_cidrs`, `images_upload_allowed_origins`, `github_repo`,
  `state_bucket_name`, `lock_table_name`; optionally `admin_runner_cidrs`, `images_cdn_aliases` + us-east-1 ACM cert.
- `backend.hcl` (copy of `backend.hcl.example`, gitignored): state bucket + lock table.
- `TF_VAR_redis_auth_token` (16–128 chars) — the same value goes into secret `ecom/<env>/redis`.
- If several envs share one AWS account, set `create_github_oidc_provider = false` in all but one.
- `EC_API_KEY`: Elastic Cloud organization API key for the `ec` provider (admin workstation / CI secret).

## Bootstrap order (first time, from an admin workstation)
1. Create the state bucket (versioned, encrypted) and lock table (`LockID` string key).
2. `cd envs/<env> && cp backend.hcl.example backend.hcl && terraform init -backend-config=backend.hcl`
3. `terraform apply` (creates VPC, EKS + add-ons, data stores, ECR, IAM, empty secrets, GitHub roles).
4. Put secret values out-of-band:
   ```
   aws secretsmanager put-secret-value --secret-id ecom/<env>/order/db --secret-string '{"username":"order","password":"..."}'
   aws secretsmanager put-secret-value --secret-id ecom/<env>/user/jwt  --secret-string "$(jq -Rs '{private_key_pem:.}' < jwt-private.pem)"
   aws secretsmanager put-secret-value --secret-id ecom/<env>/payment/psp --secret-string '{"api_key":"sk_...","webhook_secret":"whsec_..."}'
   aws secretsmanager put-secret-value --secret-id ecom/<env>/redis --secret-string '{"auth_token":"<TF_VAR_redis_auth_token>"}'
   aws secretsmanager put-secret-value --secret-id ecom/<env>/search/elasticsearch --secret-string '{"api_key":"<encoded>"}'
   ```
   The Elasticsearch API key is created once per env (Kibana Dev Tools or `POST /_security/api_key` as `elastic`),
   limited to the search-service's index; use the `encoded` value from the response:
   ```
   POST /_security/api_key
   {"name":"search-service","role_descriptors":{"search-service":{"cluster":["monitor"],
     "indices":[{"names":["products"],"privileges":["all"]}]}}}
   ```
5. Create databases/roles (below) and Kafka topics (`enable_kafka_topics = true`, run from inside the VPC).
6. Store role ARNs from `terraform output` as GitHub secrets (`AWS_TERRAFORM_ROLE_ARN` per Environment,
   `AWS_TERRAFORM_PLAN_ROLE_ARN_<ENV>` repo-level, deploy role for docker-publish/deploy), then CI takes over.

RDS, MSK and Elasticsearch (PrivateLink only) are private. GitHub-hosted runners cannot reach them, so the two data-plane modules are
gated: `enable_db_bootstrap` and `enable_kafka_topics` default to `false`. Enable them only when running from a
self-hosted runner in the VPC (`vars.TF_RUNNER` in infra.yml) or through an SSM/bastion tunnel, and add that
runner's CIDR to `admin_runner_cidrs`.

## DB bootstrap (6 logical DBs, one owner role each)
Default (keeps role passwords out of TF state): a one-off k8s Job in namespace `ecom` running `postgres:16`
with the RDS master secret (`rds_master_user_secret_arn`, synced by an ExternalSecret; the ESO role is granted read on it) and the six
`ecom/<env>/<svc>/db` secrets as env, executing for each service:
```sql
CREATE ROLE "<svc>" LOGIN PASSWORD '<from secret>';
GRANT "<svc>" TO CURRENT_USER;               -- RDS master is not a superuser
CREATE DATABASE <svc>db OWNER "<svc>";
REVOKE CONNECT ON DATABASE <svc>db FROM PUBLIC;
```
(same SQL as `infra/docker-compose/config/postgres-init.sql`). Alternative: `enable_db_bootstrap = true`
runs `modules/db-bootstrap` (cyrilgdn/postgresql); it reads the service secrets, so role passwords are
stored in the (encrypted) state.

## What the services / k8s manifests must honor
- `KAFKA_BOOTSTRAP_SERVERS` = `msk_bootstrap_brokers_sasl_iam` (port 9098) plus SASL/IAM client config:
  `SPRING_KAFKA_SECURITY_PROTOCOL=SASL_SSL`, `SPRING_KAFKA_PROPERTIES_SASL_MECHANISM=AWS_MSK_IAM`,
  `SPRING_KAFKA_PROPERTIES_SASL_JAAS_CONFIG=software.amazon.msk.auth.iam.IAMLoginModule required;`,
  `SPRING_KAFKA_PROPERTIES_SASL_CLIENT_CALLBACK_HANDLER_CLASS=software.amazon.msk.auth.iam.IAMClientCallbackHandler`
  — requires `software.amazon.msk:aws-msk-iam-auth` on the service classpath.
- IAM topic grants cover `ecom.*` (incl. `.dlt`); consumer groups must start with `<svc>-service-`.
- Redis: TLS + AUTH → `SPRING_DATA_REDIS_SSL_ENABLED=true`, `SPRING_DATA_REDIS_PASSWORD` from `ecom/<env>/redis`.
- RDS enforces TLS (`rds.force_ssl=1`): `DB_URL=jdbc:postgresql://<rds_address>:5432/<svc>db?sslmode=require`.
- Elasticsearch: `ELASTICSEARCH_URL` = `elasticsearch_url` output (HTTPS 443 over PrivateLink) and
  `SPRING_ELASTICSEARCH_API_KEY` from `ecom/<env>/search/elasticsearch` (key `api_key`).
- ECR tags are IMMUTABLE: push unique tags (git SHA), never re-push `latest`.

## Migrating an existing env from Amazon OpenSearch to Elastic Cloud
The `products` index is derived from Kafka events, so nothing is copied from the old domain. Applying this
config creates the Elastic Cloud deployment and removes the old OpenSearch domain in the same run. To avoid
search downtime, apply in two steps (for example, apply with `-target=module.elastic_cloud` first).
1. Create the Elastic Cloud deployment + PrivateLink, then create the API key and store it (see step 4 above).
2. Deploy the search-service with `ELASTICSEARCH_URL` set. It creates the index on startup.
3. Backfill the index: with the search-service scaled to 0, reset `search-service-catalog-indexer-cg` and
   `search-service-inventory-indexer-cg` to `--to-earliest`. This works if topic retention covers the whole
   catalog; otherwise have the catalog re-publish its products. Then scale the service back up.
4. Run a full `terraform apply` to destroy the old OpenSearch domain and its IAM policy.
