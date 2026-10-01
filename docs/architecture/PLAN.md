# Ecom Microservices MVP — Build Plan

## Context

`/Users/vinothkumar/IdeaProjects/ecom` is currently empty (no code, not a git repo). The user wants a **production-grade e-commerce platform** built from scratch as a Java/Spring Boot microservices system, covering the mandatory MVP feature set (browse catalog, cart, checkout, payment, user accounts) with the operational rigor (observability, resilience, security, CI/CD, K8s deployment) expected of a real production system — not a toy demo. Target deploy is **AWS EKS** for production plus **Docker Compose** for local dev.

This is a **standalone new project**, independent of the 150+ Marktplaats service repos elsewhere in `/Users/vinothkumar/IdeaProjects` — it borrows proven architectural *patterns* from Marktplaats reference docs (layered services, circuit breakers, multi-tier caching, choreographed sagas, correlation-ID propagation) but has its own domain, its own monorepo, and no dependency on Marktplaats internal libraries.

Confirmed decisions from the user: 8 services (User, Catalog, Inventory, Cart, Order, Payment, Notification, Search), single monorepo with multi-module Maven, gRPC for service-to-service sync calls, Kafka for async events, Spring Cloud Gateway + JWT at the edge, Kubernetes-native discovery/config (no Eureka, since target is EKS), and an OpenTelemetry-based observability pipeline that can fan out to Prometheus/Grafana/Tempo/Loki and/or Datadog without re-instrumenting code.

Two design passes (domain/service layer and infra/platform layer) were run in parallel and are synthesized below. **Two conflicts between them were resolved during synthesis** (called out explicitly where relevant): (1) Kafka schema strategy — went with the simpler JSON+versioned-envelope approach, not Avro+Schema Registry, to avoid an extra stateful platform component for an MVP; (2) Gateway↔service protocol — the Gateway stays a pure HTTP reverse proxy (no gRPC-Web/transcoding at the edge); every service exposes a thin REST façade for client-facing operations, and gRPC is used strictly service-to-service.

**Revision 2 (post-review, 2026-09-29):** closed saga correctness gaps and added the missing platform pieces:
- Transactional outbox on every producer (§3).
- Order as the single decision-maker; Inventory commits reservations on `OrderConfirmed`; the expiry sweep no longer releases stock blindly, and late authorizations are voided (§3).
- Payment gets the amount via `OrderService.GetOrder`; 3DS/webhook flow defined (§3, §5).
- `Idempotency-Key` on `POST /orders`; authoritative re-pricing at checkout; cart clearing (§3, §4).
- RS256 + JWKS, rotating refresh tokens, and Gateway header sanitizing (§5).
- `Money` type; PII kept out of events (§2).
- Terraform for AWS data infra and topic provisioning (§6a, §2).
- Six `ecom-common-*` platform modules collapsed into `ecom-platform-starter` (§1).
- One Postgres container locally / one RDS instance with 6 databases (§6a, §7).
- Versioned upserts in Search (§4); expand/contract migrations (§6).

---

## 1. Monorepo Layout

```
ecom/
├── pom.xml                          # parent POM: BOM imports, plugin mgmt, Java 25
├── .gitignore
├── .github/workflows/                # CI (ci.yml, docker-publish.yml, deploy-staging.yml)
├── docs/architecture/                 # ADRs, C4 diagrams
├── ecom-common-proto/                 # all .proto files + generated stubs (incl. shared ecom/common/v1/money.proto)
├── ecom-common-events/                # Kafka envelope class + event payload POJOs (JSON, versioned)
├── ecom-platform-starter/             # ONE Spring Boot autoconfig starter, internal packages:
│                                      #   core (exceptions, MDC/correlation-id, pagination, Money)
│                                      #   web (problem+json, correlation-id filter, trusted-header reader)
│                                      #   grpc (interceptors, Resilience4j channel builder)
│                                      #   kafka (outbox relay, idempotent consumer, DLT)
│                                      #   security (JWT claims model, JWKS validation helpers)
│                                      #   observability (Micrometer/OTel, logback-spring.xml, health groups)
├── ecom-common-testing/               # Testcontainers base classes (Postgres, Kafka, Redis, Elasticsearch)
├── ecom-e2e-tests/                    # saga end-to-end suite (see §11)
├── ecom-gateway/                      # Spring Cloud Gateway (edge)
├── ecom-user-service/
├── ecom-catalog-service/
├── ecom-inventory-service/
├── ecom-cart-service/
├── ecom-order-service/
├── ecom-payment-service/
├── ecom-notification-service/
├── ecom-search-service/
└── infra/
    ├── docker-compose/
    │   ├── docker-compose.yml         # profiles: "core" (db/redis/kafka only) vs default (everything incl. services+observability)
    │   ├── .env.example
    │   ├── config/otel-collector-config.yaml
    │   └── config/postgres-init.sql   # creates the 6 service databases + per-service users in ONE Postgres container
    ├── terraform/                     # AWS data/platform infra (see §6a)
    │   ├── modules/{vpc,eks,rds,elasticache,msk,elastic-cloud,ecr,iam-irsa,kafka-topics}/
    │   └── envs/{dev,staging,prod}/
    └── k8s/
        ├── base/<service>/            # Deployment, Service, ConfigMap, HPA, ExternalSecret per service (kustomize)
        ├── base/platform/              # otel-collector, namespace, network policies
        └── overlays/{dev,staging,prod}/
```

Each service module uses a lightweight layout: `web` (REST controllers, client-facing), `grpc` (gRPC server impl, for peer calls), `application` (service layer), `domain` (entities, private — never shared), `infrastructure` (JPA repos, Kafka producers/consumers, outbound gRPC clients), `bootstrap` (Spring Boot main).

**Why one platform starter instead of six `ecom-common-*` modules:** core/web/grpc/kafka/security/observability always version and ship together, so splitting them adds POM churn and makes the dependency graph harder to follow for no benefit. Each concern is its own package inside the starter, with its own `@AutoConfiguration` guarded by `@ConditionalOnClass` (a service without Kafka on its classpath gets no Kafka beans). Proto, events and testing stay separate because they have different consumers (e.g. only tests depend on testing).

