-- MVP is single-warehouse: warehouse_id is fixed to 'DEFAULT' but part of the key so multi-warehouse
-- allocation can be added without a schema break.
CREATE TABLE stock_levels (
    sku          VARCHAR(64) NOT NULL,
    warehouse_id VARCHAR(32) NOT NULL DEFAULT 'DEFAULT',
    on_hand      INTEGER     NOT NULL DEFAULT 0,
    reserved     INTEGER     NOT NULL DEFAULT 0,
    version      BIGINT      NOT NULL DEFAULT 0,
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (sku, warehouse_id),
    CONSTRAINT chk_stock_levels_non_negative CHECK (on_hand >= 0 AND reserved >= 0),
    CONSTRAINT chk_stock_levels_reserved_le_on_hand CHECK (reserved <= on_hand)
);

CREATE TABLE stock_reservations (
    order_id     UUID        NOT NULL,
    sku          VARCHAR(64) NOT NULL,
    warehouse_id VARCHAR(32) NOT NULL DEFAULT 'DEFAULT',
    qty          INTEGER     NOT NULL,
    status       VARCHAR(16) NOT NULL,
    expires_at   TIMESTAMPTZ NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (order_id, sku),
    CONSTRAINT chk_stock_reservations_qty CHECK (qty > 0),
    CONSTRAINT chk_stock_reservations_status CHECK (status IN ('RESERVED', 'EXPIRING', 'COMMITTED', 'RELEASED'))
);

-- sweep: RESERVED rows past their expiry
CREATE INDEX idx_stock_reservations_due ON stock_reservations (expires_at) WHERE status = 'RESERVED';
-- alerting gauge: EXPIRING rows nobody resolved
CREATE INDEX idx_stock_reservations_expiring ON stock_reservations (updated_at) WHERE status = 'EXPIRING';
