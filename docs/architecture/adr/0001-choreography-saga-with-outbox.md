# ADR 0001: Choreographed order saga with a transactional outbox

- Status: Accepted (2026-09-29)
- Context: PLAN.md §3, §12

## Context
Checkout spans Order, Inventory and Payment, and each one owns its own database. We need an outcome that is eventually consistent, can be compensated, and survives crashes and redelivery. The MVP flow is linear, with three compensation branches (out of stock, payment declined, reservation expiry).

## Decision
- **Choreography over Kafka**, with no central orchestrator. Order is the **single decision-maker**: only Order moves an order to `CONFIRMED` or `CANCELLED`, and it does so with guarded updates (`... WHERE status='PENDING'`). Inventory never releases stock and Payment never voids unless Order has published the matching decision.
- **Transactional outbox on every producer.** The business row and the `outbox` row are written in one local transaction. `OutboxRelay` (in `ecom-platform-starter`, `FOR UPDATE SKIP LOCKED`) publishes with key = aggregate id. Nothing calls `KafkaTemplate` directly.
- **Idempotent consumers.** `processed_events(event_id, consumer_group)` is checked in the same transaction as the handler's writes and outbox emits. Bounded retries (1s/5s/15s) are followed by `<topic>.dlt`.
- Reservation expiry **asks rather than acts**. The sweep marks a reservation `EXPIRING` and emits `ReservationExpired`, then Order decides.

## Consequences
- Adding the flow needs no extra service or failure domain, and every step is testable in isolation plus `ecom-e2e-tests`.
- The flow is implicit, spread across consumers. We mitigate this with `order_saga_state` (visibility only) and alerts: `OutboxBacklog`, `StalePendingOrders` (>30m), `StaleExpiringReservations` (>1h) and `DeadLetterTraffic`. A human or a DLT replay resolves stuck items, and nothing auto-releases.
- Delivery is at-least-once. The outbox adds DB write amplification and a polling delay of about the poll interval.
- We revisit this (a dedicated orchestrator) once fulfilment/shipping adds branches. Debezium CDC can replace the poller without changing service code.
