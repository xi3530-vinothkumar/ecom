# ecom — e-commerce microservices platform

Java 25 / Spring Boot 4.1 microservices for an e-commerce platform. The MVP covers catalog, search, cart, checkout, payment and accounts.
Service-to-service calls use gRPC, async events go over Kafka through a transactional outbox, and the edge is Spring Cloud Gateway with RS256 JWTs.
Local development runs on Docker Compose, production targets AWS EKS (Terraform + Kustomize), and CI/CD runs on GitHub Actions.

- Design: [`docs/architecture/PLAN.md`](docs/architecture/PLAN.md) (rev 2) · ADRs: [`docs/architecture/adr/`](docs/architecture/adr)
- How every module is built: [`docs/architecture/SERVICE_CONVENTIONS.md`](docs/architecture/SERVICE_CONVENTIONS.md) (ports, env vars, routes, rules)

## Modules
| Module | Purpose |
|---|---|
| `ecom-common-proto` | gRPC contracts (`ecom/<domain>/v1/*.proto`) |
| `ecom-common-events` | Kafka `EventEnvelope`, payloads, `Money`, canonical `kafka-topics.yaml` |
| `ecom-platform-starter` | Auto-configured platform: outbox relay, idempotent consumer + DLT, Spring gRPC server/clients with context propagation + Spring Cloud CircuitBreaker, trusted-header web filter, problem+json, RS256 JWT issuer, Spring Boot structured JSON logs with PII masking, virtual threads, actuator/OTel defaults |
| `ecom-common-testing` | Testcontainers singletons (Postgres, Kafka, Redis, Elasticsearch) + Kafka helpers |
| `ecom-gateway` | Edge: routing allow-list, header sanitizing, JWKS validation, claims propagation, Redis rate limiting |
| `ecom-user-service` | Accounts, addresses, login/refresh-rotation/logout, JWKS |
| `ecom-catalog-service` | Products, variants, categories, prices, S3 image upload URLs |
| `ecom-inventory-service` | Stock + reservation lifecycle (`RESERVED→EXPIRING→COMMITTED/RELEASED`) |
| `ecom-cart-service` | Redis carts (guest + user), merge on login |
| `ecom-order-service` | Checkout (idempotent), saga decision-maker |
| `ecom-payment-service` | PSP authorize/3DS/void/capture, signed webhooks (fake PSP for dev) |
| `ecom-notification-service` | Templated notifications (contact info fetched at send time) |
| `ecom-search-service` | Elasticsearch index fed by catalog + stock events (versioned upserts) |
| `ecom-e2e-tests` | Full-saga end-to-end suite (profile `e2e`) |
| `infra/` | `docker-compose/`, `k8s/` (Kustomize base + overlays), `terraform/` (AWS) |

## Build & test
```bash
# Docker for Testcontainers (Rancher Desktop on this machine; Docker Desktop needs nothing)
export DOCKER_HOST=unix://$HOME/.rd/docker.sock TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock

mvn verify                      # all modules: unit tests + Testcontainers integration tests (*IT)
mvn -pl ecom-order-service verify   # one module (after `mvn install -DskipTests` once)
mvn -DskipTests package && mvn -pl ecom-e2e-tests verify -Pe2e   # end-to-end saga suite
mvn verify -Psecurity-scan      # + OWASP Dependency-Check (set NVD_API_KEY)
```

## Run locally
```bash
scripts/build-images.sh                                   # spring-boot:build-image for all services
cp infra/docker-compose/.env.example infra/docker-compose/.env
docker compose -f infra/docker-compose/docker-compose.yml --profile core --profile services up -d
# add --profile observability for Grafana (3000) / Prometheus / Tempo / Loki / OTel Collector
scripts/smoke-test.sh                                     # golden path through the gateway (localhost:8080)
```
Fake PSP tokens: `pm_card_visa` (authorized), `pm_card_chargeDeclined`, `pm_card_threeDSecure2Required`
(complete via `POST /dev/psp/complete-action/{pspReference}?outcome=succeeded`).

## Deploy
Terraform (`infra/terraform/envs/<env>`) provisions VPC, EKS, RDS, ElastiCache, MSK, topics, Elasticsearch (Elastic Cloud over PrivateLink), ECR, S3, IRSA and Secrets Manager.
Workflows: `ci.yml` (changed modules + dependents), `docker-publish.yml` (build-image → Trivy → ECR),
`deploy.yml` (staging auto, production behind manual approval), `infra.yml` (plan on PR, apply on main).
Placeholders to fill before a first deploy (account IDs, CIDRs, ARNs, hostnames) are listed in `infra/terraform/README.md`.
