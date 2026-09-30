-- One authorization per order (PLAN §12): the unique (order_id, type) is the DB-level guard that,
-- together with the PSP idempotency key = orderId, makes a double authorization impossible.
CREATE TABLE payment_transactions (
    id             UUID          PRIMARY KEY,
    order_id       UUID          NOT NULL,
    type           VARCHAR(32)   NOT NULL,
    status         VARCHAR(32)   NOT NULL,
    amount         NUMERIC(19,4) NOT NULL,
    currency       CHAR(3)       NOT NULL,
    psp_reference  VARCHAR(255),
    -- only populated while status = REQUIRES_ACTION (3DS/SCA); nulled on every other transition
    client_secret  VARCHAR(255),
    failure_reason VARCHAR(255),
    version        BIGINT        NOT NULL,
    created_at     TIMESTAMPTZ   NOT NULL,
    updated_at     TIMESTAMPTZ   NOT NULL,
    CONSTRAINT uq_payment_transactions_order_type UNIQUE (order_id, type),
    CONSTRAINT ck_payment_transactions_status
        CHECK (status IN ('REQUIRES_ACTION', 'AUTHORIZED', 'FAILED', 'VOIDED', 'CAPTURED')),
    CONSTRAINT ck_payment_transactions_client_secret
        CHECK (client_secret IS NULL OR status = 'REQUIRES_ACTION')
);
CREATE UNIQUE INDEX uq_payment_transactions_psp_reference
    ON payment_transactions (psp_reference) WHERE psp_reference IS NOT NULL;

-- PCI SAQ-A: only the PSP token and display metadata the PSP returns; never PAN/CVC.
CREATE TABLE payment_methods (
    id         UUID         PRIMARY KEY,
    user_id    UUID         NOT NULL,
    psp_ref    VARCHAR(255) NOT NULL,
    brand      VARCHAR(32),
    last4      CHAR(4),
    created_at TIMESTAMPTZ  NOT NULL,
    CONSTRAINT uq_payment_methods_user_ref UNIQUE (user_id, psp_ref)
);

CREATE TABLE psp_webhook_events (
    psp_event_id VARCHAR(255) PRIMARY KEY,
    type         VARCHAR(128) NOT NULL,
    received_at  TIMESTAMPTZ  NOT NULL
);

-- Local record of Order's cancel decision (PLAN §3 late-authorization rule).
CREATE TABLE cancelled_orders (
    order_id     UUID        PRIMARY KEY,
    cancelled_at TIMESTAMPTZ NOT NULL
);
