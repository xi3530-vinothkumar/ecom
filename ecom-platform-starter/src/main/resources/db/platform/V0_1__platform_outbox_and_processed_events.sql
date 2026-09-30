-- Provided by ecom-platform-starter. Services add classpath:db/platform to spring.flyway.locations
-- (set by default in ecom-platform-defaults.yml). Service migrations start at V1.

-- Transactional outbox: rows are written in the same transaction as the business change and
-- relayed to Kafka by OutboxRelay (SELECT ... FOR UPDATE SKIP LOCKED, safe with N replicas).
CREATE TABLE outbox (
    id           BIGSERIAL PRIMARY KEY,
    event_id     UUID         NOT NULL UNIQUE,
    topic        VARCHAR(200) NOT NULL,
    message_key  VARCHAR(200) NOT NULL,
    payload      TEXT         NOT NULL,
    traceparent  VARCHAR(100),
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    published_at TIMESTAMPTZ
);
CREATE INDEX idx_outbox_unpublished ON outbox (id) WHERE published_at IS NULL;
CREATE INDEX idx_outbox_published_at ON outbox (published_at) WHERE published_at IS NOT NULL;

-- Idempotent consumer: one row per (event, consumer); inserted in the handler's transaction.
CREATE TABLE processed_events (
    event_id     UUID         NOT NULL,
    consumer     VARCHAR(200) NOT NULL,
    processed_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (event_id, consumer)
);
CREATE INDEX idx_processed_events_processed_at ON processed_events (processed_at);
