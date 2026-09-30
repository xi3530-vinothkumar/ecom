# ecom-e2e-tests

End-to-end verification of the order saga (PLAN §3, §11 scenarios 1-7 + concurrency). This is the only
place the choreography is tested as a whole, against the **real service fat jars**.

## How it works
- One JVM-wide system (`support/EcomSystem`, started by `EcomSystemExtension`, shared by every `*IT` class):
  - Testcontainers: one Postgres (databases `userdb`, `catalogdb`, `inventorydb`, `orderdb`, `paymentdb`,
    `notificationdb` created over JDBC), Kafka (KRaft), Redis — the `ecom-common-testing` singletons.
  - Each service launched as a child process `java -jar ecom-<svc>-service/target/ecom-<svc>-service-0.1.0-SNAPSHOT.jar`
    (user, catalog, inventory, cart, order, payment, notification) on random free HTTP/gRPC ports, with the
    env vars from `SERVICE_CONVENTIONS.md`: `DB_URL`, `KAFKA_BOOTSTRAP_SERVERS`, `KAFKA_CREATE_TOPICS=true`,
    `REDIS_HOST/PORT`, `<PEER>_GRPC_ADDRESS`, `PSP_MODE=fake`, `JWT_EPHEMERAL_KEY=true`,
    `RESERVATION_TTL=20s`, `RESERVATION_SWEEP_INTERVAL=2s`, `TRACING_ENABLED=false`, `SPRING_PROFILES_ACTIVE=plain-logs`.
  - Startup waits for `/actuator/health/readiness` on every service; processes are stopped after the last
    test class (and by a JVM shutdown hook).
- Tests call the services' REST APIs directly with Gateway-style headers (`X-User-Id`, `X-User-Roles`,
  `X-Correlation-Id`); the Gateway has its own tests.
- `support/EventLog` taps every saga topic, so tests count events per order and re-publish captured records
  verbatim (same `eventId`) for the duplicate-delivery scenario.
- `support/Db` does read-only queries for state no API exposes (reservation rows, payment transaction status,
  notification log).

| Class | Scenarios |
|---|---|
| `HappyPathIT` | 1 — CONFIRMED, reservation COMMITTED, on_hand decremented, cart lines removed, payment CAPTURED, one notification |
| `CompensationIT` | 2 — OUT_OF_STOCK, no payment/reservation · 3 — PAYMENT_DECLINED, reservation RELEASED |
| `ThreeDSecureIT` | 4 — 3DS past TTL -> PAYMENT_TIMEOUT, stock released, late completion -> VOIDED · 5 — 3DS in time -> CONFIRMED · 5 race — completion at expiry ±offsets converges |
| `DuplicateDeliveryIT` | 6 — replay order-created / inventory-reserved / payment-authorized / order-confirmed (same eventId) is a no-op; order-created with a new eventId does not reserve twice |
| `IdempotentCheckoutIT` | 7 — same `Idempotency-Key` (sequential and concurrent) -> one order; different body -> 422 |
| `ConcurrencyIT` | N shoppers racing for the last M units -> exactly M CONFIRMED, stock never negative |

Every scenario creates its own user and SKUs, so classes and methods are independent of each other.

## Run
Needs Docker (Rancher Desktop on this machine):
```bash
export DOCKER_HOST=unix://$HOME/.rd/docker.sock TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock
mvn -DskipTests package                 # build the service fat jars first (repo root)
mvn -pl ecom-e2e-tests verify -Pe2e     # ~2 min: ~25 s startup + scenarios
mvn -pl ecom-e2e-tests verify -Pe2e -Dit.test=ThreeDSecureIT   # one class
```
Plain `mvn verify` compiles this module but skips the ITs (`skipITs=true` unless `-Pe2e`). If a jar is
missing the suite fails fast telling you to run `mvn -DskipTests package`.

Service logs: `ecom-e2e-tests/target/e2e-logs/<svc>.log` (on a startup failure the log tail is also in the
test failure message).