Parent POM centralizes Spring Boot/Spring Cloud/gRPC/Micrometer/Testcontainers BOMs, Java 25, and shared plugin config (`protobuf-maven-plugin`, `spring-boot:build-image` via Cloud Native Buildpacks — no hand-written Dockerfiles).

---

## 2. Domain Services

Cross-cutting rule for all services: **no service ever live-joins into another service's tables.** Peers are reached either via gRPC (real-time need) or via a locally-maintained, event-driven denormalized copy (Search indexing Catalog+Inventory events; Order snapshotting product name/price at order time). Notification does **not** snapshot contact info from events: events carry `userId` only, and Notification calls `UserService.GetUser` when it sends. This keeps email/phone (PII) out of Kafka retention, so GDPR erasure is a delete in `userdb` only.

**Money:** proto has no decimal type. All monetary fields use a shared `ecom.common.v1.Money { string currency_code; int64 units; int32 nanos; }` (same shape as google.type.Money). Events use the same fields in JSON, and Java code maps them to `BigDecimal` + `Currency`. `double`/`float` are never allowed for money. The MVP is single-currency (EUR), but currency is still carried everywhere. No shared JPA entity module, no shared "common business logic" module — only infrastructure/contract concerns are shared (see §5).

| Service | Owns | DB | Key tables/entities |
|---|---|---|---|
| **ecom-user-service** | Identity, credentials, addresses, roles, JWT issuance (`/auth/login`, `/auth/refresh`, `/auth/logout`), JWKS publication (`/.well-known/jwks.json`) | Postgres `userdb` | `users`, `addresses`, `roles`, `user_roles`, `refresh_tokens` |
| **ecom-catalog-service** | Product master data, categories, brands, variants, list price; admin CRUD (`/admin/products`, `ADMIN` role) | Postgres `catalogdb` + S3 bucket for product images (pre-signed upload URLs) | `products`, `categories`, `product_variants`, `product_images` (S3 keys only) |
| **ecom-inventory-service** | Stock levels per SKU, reservations + TTL, commit on confirm. **MVP: single warehouse** (`warehouse_id` column present, fixed to `DEFAULT`, so multi-warehouse allocation can be added without a schema break) | Postgres `inventorydb` (`SELECT...FOR UPDATE` on stock rows — concurrency-critical) | `stock_levels(sku, warehouse_id, on_hand, reserved)`, `stock_reservations(order_id, sku, qty, status, expires_at)` |
| **ecom-cart-service** | Guest + user cart contents | **Redis** (not Postgres — ephemeral, high-churn, no reporting need) | `cart:guest:{id}` / `cart:user:{userId}` hashes: sku→{qty} |
| **ecom-order-service** | Orders, order lines (snapshotted price/name), saga state (observability only), checkout idempotency | Postgres `orderdb` | `orders` (unique `(user_id, idempotency_key)`), `order_lines`, `order_saga_state` |
| **ecom-payment-service** | Payment transactions, tokenized payment methods (PCI SAQ-A — never store raw PAN), PSP webhook ingestion | Postgres `paymentdb` | `payment_transactions`, `payment_methods`, `psp_webhook_events` (dedup by PSP event id) |
| **ecom-notification-service** | Templates, send log/audit (email/SMS/push adapters) | Postgres `notificationdb` | `notification_templates`, `notification_log` |
| **ecom-search-service** | Product search/facet/typeahead index | **Elasticsearch 9** (not Postgres — full-text/facet workload; Elastic Cloud in AWS envs, since Amazon's managed service only offers OpenSearch) | `products` index doc (denormalized: catalog fields + `inStock`/`availableQty` from Inventory) |

### Client-facing API (Gateway → service, HTTP/JSON REST)
Each service exposes a thin REST controller for browser/mobile-facing operations (login, browse products, view/update cart, checkout, view order, search). This is what the Gateway proxies to (plain HTTP reverse proxy, no protocol translation).

### Service-to-service API (gRPC, internal only)
Defined in `ecom-common-proto` under `ecom/<domain>/v1/*.proto`:
- **UserService**: `GetUser` (includes contact info for Notification), `GetUserRoles(userId)` (for services that need more than the header-propagated claims; takes a user ID, never a raw JWT, in line with §5), `GetAddress`
- **CatalogService**: `GetProduct`, `GetProductsBatch` (avoids N+1 from Cart/Order), `ListCategories`
- **InventoryService**: `CheckAvailability`, `GetReservation(orderId)` (used by Payment before authorizing, see §3)
- **CartService**: `GetCart` (raw `sku→qty` lines plus optional hydration flag), `MergeCart` (guest→user merge on login)
- **OrderService**: `GetOrder(orderId)` (returns the total `Money`, user, lines, status — how Payment gets the amount, see §3)
- **PaymentService**: `GetTransaction` (query/retry tooling; the saga itself is event-driven, see §3)

Stock reservation/release are **not** gRPC: they happen only through saga events, so there is a single path that mutates reservations.

### Kafka event catalog (topic prefix `ecom.<domain>.<event>.v1`)

| Topic | Producer | Key consumers |
|---|---|---|
| `ecom.order.order-created.v1` | Order | Inventory |
| `ecom.inventory.inventory-reserved.v1` | Inventory | Order, Payment |
| `ecom.inventory.inventory-reservation-failed.v1` | Inventory | Order |
| `ecom.payment.payment-authorized.v1` | Payment | Order |
| `ecom.payment.payment-failed.v1` | Payment | Order |
| `ecom.payment.payment-voided.v1` | Payment | Order (audit only) |
| `ecom.order.order-confirmed.v1` | Order | **Inventory (commit reservation)**, Cart (clear), Notification |
| `ecom.order.order-cancelled.v1` | Order | Inventory (compensate), Payment (void if authorized), Notification |
| `ecom.inventory.reservation-expired.v1` | Inventory | Order (cancel if still PENDING) |
| `ecom.catalog.product-created/updated/deleted.v1` | Catalog | Search |
| `ecom.inventory.stock-level-changed.v1` | Inventory | Search |
| `ecom.user.user-registered.v1` | User | Notification |

Envelope (from `ecom-common-events`, JSON — see §5 for why not Avro): `eventId (UUID)`, `eventType`, `eventVersion`, `occurredAt`, `correlationId`, `producerService`, `payload`. Payloads carry IDs and `Money` values, never PII (no email/phone/address).

**Topic provisioning:** `auto.create.topics.enable=false` in every environment. In EKS, topics (and their `.dlt` twins) are declared in Terraform (`infra/terraform/modules/kafka-topics`, Mongey/kafka provider against MSK): 6 partitions, RF=3, `min.insync.replicas=2`. In Compose, the producing service's `NewTopic` beans (profile `local` only) create them with RF=1. Topic definitions live in one YAML file (`ecom-common-events/src/main/resources/kafka-topics.yaml`, on every service's classpath), read by both, so they don't drift; `KafkaTopicsYamlTest` fails the build if it diverges from `EventTopics`.

