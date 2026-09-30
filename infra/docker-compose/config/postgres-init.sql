-- One Postgres container hosting the 6 service databases (mirrors the single RDS instance in EKS, ADR 0005).
-- Each service user owns only its own database; PUBLIC is revoked so no service can connect to a peer's DB.
-- Local-dev credentials only (user = password = service name). Runs once, on first container start.

CREATE ROLE "user"         LOGIN PASSWORD 'user';
CREATE ROLE catalog        LOGIN PASSWORD 'catalog';
CREATE ROLE inventory      LOGIN PASSWORD 'inventory';
CREATE ROLE "order"        LOGIN PASSWORD 'order';
CREATE ROLE payment        LOGIN PASSWORD 'payment';
CREATE ROLE notification   LOGIN PASSWORD 'notification';

CREATE DATABASE userdb         OWNER "user";
CREATE DATABASE catalogdb      OWNER catalog;
CREATE DATABASE inventorydb    OWNER inventory;
CREATE DATABASE orderdb        OWNER "order";
CREATE DATABASE paymentdb      OWNER payment;
CREATE DATABASE notificationdb OWNER notification;

REVOKE ALL ON DATABASE userdb         FROM PUBLIC;
REVOKE ALL ON DATABASE catalogdb      FROM PUBLIC;
REVOKE ALL ON DATABASE inventorydb    FROM PUBLIC;
REVOKE ALL ON DATABASE orderdb        FROM PUBLIC;
REVOKE ALL ON DATABASE paymentdb      FROM PUBLIC;
REVOKE ALL ON DATABASE notificationdb FROM PUBLIC;

-- PG15+: the public schema is owned by pg_database_owner, so each owner can create tables in its own DB.
