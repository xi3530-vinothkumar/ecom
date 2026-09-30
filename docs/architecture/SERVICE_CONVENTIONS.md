# Service Conventions (binding for every module)

Source of truth for design: `docs/architecture/PLAN.md` (rev 2). This file = how to build it consistently.

## Stack & versions (from parent POM — never override)
Java 21, Spring Boot 3.4.1, Spring Cloud 2024.0.0, grpc-java 1.70 (plain grpc-java, **no** spring-grpc / grpc-spring-boot-starter),
protobuf 3.25.5, Resilience4j 2.2, Testcontainers 1.20.4, Flyway (Boot-managed), PostgreSQL 16, Redis 7, Kafka 3.8 (KRaft), Elasticsearch 9.5 (elasticsearch-java 9.5.4).

## Foundation modules (DONE, installed in ~/.m2 — do NOT modify; if you need a change, report it)
- `ecom-common-proto` — gRPC contracts under `ecom-common-proto/src/main/proto/ecom/**/v1/*.proto`. Java pkgs `com.ecom.<domain>.proto.v1`, Money = `com.ecom.common.proto.v1.Money`.
- `ecom-common-events` — `EventEnvelope`, `EventTopics` (constants; event type == topic name), payload records in `com.ecom.common.events.payload`, `Money` (`com.ecom.common.events.Money`: currencyCode/units/nanos, `of(BigDecimal|String, ccy)`, `toBigDecimal()`, `toMinorUnits()`, `plus`, `times`, `timesRate`), `kafka-topics.yaml` (canonical topic list).
- `ecom-platform-starter` — one dependency, auto-configures by classpath:
  - `com.ecom.platform.core`: `EcomException` (+ factories `notFound/conflict/validation/unprocessable/forbidden/unauthenticated/unavailable`), `ErrorCode` (HTTP + gRPC mapping), `CallContext` (`requireUserId()`, `requireRole(CallContext.ROLE_ADMIN)`, `current()`), `MoneyMapper` (proto <-> events Money), `CorrelationIds`.
  - `web` (servlet apps): `CallContextFilter` reads Gateway-stamped `X-User-Id`, `X-User-Roles`, `X-Correlation-Id`; `ProblemDetailsExceptionHandler` maps `EcomException` -> problem+json. Don't write your own advice for these.
  - `grpc`: every `BindableService` bean (`@Component class X extends FooServiceGrpc.FooServiceImplBase`) is served on `ecom.grpc.server.port` with context propagation + `EcomException` -> gRPC status. Clients: `GrpcChannels.channel("<name>")` (address from `ecom.grpc.clients.<name>.address`) and wrap calls in `ResilientCalls.call("<name>", () -> stub.x(req))` (retry+CB, failures become `EcomException`). Disable server with `ecom.grpc.server.enabled=false` if a service exposes no gRPC.
  - `kafka`: `OutboxWriter.publish(topic, key, payload)` — **the only way to publish**, must be inside `@Transactional`. `OutboxRelay` sends rows (enabled automatically). `IdempotentConsumer.handle(record, consumerGroup, env -> ...)` — **every** `@KafkaListener` routes through it; it opens the transaction, dedups on `(eventId, consumerGroup)`, and anything you write (incl. `OutboxWriter.publish`) commits atomically. Error handler: retries 1s/5s/15s then `<topic>.dlt`. The starter's Flyway migration `db/platform/V0_1__...sql` creates `outbox` + `processed_events` (Flyway locations default to `classpath:db/migration,classpath:db/platform`; your migrations start at `V1__`).
  - `security`: `JwtIssuer` (RS256, JWKS) — User service only. `JwtProperties` under `ecom.jwt.*`.
  - defaults (lowest precedence, `ecom-platform-defaults.yml`): graceful shutdown, `ddl-auto=validate`, `open-in-view=false`, Kafka String serdes/acks=all/idempotence, actuator `health,info,prometheus` + k8s probes, OTLP tracing to `${OTEL_EXPORTER_OTLP_ENDPOINT:http://localhost:4318}`, JSON logs (profile `plain-logs` for human-readable).