---

## 3. Order Saga (place order → confirm)

**Choreography via Kafka**, not a central orchestrator. The MVP has one linear flow with a few compensation branches, and an orchestrator would be a 9th service with its own failure domain for a flow this shallow. Revisit (introduce a dedicated saga orchestrator) once a shipping/fulfilment branch is added.

**Order is the single decision-maker.** Only Order moves an order to `CONFIRMED` or `CANCELLED`. Inventory never releases stock and Payment never voids unless Order has published a cancel decision (or an ID-matched duplicate of one). Every state transition is a guarded update (`UPDATE orders SET status=? WHERE id=? AND status='PENDING'`). The first event to win decides the outcome, and later conflicting events see 0 rows updated and are handled as described below.

### Transactional outbox (every producer)
Never "write the DB, then call `KafkaTemplate.send`". Each service writes its business change **and** an `outbox(id, aggregate_id, topic, payload, created_at, published_at)` row in the same local transaction. The outbox relay in `ecom-platform-starter` (a `@Scheduled` poller using `SELECT ... FOR UPDATE SKIP LOCKED`, so it's safe with multiple replicas) publishes the row with key=`aggregate_id` and marks it published. Delivery is at-least-once, and the consumer-side dedup in §12 handles duplicates. Upgrade path: swap the poller for Debezium CDC on the outbox table without changing service code.

### Checkout request
`POST /orders` (authenticated users only — **no guest checkout in the MVP**; guests are prompted to log in, and `MergeCart` runs at login):
- Requires an `Idempotency-Key` header, with a unique constraint on `(user_id, idempotency_key)`. A repeat with the same key and same body returns the existing order (`200`). The same key with a different body returns `422`.
- The body carries `paymentMethodRef`, which is a **PSP token** created client-side by the PSP's JS SDK (card data never touches our servers, PCI SAQ-A), plus `shippingAddressId`.
- Order calls `CartService.GetCart` for the raw `sku→qty` lines, then calls `CatalogService.GetProductsBatch` **itself** to get authoritative prices. It does not rely on Cart's hydrated view (Cart's Caffeine L1 may be stale). The price/name snapshot and the order total (`Money`) are computed and persisted here. Tax = flat VAT rate from config for the MVP; shipping = flat fee.

### Happy path
1. Order persists the `orders` row (`PENDING`) + outbox `OrderCreated{orderId, userId, lines[sku,qty]}` in one transaction → `202 Accepted` with `orderId`.
2. Inventory consumes `OrderCreated` → locks the stock rows (`FOR UPDATE`, SKUs locked in sorted order to avoid deadlocks) → inserts `stock_reservations` (`RESERVED`, `expires_at = now + 15m`) + outbox `InventoryReserved{orderId}`. If any line is short, it reserves nothing and emits `InventoryReservationFailed{orderId, shortSkus}`.
3. Payment consumes `InventoryReserved` **directly** (no relay through Order, which saves a hop) → calls `OrderService.GetOrder(orderId)` for the amount, currency, `paymentMethodRef` and status (the event stays thin, and Inventory never learns order totals) → skips if the order is no longer `PENDING` → creates a PSP authorization (manual-capture PaymentIntent) keyed by the idempotency key `orderId` (§12):
   - Authorized synchronously → outbox `PaymentAuthorized`.
   - Declined → outbox `PaymentFailed`.
   - **Requires 3DS/SCA action** → the transaction is stored as `REQUIRES_ACTION`, and the PSP `client_secret` is exposed via `GET /orders/{id}` (`paymentAction` field). The client completes the challenge in the browser, and the PSP then notifies us via a **webhook** (`POST /webhooks/psp`, see §5). Payment verifies the signature, dedups via `psp_webhook_events`, and emits `PaymentAuthorized`/`PaymentFailed`.
4. Order consumes `PaymentAuthorized` → guarded `PENDING → CONFIRMED` + outbox `OrderConfirmed`.
5. Consumers of `OrderConfirmed`:
   - Inventory **commits** the reservation (`RESERVED|EXPIRING → COMMITTED`, `on_hand -= qty`, `reserved -= qty`).
   - Cart **removes the ordered lines**, decrementing the qty by the ordered amount. It does not delete the whole cart, because the user may have added items after checking out.
   - Notification sends the confirmation (fetching contact info via `UserService.GetUser`).

**Capture:** the MVP captures immediately after `OrderConfirmed` (Payment consumes it → PSP capture), since there is no fulfilment service yet. When shipping is added, capture moves to `OrderShipped`.

### Compensation
- `InventoryReservationFailed` → Order `PENDING → CANCELLED` (reason `OUT_OF_STOCK`) + `OrderCancelled` → Notification informs the user. No payment was attempted.
- `PaymentFailed` → Order `PENDING → CANCELLED` (`PAYMENT_DECLINED`) + `OrderCancelled` → Inventory releases the reservation for that `orderId` → Notification informs the user.
- `OrderCancelled` → Payment **voids** any authorization it holds for that `orderId` (emits `PaymentVoided`) and records the order as cancelled locally.

