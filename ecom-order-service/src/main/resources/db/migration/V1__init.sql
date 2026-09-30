CREATE TABLE orders (
    id                  UUID          PRIMARY KEY,
    user_id             VARCHAR(64)   NOT NULL,
    status              VARCHAR(16)   NOT NULL CHECK (status IN ('PENDING', 'CONFIRMED', 'CANCELLED')),
    cancel_reason       VARCHAR(32),
    idempotency_key     VARCHAR(100)  NOT NULL,
    request_hash        CHAR(64)      NOT NULL,
    currency            CHAR(3)       NOT NULL,
    subtotal_amount     NUMERIC(19,4) NOT NULL,
    vat_amount          NUMERIC(19,4) NOT NULL,
    shipping_amount     NUMERIC(19,4) NOT NULL,
    total_amount        NUMERIC(19,4) NOT NULL,
    payment_method_ref  VARCHAR(255)  NOT NULL,
    shipping_address_id VARCHAR(64)   NOT NULL,
    ship_line1          VARCHAR(255)  NOT NULL,
    ship_line2          VARCHAR(255),
    ship_city           VARCHAR(128)  NOT NULL,
    ship_postal_code    VARCHAR(32)   NOT NULL,
    ship_country_code   CHAR(2)       NOT NULL,
    version             BIGINT        NOT NULL,
    created_at          TIMESTAMPTZ   NOT NULL,
    updated_at          TIMESTAMPTZ   NOT NULL,
    CONSTRAINT uq_orders_user_idempotency_key UNIQUE (user_id, idempotency_key)
);
CREATE INDEX idx_orders_user_created ON orders (user_id, created_at DESC);
-- stale-PENDING gauge scans only this partial index
CREATE INDEX idx_orders_pending_created ON orders (created_at) WHERE status = 'PENDING';

CREATE TABLE order_lines (
    order_id   UUID          NOT NULL REFERENCES orders (id) ON DELETE CASCADE,
    sku        VARCHAR(64)   NOT NULL,
    name       VARCHAR(512)  NOT NULL,
    unit_price NUMERIC(19,4) NOT NULL,
    quantity   INTEGER       NOT NULL CHECK (quantity > 0),
    line_total NUMERIC(19,4) NOT NULL,
    PRIMARY KEY (order_id, sku)
);

-- Support/debugging visibility only: the last saga step observed per order. Never read for decisions.
CREATE TABLE order_saga_state (
    order_id   UUID        PRIMARY KEY REFERENCES orders (id) ON DELETE CASCADE,
    step       VARCHAR(48) NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);
