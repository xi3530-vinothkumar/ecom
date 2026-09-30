# ADR 0005: One Postgres instance hosting six logical databases

- Status: Accepted (2026-09-29)
- Context: PLAN.md §6a, §7; infra/terraform/modules/rds, infra/docker-compose/config/postgres-init.sql

## Context
Six services need relational storage: user, catalog, inventory, order, payment and notification. Six RDS instances (Multi-AZ in prod) would cost about 6× for MVP load and add six sets of backups, patch windows and alarms. The data-ownership rule (no service reads another's tables) must still hold.

## Decision
- **One RDS PostgreSQL 16 instance per environment** (Multi-AZ in prod) with six databases (`userdb` … `notificationdb`). Each database is owned by its own login role (`user`, `catalog`, …), and `CONNECT` is revoked from `PUBLIC`. No role can reach a peer's database.
- Credentials are per service in Secrets Manager (`ecom/<env>/<svc>/db`), synced by ExternalSecrets into that service's pod only.
- Compose mirrors this with one `postgres:16` container and `postgres-init.sql`.
- Every service keeps its own Flyway history. Migrations are expand/contract only.

## Consequences
- Lower cost and a single thing to operate, backed by real isolation at the credential and database level.
- Shared blast radius: noisy neighbours, a shared `max_connections` budget (HikariCP pools must be sized so that the sum across replicas × services stays under the limit), and a shared maintenance window. Performance Insights per database user shows who is responsible.
- Moving one service onto its own instance later means dump/restore, then changing only its `DB_URL`/secret. No code change.
