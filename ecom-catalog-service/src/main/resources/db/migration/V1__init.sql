CREATE TABLE categories (
    id         UUID         PRIMARY KEY,
    name       VARCHAR(200) NOT NULL,
    parent_id  UUID         REFERENCES categories (id),
    created_at TIMESTAMPTZ  NOT NULL,
    updated_at TIMESTAMPTZ  NOT NULL,
    CONSTRAINT uq_categories_parent_name UNIQUE NULLS NOT DISTINCT (parent_id, name)
);
CREATE INDEX idx_categories_parent ON categories (parent_id);

-- version is bumped by the application on every write (incl. variant/price/image changes) and carried
-- in product events; Search uses it to discard stale or reordered updates.
CREATE TABLE products (
    id          UUID         PRIMARY KEY,
    name        VARCHAR(300) NOT NULL,
    description TEXT,
    category_id UUID         NOT NULL REFERENCES categories (id),
    brand       VARCHAR(200),
    active      BOOLEAN      NOT NULL,
    version     BIGINT       NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL,
    updated_at  TIMESTAMPTZ  NOT NULL,
    deleted_at  TIMESTAMPTZ
);
CREATE INDEX idx_products_listing ON products (category_id, created_at DESC) WHERE active AND deleted_at IS NULL;
CREATE INDEX idx_products_listing_all ON products (created_at DESC) WHERE active AND deleted_at IS NULL;

-- SKUs are never reused: removed variants are deactivated, not deleted, so orders/carts keep resolving them.
CREATE TABLE product_variants (
    sku            VARCHAR(64)    PRIMARY KEY,
    product_id     UUID           NOT NULL REFERENCES products (id),
    variant_name   VARCHAR(200)   NOT NULL,
    price_amount   NUMERIC(19, 4) NOT NULL CHECK (price_amount > 0),
    price_currency CHAR(3)        NOT NULL,
    active         BOOLEAN        NOT NULL
);
CREATE INDEX idx_product_variants_product ON product_variants (product_id);

CREATE TABLE product_images (
    id         UUID          PRIMARY KEY,
    product_id UUID          NOT NULL REFERENCES products (id),
    s3_key     VARCHAR(1024) NOT NULL UNIQUE,
    position   INT           NOT NULL,
    created_at TIMESTAMPTZ   NOT NULL
);
CREATE INDEX idx_product_images_product ON product_images (product_id, position);