- `ecom-common-testing` (test scope): `Containers.registerPostgres(r) / registerKafka(r) / registerRedis(r) / registerElasticsearch(r)` in `@DynamicPropertySource` (JVM-singleton containers; `registerKafka` also sets `ecom.kafka.create-topics=true`). `KafkaTestSupport.sendPayload(...)` / `await(...)`.

## Module layout
```
ecom-<svc>-service/
  pom.xml
  src/main/java/com/ecom/<svc>/
    <Svc>ServiceApplication.java      # @SpringBootApplication (+ @EnableScheduling if needed)
    web/            REST controllers + request/response DTOs (records, jakarta.validation)
    grpc/           *ImplBase implementations (thin: map proto <-> application)
    application/    use-case services, @Transactional boundaries, event handlers
    domain/         entities / value objects / state machines (never shared across services)
    infrastructure/ JPA repositories, Kafka listeners, outbound gRPC clients, external adapters
  src/main/resources/application.yml
  src/main/resources/db/migration/V1__init.sql
  src/test/java/...  *Test = unit (no Spring, no Docker), *IT = integration (Testcontainers, failsafe)
```
`<svc>` ∈ user, catalog, inventory, cart, order, payment, notification, search. Gateway: `com.ecom.gateway`.

## POM template (service)
```xml
<parent><groupId>com.ecom</groupId><artifactId>ecom-parent</artifactId><version>0.1.0-SNAPSHOT</version></parent>
<artifactId>ecom-<svc>-service</artifactId>
<dependencies>
  <dependency><groupId>com.ecom</groupId><artifactId>ecom-platform-starter</artifactId></dependency>
  <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-web</artifactId></dependency>
  <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-validation</artifactId></dependency>
  <!-- Postgres services -->
  <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-data-jpa</artifactId></dependency>
  <dependency><groupId>org.flywaydb</groupId><artifactId>flyway-database-postgresql</artifactId></dependency>
  <dependency><groupId>org.postgresql</groupId><artifactId>postgresql</artifactId><scope>runtime</scope></dependency>
  <!-- Kafka -->
  <dependency><groupId>org.springframework.kafka</groupId><artifactId>spring-kafka</artifactId></dependency>
  <!-- gRPC server and/or client -->
  <dependency><groupId>io.grpc</groupId><artifactId>grpc-netty-shaded</artifactId></dependency>
  <dependency><groupId>io.grpc</groupId><artifactId>grpc-services</artifactId></dependency>
  <dependency><groupId>io.github.resilience4j</groupId><artifactId>resilience4j-circuitbreaker</artifactId></dependency>
  <dependency><groupId>io.github.resilience4j</groupId><artifactId>resilience4j-retry</artifactId></dependency>
  <!-- tests -->
  <dependency><groupId>com.ecom</groupId><artifactId>ecom-common-testing</artifactId><scope>test</scope></dependency>
  <dependency><groupId>io.grpc</groupId><artifactId>grpc-inprocess</artifactId><scope>test</scope></dependency>
</dependencies>
<build><plugins><plugin><groupId>org.springframework.boot</groupId><artifactId>spring-boot-maven-plugin</artifactId></plugin></plugins></build>
```
Note: `flyway-database-postgresql` pulls flyway-core. `ecom-common-testing` brings spring-boot-starter-test + Testcontainers (postgresql, kafka).

## Ports & names
| Service | k8s/Compose DNS | HTTP (local default) | gRPC (local default) | DB / store |
|---|---|---|---|---|
| gateway | gateway | 8080 | – | Redis (rate limit) |
| user | user-service | 8081 | 9091 | Postgres `userdb` |
| catalog | catalog-service | 8082 | 9092 | Postgres `catalogdb` |
| inventory | inventory-service | 8083 | 9093 | Postgres `inventorydb` |
| cart | cart-service | 8084 | 9094 | Redis |
| order | order-service | 8085 | 9095 | Postgres `orderdb` |
| payment | payment-service | 8086 | 9096 | Postgres `paymentdb` |
| notification | notification-service | 8087 | 9097 (disabled) | Postgres `notificationdb` |
| search | search-service | 8088 | 9098 (disabled) | Elasticsearch |