### Reservation expiry (replaces the blind TTL release)
The old design released expired stock straight from the sweep, which races slow 3DS payments: stock is released, then the payment authorizes and the order confirms with no stock held (oversell). New rule: **the sweep asks, Order decides.**
- The Inventory sweep (every minute) marks `RESERVED` rows past `expires_at` as `EXPIRING` and emits `ReservationExpired{orderId}`. **It does not release stock.**
- Order consumes `ReservationExpired`:
  - order still `PENDING` → `CANCELLED` (`PAYMENT_TIMEOUT`) + `OrderCancelled` → Inventory releases stock, and Payment voids anything in flight.
  - order already `CONFIRMED` → no-op. Inventory's `OrderConfirmed` consumer commits from `EXPIRING`.
- **Late authorization:** if `PaymentAuthorized` reaches Order after it has cancelled, the guarded update matches 0 rows. Order logs it, publishes nothing, and relies on Payment. Payment has already recorded the `OrderCancelled`, so when the PSP webhook authorizes afterwards, Payment checks that local flag and **voids immediately**. Both arrival orders converge on "cancelled + voided".
- Backstop for poison or DLT'd events: an alert (not an auto-release) fires on any reservation sitting in `EXPIRING` for more than 1h, or any order sitting in `PENDING` for more than 30m. A human, or a replay from the DLT, resolves it. Auto-releasing without Order's decision is exactly the oversell bug this section removes.

`order_saga_state` exists purely for support/debugging visibility into which step an order is stuck at — it is not a control mechanism.

---

## 4. Cart & Search Specifics

**Cart**: Redis-backed (see table above). Cart hash stores only `sku→qty`; product name/price are hydrated at read time via `CatalogService.GetProductsBatch` (gRPC) with an L1 Caffeine cache for hot SKUs — avoids staleness in long-lived guest carts. Guest cart key TTL ~30 days; user cart has no TTL. **Merge on login**: `CartService.MergeCart(guestCartId, userId)`, policy = **max-quantity-wins per SKU** (not sum, to avoid silent duplication across devices), then delete the guest cart key. The hydrated prices in Cart are **display-only**; checkout re-prices authoritatively (§3). **Post-checkout clearing**: Cart consumes `OrderConfirmed` and decrements the ordered SKUs/qtys (removing a line when it reaches 0), instead of deleting the key. This is idempotent via Redis `SETNX processed:{eventId}` with a 7-day TTL.

