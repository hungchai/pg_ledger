-- V003: infrastructure tables owned by ops, not by the API service.
-- The API role may run with CREATE revoked; PostgresLedgerStore.migrate()
-- creates these idempotently but a read-only-DDL deployment needs the DDL
-- shipped as a migration. shedlock coordinates HA scheduled tasks
-- (exactly one replica executes each scheduled job).
--
-- Apply as the migration/owner role (e.g. via Flyway/psql as admin):
--   psql "$WRITER_URL" -f db/V003__shedlock.sql

CREATE TABLE IF NOT EXISTS shedlock (
    name       VARCHAR(64)  NOT NULL PRIMARY KEY,
    lock_until TIMESTAMP    NOT NULL,
    locked_at  TIMESTAMP    NOT NULL,
    locked_by  VARCHAR(255) NOT NULL
);

-- The API role only needs DML on this table.
GRANT SELECT, INSERT, UPDATE, DELETE ON shedlock TO PUBLIC;