In containers every service uses HTTP 8080 / gRPC 9090 (set via `HTTP_PORT` / `GRPC_PORT` env).

## application.yml template
```yaml
spring:
  application:
    name: <svc>-service
  datasource:
    url: ${DB_URL:jdbc:postgresql://localhost:5432/<svc>db}
    username: ${DB_USERNAME:<svc>}
    password: ${DB_PASSWORD:<svc>}
server:
  port: ${HTTP_PORT:808X}
ecom:
  grpc:
    server:
      port: ${GRPC_PORT:909X}
    clients:
      catalog:
        address: ${CATALOG_GRPC_ADDRESS:localhost:9092}
  kafka:
    create-topics: ${KAFKA_CREATE_TOPICS:false}   # true only in Compose/tests
```
Env var names used by infra (must be honored exactly): `HTTP_PORT, GRPC_PORT, DB_URL, DB_USERNAME, DB_PASSWORD, KAFKA_BOOTSTRAP_SERVERS, KAFKA_CREATE_TOPICS, REDIS_HOST, REDIS_PORT, ELASTICSEARCH_URL, ELASTICSEARCH_API_KEY, OTEL_EXPORTER_OTLP_ENDPOINT, <PEER>_GRPC_ADDRESS` (USER/CATALOG/INVENTORY/CART/ORDER/PAYMENT), plus service-specific ones documented in that service's README section. Redis: `spring.data.redis.host: ${REDIS_HOST:localhost}`, `port: ${REDIS_PORT:6379}`. Elasticsearch: `ecom.elasticsearch.url: ${ELASTICSEARCH_URL:http://localhost:9200}`, `ecom.elasticsearch.api-key: ${ELASTICSEARCH_API_KEY:}` (blank = no auth).

## Public REST surface (Gateway routes by path prefix, path forwarded unchanged)
| Prefix | Service | Auth |
|---|---|---|
| `POST /auth/login`, `POST /auth/refresh`, `POST /auth/logout`, `POST /users` (register) | user | public |
| `/users/me/**` | user | JWT |
| `/.well-known/jwks.json` | user | internal only (Gateway fetches it via service DNS; not routed publicly) |
| `GET /products/**`, `GET /categories/**` | catalog | public |
| `/admin/products/**`, `/admin/categories/**` | catalog | JWT + ADMIN |
| `/admin/inventory/**` | inventory | JWT + ADMIN |
| `/cart/**` | cart | public (guest via `X-Guest-Cart-Id` header) or JWT |
| `/orders/**` | order | JWT |
| `POST /webhooks/psp` | payment | public, PSP signature-verified in Payment |
| `/dev/psp/**` | payment | only when `ecom.payment.psp=fake` (local/e2e) |
| `GET /search/**` | search | public |

## Persistence rules
- UUID primary keys generated in application code (`UUID.randomUUID()`), stored as `uuid`.
- Money in DB: `amount NUMERIC(19,4)` + `currency CHAR(3)`; in Java `com.ecom.common.events.Money`.
- Optimistic `version BIGINT` column on aggregates that are updated concurrently; guarded state transitions via
  `@Modifying @Query("update ... set status = :to where id = :id and status = :from")` returning affected rows.
- Timestamps `TIMESTAMPTZ`, Java `Instant`.
- Migrations are **expand/contract** only (see PLAN §6).

## Messaging rules
- Consumer group id: `<svc>-service-<purpose>-cg`, e.g. `inventory-service-order-events-cg`. Pass the same string to `IdempotentConsumer.handle`.
- Listener signature: `@KafkaListener(topics = EventTopics.X, groupId = "...") void on(ConsumerRecord<String, String> rec)`.
- Bind payload: `env.payloadAs(mapper, XPayload.class)` (mapper = `IdempotentConsumer.mapper()` or injected ObjectMapper).
- Key = aggregate id (orderId / sku / productId / userId).
- Never call `KafkaTemplate` directly for domain events.
- No PII in payloads.

## Business constants (MVP)
Currency `EUR` everywhere. VAT 21% on line subtotal (`ecom.order.vat-rate: 0.21`). Flat shipping `4.95 EUR`. Reservation TTL 15 min, sweep every 60 s. Access token 15 min, refresh token 14 days.

