# ADR 0002: Versioned JSON envelope instead of Avro + Schema Registry

- Status: Accepted (2026-09-29)
- Context: PLAN.md §2, §5

## Context
Kafka events need a stable contract between 8 services. They are owned by one team and live in one monorepo. Avro + Schema Registry gives enforced compatibility, but it adds a stateful, HA-critical component in both Compose and EKS, and it needs compatibility-mode governance.

## Decision
Events are JSON wrapped in `EventEnvelope` (`eventId`, `eventType`, `eventVersion`, `occurredAt`, `correlationId`, `producerService`, `payload`), defined once in `ecom-common-events`. The event type is the topic name (`ecom.<domain>.<event>.v1`). Evolution is **additive-only**: consumers ignore unknown fields (`fail-on-unknown-properties: false`), and a breaking change gets a new `.v2` topic. The topic list lives in one YAML file (`ecom-common-events/src/main/resources/kafka-topics.yaml`), read by the starter (local topic creation), by Terraform (MSK topics + `.dlt`) and by a drift test against `EventTopics`.

## Consequences
- No registry to run, and events are human-readable in Redpanda Console and DLT tooling.
- Compatibility is enforced by review and consumer-side contract tests (current + previous `eventVersion`), not by the broker. Payloads are larger than Avro's.
- Upgrade path: introduce a registry and Avro/Protobuf serdes per topic once more teams or external consumers appear. The envelope already carries `eventVersion` to bridge the migration.