**Search**: Elasticsearch, synced via **Kafka consumer, not dual-write** (keeps Catalog's write path fast and Search-agnostic; standard event-carried-state-transfer/CQRS). Two independent consumer groups (`search-service-catalog-indexer`, `search-service-inventory-indexer`) so a slow inventory indexer never blocks catalog indexing.
- **Cross-topic race:** `stock-level-changed` can arrive before `product-created`, because there is no ordering across topics. So every write is an **upsert with a partial doc** (`doc_as_upsert: true`): the stock event creates a stub `{sku, inStock, availableQty}`, and the catalog event fills in the rest. Stubs without catalog fields are excluded from queries by a `filter: exists(name)`.
- **Stale overwrite guard:** each field group carries its source version (`catalogVersion` = product `updated_at`/version column, `stockVersion` = the stock row's version). Updates use a painless script that applies only when the incoming version > the stored version, so redelivered or reordered events can't regress the doc.
- `product-deleted` → hard delete of the doc (a later stock event re-creating a stub is filtered out by the same `exists(name)` rule, and a nightly job purges orphan stubs).

---

## 5. Shared Modules & Cross-Cutting Platform Concerns

### What's shared (infra/contract only)
- **ecom-common-proto**: all gRPC contracts, one JAR every service depends on. Versioned by folder (`v1`, `v2` for breaking changes — never mutate a released message).
- **ecom-common-events**: the JSON envelope class + event payload POJOs.
  - **Resolved conflict**: domain design proposed Avro + Schema Registry; infra design proposed plain JSON + versioned envelope. **Going with JSON + versioned envelope** — for an MVP with one team owning all 8 services, a schema registry is an extra stateful component (HA, compatibility-mode governance) for marginal benefit; the explicit `eventVersion` field plus additive-field discipline gives most of the safety without new infrastructure in either Compose or EKS. Documented upgrade path: move to Avro+Registry once schema governance across more teams becomes a real pain point.
- **ecom-platform-starter** (single module, one package per concern, each with its own conditional autoconfig):
  - `core`: exception hierarchy mapped to gRPC status codes and HTTP problem+json, MDC/correlation-id utilities, pagination/result wrappers, `Money` ↔ `BigDecimal` mapping.
  - `grpc`: server+client interceptors for correlation-ID and auth-claim propagation (Context + gRPC Metadata), Resilience4j-backed channel builder (retry + circuit breaker defaults), OTel gRPC auto-instrumentation wired in alongside.
  - `kafka`: envelope-aware producer/consumer wrappers, the **transactional outbox** (`outbox` table Flyway snippet, `OutboxWriter`, `OutboxRelay` poller — see §3), the `ProcessedEventStore` idempotency helper (see §12), and standardized DLT publishing (`<topic>.dlt`) via `DefaultErrorHandler` + `DeadLetterPublishingRecoverer` after bounded retries.
  - `security`: JWT claims model, JWKS-based validation helpers (Gateway), RS256 signing helpers (User service only).
  - `web`: correlation-ID servlet filter, the trusted-header reader (`X-User-Id`/`X-User-Roles` → `SecurityContext`), problem+json exception mapping for REST controllers.
  - `observability`: `micrometer-tracing-bridge-otel`, OTLP exporter, structured JSON logging (`logstash-logback-encoder`), actuator health-group wiring.
- **ecom-common-testing**: Testcontainers base classes (Postgres, Kafka, Redis, Elasticsearch), shared JUnit5 container-lifecycle extension.

### What's explicitly NOT shared (anti-pattern to avoid)
No shared JPA entity/domain module (services expose gRPC/REST DTOs only, never entities). No shared "common business logic" module (if two services need the same rule, one owns it and the other calls it). No shared database or shared Flyway migrations (each service versions its own schema independently).

### Gateway (`ecom-gateway`)
- **Resolved conflict**: domain design had the Gateway calling services via gRPC directly; infra design had the Gateway as a pure HTTP reverse proxy. **Going with pure HTTP reverse proxy** — avoids gRPC-Web/HTTP-transcoding complexity at the edge for no real MVP benefit; browsers speak JSON/HTTP to the Gateway, the Gateway forwards HTTP to each service's own REST controller (routed via k8s Service DNS), and gRPC stays strictly service-to-service.
- Code-first `RouteLocator` bean (not YAML route blocks) so filters can be composed programmatically across only 8 backends.
- **JWT issuance**: `POST /auth/login` / `/auth/refresh` / `/auth/logout` live in `ecom-user-service`, not the Gateway (keeps Gateway stateless).
  - **RS256 from day one** (changed from HS256): only User service holds the private key (ExternalSecret). It publishes `/.well-known/jwks.json` with a `kid` per key, so rotation = add the new key, sign with it, and drop the old key after the access-token TTL. With HS256 the Gateway would hold the signing secret too, so a leak from either component would let an attacker mint tokens. The cost of RS256 over HS256 with Nimbus is roughly zero.
  - Access token: 15 min TTL. Claims: `sub`, `roles`, `iat`, `exp`, `jti`.
  - **Refresh tokens**: opaque random 256-bit values. Only the SHA-256 hash is stored in `refresh_tokens(id, user_id, token_hash, family_id, expires_at, revoked_at, replaced_by)`, with a 14-day TTL. They are **rotated on every refresh**. If an already-rotated token is reused, the whole `family_id` is revoked (theft detection). Logout revokes the family. The token is delivered as an `HttpOnly; Secure; SameSite=Strict` cookie scoped to `/auth`.
  - Access tokens are not revocable before `exp`. The 15-min TTL is the accepted bound for the MVP, and a Redis `jti` denylist is the post-MVP option.
- **JWT validation**: `spring-security-oauth2-resource-server` with `NimbusReactiveJwtDecoder.withJwkSetUri(user-service JWKS)` (JWKS cached, refreshed on unknown `kid`), validating `exp`, `iss` and `aud`. The library is used purely for its validation filter chain, not for OAuth2 flows.
- **Claims propagation & header trust**: the Gateway's first `GlobalFilter` (highest precedence) **strips every inbound `X-User-*` and `X-Correlation-Id` header supplied by the client**, then re-stamps `X-User-Id` and `X-User-Roles` from the validated JWT. It passes through a client `X-Correlation-Id` only if it matches the UUID format, and otherwise generates a new one. The raw JWT is never forwarded downstream. Each service's inbound filter (`ecom-platform-starter` `web`) trusts these headers **only** because NetworkPolicy (§10) makes the Gateway the sole ingress to service HTTP ports. When a service calls a peer over gRPC, the `grpc` client interceptor re-emits them as gRPC Metadata, preserving the trust chain across multi-hop calls. A Gateway integration test asserts that a spoofed `X-User-Id` is overwritten.
- **Public routes** (no JWT): `GET /products/**`, `GET /search/**`, `/auth/login|refresh`, `POST /users` (register), `/.well-known/jwks.json` (internal-only; not routed publicly), and **`POST /webhooks/psp`**. The webhook route has no JWT and instead has its own rate limit and body-size limit. It is forwarded untouched (raw body preserved) to Payment, which **verifies the PSP signature** (e.g. `Stripe-Signature` HMAC with timestamp tolerance) before doing anything, and dedups on the PSP event id.
- **Admin routes**: `/admin/**` requires `ROLE_ADMIN` at the Gateway **and** is re-checked in the owning service (defense in depth).
- **Rate limiting**: Gateway's built-in Redis-backed `RequestRateLimiter` (token bucket) — distributed-safe across Gateway replicas, zero custom code, reuses Redis already in the stack.

### gRPC platform concerns
- TLS stance: **in-cluster plaintext (h2c) for MVP**, with EKS `NetworkPolicy` providing network-level isolation instead of mTLS — explicit post-MVP hardening item (adopt Linkerd/App Mesh sidecar mTLS later without touching service code).
- Correlation-ID and auth-context interceptors are Spring `@Bean`s wired once in `ecom-platform-starter`'s `grpc` autoconfig — no per-service boilerplate.

### Kafka platform concerns
- Partition key = aggregate ID (`orderId`, `sku`) for per-aggregate ordering.
- 6 partitions/topic, RF=3 in EKS (MSK, provisioned via Terraform — §6a), RF=1 in Compose (single KRaft broker). Topics are declared explicitly and auto-create is off (§2).
- Producers: `acks=all`, `enable.idempotence=true` (the outbox relay is the only producer path).
- Consumer group naming: `<service>-<purpose>-cg`.
- DLT: `<topic>.dlt`, bounded retries (1s/5s/15s backoff) then routed off the main partition.

---

## 6. Kubernetes / EKS Deployment

- Single `ecom` namespace for the app (MVP scope doesn't yet need per-service namespace isolation); `ecom-observability` namespace for Prometheus/Grafana/Tempo/Loki/OTel Collector. `dev`/`staging`/`prod` via Kustomize overlays (`infra/k8s/overlays/`), not label-splitting inside one namespace.
- Per service: `Deployment` + `ClusterIP Service` (HTTP + gRPC ports) + `ConfigMap` (non-secret env, peer DNS names) + `ExternalSecret` + `HorizontalPodAutoscaler`. Service DNS (`catalog-service.ecom.svc.cluster.local`) is the discovery mechanism — no Eureka.
- **Secrets**: External Secrets Operator syncing from AWS Secrets Manager (via IRSA), presented to pods as normal `Secret` mounts — centralizes rotation/audit without any AWS-SDK awareness in app code.
- **Entrypoint**: AWS ALB (via AWS Load Balancer Controller `Ingress`) → `ecom-gateway` Service → Gateway owns all further routing. TLS termination/WAF at the ALB; all business routing/auth stays in one portable Spring component that behaves identically in Compose (just without the ALB in front).
- **HPA**: `minReplicas: 2, maxReplicas: 6`, target 70% CPU for services; Gateway `minReplicas: 2, maxReplicas: 8`.
- **Resources**: default `requests: cpu=250m, memory=512Mi` / `limits: cpu=1000m, memory=1Gi` per service, both always set.
- **Schema migrations under rolling deploys**: Flyway runs at startup (its lock table serializes replicas). Because old and new pods run side by side during a rollout, every migration must be **expand/contract**: add a nullable column or new table → deploy code that writes both → backfill → deploy code that reads new → drop the old column in a *later* release. Destructive DDL (drop/rename, adding a `NOT NULL` column without a default) in the same release as the code change is rejected in review. Large backfills run as a one-off k8s `Job`, not inside Flyway.

---

## 6a. AWS Infrastructure as Code (Terraform)

App manifests (Kustomize) assume the platform already exists; Terraform creates it. State is kept in S3 + a DynamoDB lock table (one state per env), and `infra/terraform/envs/{dev,staging,prod}` compose the shared modules:

| Module | Creates | Notes |
|---|---|---|
| `vpc` | VPC, 3-AZ private/public subnets, NAT | Data stores live in private subnets only |
| `eks` | EKS cluster, managed node groups, OIDC provider, add-ons: AWS LB Controller, External Secrets Operator, metrics-server, cluster-autoscaler/Karpenter | Add-ons installed via the Terraform Helm provider |
| `rds` | **One RDS Postgres instance (Multi-AZ in prod) hosting the 6 logical databases** (`userdb`, `catalogdb`, `inventorydb`, `orderdb`, `paymentdb`, `notificationdb`), each with its own DB user granted only its own DB | One instance per service would be 6× the cost for no MVP benefit. Isolation is kept at the DB/credential level, so any service can be split onto its own instance later by changing only its connection string |
| `elasticache` | Redis (cluster-mode off, 1 primary + 1 replica, Multi-AZ) | Shared by Cart + Gateway rate limiter, which use separate logical DB indexes / key prefixes |
| `msk` | MSK cluster, 3 brokers across 3 AZs, IAM auth | Services authenticate via IRSA (`aws-msk-iam-auth`) |
| `kafka-topics` | All topics + `.dlt` twins from the shared topic YAML (§2) | Mongey/kafka provider |
| `elastic-cloud` | Elastic Cloud Elasticsearch 9 deployment, 2 zones (3 in prod), reachable only over AWS PrivateLink (vpce traffic filter) | Only Search holds its API key (Secrets Manager) |
| `ecr` | One repo per service, `scanOnPush: true`, lifecycle policy (keep the last 30 tags) | |
| `s3` | Product images bucket (private, CloudFront OAC in front) | Catalog writes via pre-signed URLs |
| `iam-irsa` | One IAM role per service ServiceAccount (least privilege: its own Secrets Manager paths, MSK topics, S3 where needed); GitHub OIDC deploy role | |
| `secrets` | Secrets Manager entries (DB creds, JWT private key, PSP API key + webhook secret) — values set out-of-band, never in TF state | Synced into the cluster by ExternalSecrets |

Terraform runs in its own `infra.yml` GitHub Actions workflow (`plan` on PR with the output posted as a comment, `apply` on merge behind the same manual-approval environment as prod deploys).

---

## 7. Docker Compose Local Dev

- `infra/docker-compose/docker-compose.yml` with Compose **profiles**: `core` (**one Postgres container** with the 6 service databases + per-service users created by `config/postgres-init.sql` — mirrors the single-RDS-instance layout in §6a and saves ~5 containers' worth of RAM; Redis; Kafka in KRaft mode — no Zookeeper, no schema registry per §5's JSON-envelope decision; Elasticsearch single-node with `ES_JAVA_OPTS=-Xms512m -Xmx512m`, `xpack.security.enabled=false` locally; Payment runs with `PSP_MODE=fake`, a deterministic in-service PSP keyed by payment-method token, instead of an external mock) vs default/`everything` (adds all 8 services + Gateway, built via `spring-boot:build-image`, plus OTel Collector + Prometheus/Grafana/Tempo/Loki).
- Rough memory budget for `everything`: ~8–10 GB. The observability stack sits behind its own `observability` profile, so `core` + the services under development fit on a 16 GB laptop.
- Config parity with EKS: same env-var-driven Spring config in both environments (`SPRING_DATASOURCE_URL`, `KAFKA_BOOTSTRAP_SERVERS`, etc.) — no Spring Cloud Config Server, no `application-k8s.yml` vs `application-compose.yml` divergence, only value differences via `.env` (gitignored, seeded from committed `.env.example`).

---

## 8. CI/CD

- **GitHub Actions** (standalone repo, not the Marktplaats Jenkins ecosystem).
- `ci.yml`: path-filtered `mvn verify -pl <changed modules> -amd` (changed modules + dependents; a change to `ecom-platform-starter`/proto/events rebuilds everything) on PRs, full reactor build on `main`. Includes OWASP Dependency-Check (fails above CVSS threshold) and Testcontainers-based integration tests.
- `docker-publish.yml`: on merge to `main`, builds only changed service images (`spring-boot:build-image`), Trivy-scans them (fail on HIGH/CRITICAL with a fix available), pushes to **Amazon ECR** (one repo per service, `scanOnPush: true` as a second always-on scan layer).
- `deploy-staging.yml` / production deploy: `kubectl apply -k` against the relevant Kustomize overlay, OIDC-federated AWS credentials (no long-lived keys); production gated behind a manual GitHub Actions approval environment.

---

## 9. Observability

Single **OpenTelemetry Collector** per environment (Compose: 1 container; EKS: 1 Deployment in `ecom-observability`). Every service instruments once via Micrometer + Micrometer Tracing's OTel bridge, exports OTLP to the Collector. Collector fans out to **Prometheus+Grafana / Tempo / Loki** by default; **Datadog is wired as an alternate exporter target** (config-only toggle in `otel-collector-config.yaml`, zero app code change) rather than run in parallel with the OSS stack — avoids paying for and maintaining two overlapping metrics/trace/log backends simultaneously while still giving Datadog as a real, ready option.

- `ecom-platform-starter`'s `observability` package gives every service: OTLP export, structured JSON logs (correlation-id/trace-id/span-id in MDC → every log line), actuator health groups (`liveness`/`readiness` wired to k8s probes), `/actuator/prometheus` + `/actuator/info`.
- Trace context propagation: REST via W3C `traceparent` (auto), gRPC via OTel's gRPC instrumentation layered alongside the correlation-ID interceptor, Kafka via record headers (Micrometer Tracing's Kafka instrumentation — no manual header plumbing needed).

---

## 10. Security Baseline

- Secrets: ExternalSecrets+Secrets Manager in EKS, gitignored `.env` (from `.env.example`) locally — never real secrets committed.
- Dependency scanning: OWASP Dependency-Check in the parent POM (`verify` phase), suppression file for triaged findings.
- Image scanning: Trivy in CI (`docker-publish.yml`) + ECR `scanOnPush` as an always-on second layer.
- Network: default-deny `NetworkPolicy` in `ecom` namespace, explicit allow rules derived from the proto/event dependency graph (Gateway↔services, service↔specific peers, all↔Kafka/Postgres/OTel Collector). This is the MVP's substitute for mTLS (§5). **Service HTTP ports accept ingress only from Gateway pods.** This is what makes the trusted `X-User-*` headers safe (§5), and it is verified in the EKS checks below. gRPC ports accept ingress only from the specific peers that call them.
- Auth: RS256 JWT with JWKS rotation, rotating refresh tokens with reuse detection (§5). Passwords are hashed with Argon2id (Spring Security `Argon2PasswordEncoder`), and `/auth/login` has its own stricter rate limit (per IP + per account).
- PCI: SAQ-A scope. Card data is tokenized client-side by the PSP SDK; the backend only ever sees PSP tokens. PSP webhooks are signature-verified (§5).
- PII: kept out of Kafka events (§2). Logs mask email/phone via a logback masking converter in the starter.

---

## 11. Testing Strategy

Uniform via `ecom-common-testing` so every service's test setup looks the same:
- **Unit tests** (JUnit5+Mockito): business logic, no Spring context — heaviest on Inventory's reservation math and Payment's idempotency checks.
- **Integration tests** (Testcontainers, real Postgres/Redis/Elasticsearch/Kafka per service's actual dependencies, real Flyway migrations — no H2 substitution, since row-locking/JSONB behavior must be tested against the real engine).
- **gRPC contract testing**: provider-driven invariant tests co-located with `ecom-common-proto`'s test module (each implementing service asserts its behavior against documented request/response invariants) — not a full Pact broker for MVP.
- **Kafka event contract testing** (adjusted for the JSON-envelope decision, not Avro registry compatibility checks): consumer-side Testcontainers-Kafka tests asserting a service correctly handles both the current `eventVersion` and one prior version's payload shape, plus a documented additive-only-fields discipline enforced by code review.
- **Outbox tests** (in the starter): a crash injected between the business write and the relay publish, then a restart, still publishes exactly once from the consumer's perspective (dedup). A rollback of the business transaction publishes nothing.
- **End-to-end saga test**: one dedicated `ecom-e2e-tests` module spinning up Order+Inventory+Payment+Cart+Kafka+Redis via Testcontainers, services launched as child processes with the fake PSP, asserting the full choreography — the only place the saga as a whole is verified. Required scenarios:
  1. Happy path → `CONFIRMED`, reservation `COMMITTED`, `on_hand` decremented, cart lines removed, payment captured.
  2. Out of stock → `CANCELLED(OUT_OF_STOCK)`, no payment attempted.
  3. Payment declined → `CANCELLED(PAYMENT_DECLINED)`, reservation released.
  4. 3DS pending past TTL → `ReservationExpired` → `CANCELLED(PAYMENT_TIMEOUT)`, stock released. A late webhook authorization is then **voided**.
  5. `PaymentAuthorized` and `ReservationExpired` racing (both orders of arrival) → both converge (confirmed+committed, or cancelled+voided), and stock is never negative.
  6. Duplicate delivery of every event type → no double reservation, charge or notification.
  7. `POST /orders` replayed with the same `Idempotency-Key` → one order.
- **Concurrency test** (Inventory integration): N parallel orders for the last M units of a SKU → exactly M succeed, `on_hand - reserved` is never < 0.
- **Security tests** (Gateway): a spoofed `X-User-Id` is overwritten, an expired or wrong-`kid` JWT is rejected, a refresh-token reuse revokes the family, and a webhook with a bad signature is rejected by Payment.

---

## 12. Idempotency (Kafka Consumers)

At-least-once delivery + idempotent-consumer pattern (not relying on Kafka EOS alone, since every consumer here also writes to Postgres/Redis/calls gRPC):
- Every event carries a stable `eventId`. Every consuming service has a `processed_events(event_id PK, processed_at)` table (or Redis `SETNX` for Cart-adjacent cases); handler does check-insert-process in one local transaction, provided generically by `ecom-platform-starter`'s `kafka` template method. Any events the handler emits go to the outbox **in that same transaction** (§3), so "consume → change state → emit" is atomic per service. `processed_events` rows are purged after 14 days (longer than max Kafka retention + DLT replay window).
- **Payment additionally keys on `orderId`** as the PSP idempotency key (`Idempotency-Key` header on the PSP API) plus a unique `payment_transactions(order_id, type)` constraint. This is a second, belt-and-suspenders layer, since a payment must never double-authorize even if event-dedup had a bug.
- **HTTP-side idempotency**: `POST /orders` requires an `Idempotency-Key` header (§3). This covers client retries/double-clicks, which Kafka-side dedup can't see.
- **State-machine guards** make handlers safe even if dedup failed: every transition is a conditional update on the expected current status (§3), so a replayed `PaymentAuthorized` on a `CONFIRMED` order is a no-op.
- Ordering relies on Kafka partition-key-by-business-ID (§5), not consumer-side sequencing — dedup and ordering stay orthogonal concerns. Cross-topic ordering is **not** assumed anywhere (see the §3 race handling and the §4 Search upserts).

---

## Critical Files to Create First

1. `/Users/vinothkumar/IdeaProjects/ecom/pom.xml` — parent POM, module list, BOMs.
2. `/Users/vinothkumar/IdeaProjects/ecom/ecom-common-proto/src/main/proto/ecom/**/v1/*.proto` — all service contracts (build this before any service, everything depends on it).
3. `/Users/vinothkumar/IdeaProjects/ecom/ecom-common-events/.../EventEnvelope.java` — the Kafka envelope every producer/consumer uses.
4. `/Users/vinothkumar/IdeaProjects/ecom/ecom-platform-starter/.../kafka/OutboxWriter.java` + `OutboxRelay.java` + `IdempotentConsumerTemplate.java` — the shared outbox and dedup mechanisms, which every saga participant depends on.
5. `/Users/vinothkumar/IdeaProjects/ecom/ecom-platform-starter/.../grpc/GrpcCorrelationAndAuthInterceptors.java` — correlation-ID/auth propagation used by every service.
6. `/Users/vinothkumar/IdeaProjects/ecom/ecom-gateway/.../GatewayRouteConfig.java` + `HeaderSanitizingFilter.java` (strip client `X-User-*`) + `JwtClaimsPropagationFilter.java`.
7. `/Users/vinothkumar/IdeaProjects/ecom/ecom-order-service/` and `/Users/vinothkumar/IdeaProjects/ecom/ecom-inventory-service/` — the core saga logic (order state machine, reservation lifecycle `RESERVED→EXPIRING→COMMITTED|RELEASED`), the highest-risk business flow. Build and verify these end-to-end first, before the remaining services.
8. `/Users/vinothkumar/IdeaProjects/ecom/infra/docker-compose/docker-compose.yml` + `config/postgres-init.sql` — needed as soon as 2+ services exist, to verify integration locally.
9. `/Users/vinothkumar/IdeaProjects/ecom/infra/kafka-topics.yaml` — the single source for topic definitions (read by Terraform and the local `NewTopic` beans).
10. `/Users/vinothkumar/IdeaProjects/ecom/infra/terraform/` modules + `envs/dev` — before the first EKS deploy. It can be built in parallel with services once the topic YAML and the secret list are stable.
11. `/Users/vinothkumar/IdeaProjects/ecom/infra/k8s/base/<service>/` templates — after the local Compose flow is verified and `envs/dev` is applied.

## Suggested Build Order

1. Scaffolding: parent POM, `ecom-common-proto` (incl. `Money`), `ecom-common-events`, `ecom-platform-starter` (the **outbox + idempotent consumer are implemented and tested here first**, not stubbed), `ecom-common-testing`, and the topic YAML.
2. User (incl. RS256 signing, JWKS, refresh-token rotation) + Catalog (incl. admin CRUD) + Inventory (reservation lifecycle + concurrency test) services, with their REST+gRPC surfaces and Flyway migrations.
3. Cart service (Redis) + Gateway (routing, header sanitizing, JWKS validation, rate limiting) — enough to browse and add to cart end-to-end.
4. Order + Payment services + the full choreographed saga, including 3DS/webhook handling, expiry and late-authorization voiding. This is the highest-complexity flow: all 7 `ecom-e2e-tests` scenarios (§11) must be green before moving on.
5. Notification service (consumes existing events + calls `GetUser`, lowest risk).
6. Search service (Elasticsearch + Catalog/Inventory event consumers with versioned upserts) — can be built in parallel with step 4 since it has no dependency on the saga.
7. Observability wiring (starter `observability` + OTel Collector + Compose/K8s dashboards + the stuck-order/stuck-reservation alerts from §3), then CI/CD pipelines.
8. Terraform `envs/dev` (can start in parallel from step 3), then EKS manifests + first dev deploy, then staging.

## Verification

- **Local**: `mvn -T 1C verify` at the repo root builds and runs unit+integration tests (Testcontainers) for all modules. `docker compose --profile everything up` brings up the full system; smoke-test the golden path (register → login → browse/search → add to cart → checkout → see order confirmed → receive notification event) via the Gateway's HTTP endpoints (curl/Postman), and verify Grafana/Tempo/Loki show metrics/traces/logs for the request, and Kafka topics show the expected event sequence (`kafka-console-consumer` or a UI like Kafdrop/Redpanda Console added to the Compose `everything` profile).
- **Saga correctness**: run the `ecom-e2e-tests` suite (all 7 scenarios in §11). Chaos-style checks:
  - Kill the Order pod between the DB commit and the outbox publish → the event is still delivered after restart.
  - Deliberately DLT an `OrderCancelled` → the reservation stays `EXPIRING` (not auto-released) and the stuck-reservation alert fires; replaying from the DLT resolves it.
- **CI**: open a PR touching one service, confirm only that service (+ dependents) rebuilds and its tests run; confirm Dependency-Check/Trivy gates actually fail the build when a known-vulnerable dependency/base image is introduced deliberately, then pass once fixed.
- **Infra**: `terraform plan` for `envs/dev` is clean after apply (no drift). The topics listed in MSK match `kafka-topics.yaml`, and each service's IRSA role can reach only its own secrets/DB/topics (a negative test from a debug pod using another service's SA fails).
- **EKS**: after first `kubectl apply -k overlays/dev`, confirm:
  - all pods reach `Ready` (readiness probes passing);
  - `NetworkPolicy` blocks a manually-attempted cross-service call that shouldn't be allowed, **including a direct HTTP call to a service port from a non-Gateway pod** (the header-trust guarantee);
  - ExternalSecret-synced values actually appear in the running containers' env;
  - a rolling deploy carrying an expand-phase migration serves traffic without errors throughout.