## PSP (Payment)
`PspClient` interface; `ecom.payment.psp=fake|stripe`. `fake` is deterministic by payment-method token:
`pm_card_visa` -> authorized, `pm_card_chargeDeclined` -> declined, `pm_card_threeDSecure2Required` -> requires_action
(completed via `POST /dev/psp/complete-action/{pspReference}?outcome=succeeded|failed`, which emits a signed webhook to Payment itself).
Webhook signature = Stripe scheme: header `Stripe-Signature: t=<unix>,v1=<hex HMAC-SHA256(secret, t + "." + rawBody)>`, 5-min tolerance.

## Testing & build
- Docker for Testcontainers on this machine (Rancher Desktop):
  `export DOCKER_HOST=unix://$HOME/.rd/docker.sock TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock`
- Build only your module(s): `mvn -q -pl ecom-<svc>-service verify` (NOT `-am`; foundation is installed). Filter output: `2>&1 | grep -E "Tests run:|FAIL|ERROR" | cut -c1-300 | head -30`.
- Prefer ONE `@SpringBootTest` context per module shared by IT classes (same config => cached context).
- Unit-test domain logic without Spring; IT the real DB/Kafka/Redis paths (row locking, guarded updates, outbox emission).

## Service-specific env vars (contract between service code and infra — honor exactly)
| Service | Env vars (default in parentheses) |
|---|---|
| gateway | `USER_SERVICE_URI` (http://localhost:8081), `CATALOG_SERVICE_URI` (:8082), `INVENTORY_SERVICE_URI` (:8083), `CART_SERVICE_URI` (:8084), `ORDER_SERVICE_URI` (:8085), `PAYMENT_SERVICE_URI` (:8086), `SEARCH_SERVICE_URI` (:8088), `USER_JWKS_URI` (http://localhost:8081/.well-known/jwks.json), `JWT_ISSUER` (ecom-user-service), `JWT_AUDIENCE` (ecom), `REDIS_HOST`, `REDIS_PORT`, `CORS_ALLOWED_ORIGINS` (http://localhost:3000), `PSP_DEV_ROUTES_ENABLED` (false) |
| user | `JWT_PRIVATE_KEY_PEM` (unset), `JWT_KEY_ID` (local-dev), `JWT_EPHEMERAL_KEY` (false; true => generate RSA key at startup, local/Compose only), `ADMIN_EMAIL` / `ADMIN_PASSWORD` (unset => no bootstrap admin), `CART_GRPC_ADDRESS` |
| catalog | `IMAGES_ENABLED` (false), `IMAGES_BUCKET`, `AWS_REGION` (eu-west-1), `IMAGES_CDN_BASE_URL` |
| inventory | `RESERVATION_TTL` (15m), `RESERVATION_SWEEP_INTERVAL` (60s) |
| cart | `CATALOG_GRPC_ADDRESS`, `REDIS_HOST`, `REDIS_PORT` |
| order | `CART_GRPC_ADDRESS`, `CATALOG_GRPC_ADDRESS`, `USER_GRPC_ADDRESS`, `PAYMENT_GRPC_ADDRESS` |
| payment | `PSP_MODE` (fake), `PSP_API_KEY`, `PSP_BASE_URL` (https://api.stripe.com), `PSP_WEBHOOK_SECRET` (whsec_local_dev_secret), `ORDER_GRPC_ADDRESS`, `INVENTORY_GRPC_ADDRESS` |
| notification | `USER_GRPC_ADDRESS`, `NOTIFICATION_CHANNEL` (log\|smtp), `SMTP_HOST`, `SMTP_PORT`, `MAIL_FROM` (no-reply@ecom.local) |
| search | `ELASTICSEARCH_URL`, `ELASTICSEARCH_API_KEY` |

## Cross-service interactions (who calls whom)
- gRPC: cart→catalog; order→cart, catalog, user, payment; payment→order, inventory; notification→user; user→cart (MergeCart at login when the login request carries `guestCartId`).
- Kafka: see PLAN §2 event catalog (rev 2).
