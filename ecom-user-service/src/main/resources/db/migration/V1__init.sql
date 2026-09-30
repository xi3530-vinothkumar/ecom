CREATE TABLE roles (
    name VARCHAR(32) PRIMARY KEY
);
INSERT INTO roles (name) VALUES ('CUSTOMER'), ('ADMIN');

CREATE TABLE users (
    id            UUID         PRIMARY KEY,
    -- always stored lower-cased; the functional index guards against any writer that forgets
    email         VARCHAR(320) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    full_name     VARCHAR(200) NOT NULL,
    phone         VARCHAR(32),
    created_at    TIMESTAMPTZ  NOT NULL,
    updated_at    TIMESTAMPTZ  NOT NULL,
    version       BIGINT       NOT NULL
);
CREATE UNIQUE INDEX ux_users_email ON users (lower(email));

CREATE TABLE user_roles (
    user_id   UUID        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    role_name VARCHAR(32) NOT NULL REFERENCES roles (name),
    PRIMARY KEY (user_id, role_name)
);

CREATE TABLE addresses (
    id           UUID         PRIMARY KEY,
    user_id      UUID         NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    line1        VARCHAR(200) NOT NULL,
    line2        VARCHAR(200),
    city         VARCHAR(100) NOT NULL,
    postal_code  VARCHAR(20)  NOT NULL,
    country_code VARCHAR(2)   NOT NULL,
    created_at   TIMESTAMPTZ  NOT NULL,
    updated_at   TIMESTAMPTZ  NOT NULL,
    version      BIGINT       NOT NULL
);
CREATE INDEX idx_addresses_user_id ON addresses (user_id);

-- Opaque refresh tokens: only the SHA-256 hex digest is stored. A rotated token keeps its row
-- (revoked_at + replaced_by set) so that a later reuse can be detected and the family revoked.
CREATE TABLE refresh_tokens (
    id          UUID        PRIMARY KEY,
    user_id     UUID        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    token_hash  VARCHAR(64) NOT NULL UNIQUE,
    family_id   UUID        NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL,
    expires_at  TIMESTAMPTZ NOT NULL,
    revoked_at  TIMESTAMPTZ,
    replaced_by UUID
);
CREATE INDEX idx_refresh_tokens_family ON refresh_tokens (family_id);
CREATE INDEX idx_refresh_tokens_expires_at ON refresh_tokens (expires_at);

-- Per-account login throttling (row locked FOR UPDATE during a login attempt).
CREATE TABLE login_failures (
    user_id           UUID        PRIMARY KEY REFERENCES users (id) ON DELETE CASCADE,
    failure_count     INT         NOT NULL,
    window_started_at TIMESTAMPTZ,
    locked_until      TIMESTAMPTZ
);
