-- pgledger schema (https://github.com/pgr0ss/pgledger) plus balance_type registry.
-- pgledger_balance_types holds registered codes (AVAILABLE, LOCKED, ...).
-- pgledger_accounts.id is the internal primary key referenced by transfers and
-- entries. account_id is the caller's business account. One row is one balance:
-- unique (account_id, balance_type, currency).

-- UUID to ULID text. Ids are stored as text, so the reverse (ULID to UUID) is not loaded.

CREATE OR REPLACE FUNCTION format_ulid(bytes bytea) RETURNS text AS $$
DECLARE
  encoding   bytea = '0123456789ABCDEFGHJKMNPQRSTVWXYZ';
  output     text  = '';
BEGIN

  -- Encode the timestamp
  output = output || CHR(GET_BYTE(encoding, (GET_BYTE(bytes, 0) & 224) >> 5));
  output = output || CHR(GET_BYTE(encoding, (GET_BYTE(bytes, 0) & 31)));
  output = output || CHR(GET_BYTE(encoding, (GET_BYTE(bytes, 1) & 248) >> 3));
  output = output || CHR(GET_BYTE(encoding, ((GET_BYTE(bytes, 1) & 7) << 2) | ((GET_BYTE(bytes, 2) & 192) >> 6)));
  output = output || CHR(GET_BYTE(encoding, (GET_BYTE(bytes, 2) & 62) >> 1));
  output = output || CHR(GET_BYTE(encoding, ((GET_BYTE(bytes, 2) & 1) << 4) | ((GET_BYTE(bytes, 3) & 240) >> 4)));
  output = output || CHR(GET_BYTE(encoding, ((GET_BYTE(bytes, 3) & 15) << 1) | ((GET_BYTE(bytes, 4) & 128) >> 7)));
  output = output || CHR(GET_BYTE(encoding, (GET_BYTE(bytes, 4) & 124) >> 2));
  output = output || CHR(GET_BYTE(encoding, ((GET_BYTE(bytes, 4) & 3) << 3) | ((GET_BYTE(bytes, 5) & 224) >> 5)));
  output = output || CHR(GET_BYTE(encoding, (GET_BYTE(bytes, 5) & 31)));

  -- Encode the entropy
  output = output || CHR(GET_BYTE(encoding, (GET_BYTE(bytes, 6) & 248) >> 3));
  output = output || CHR(GET_BYTE(encoding, ((GET_BYTE(bytes, 6) & 7) << 2) | ((GET_BYTE(bytes, 7) & 192) >> 6)));
  output = output || CHR(GET_BYTE(encoding, (GET_BYTE(bytes, 7) & 62) >> 1));
  output = output || CHR(GET_BYTE(encoding, ((GET_BYTE(bytes, 7) & 1) << 4) | ((GET_BYTE(bytes, 8) & 240) >> 4)));
  output = output || CHR(GET_BYTE(encoding, ((GET_BYTE(bytes, 8) & 15) << 1) | ((GET_BYTE(bytes, 9) & 128) >> 7)));
  output = output || CHR(GET_BYTE(encoding, (GET_BYTE(bytes, 9) & 124) >> 2));
  output = output || CHR(GET_BYTE(encoding, ((GET_BYTE(bytes, 9) & 3) << 3) | ((GET_BYTE(bytes, 10) & 224) >> 5)));
  output = output || CHR(GET_BYTE(encoding, (GET_BYTE(bytes, 10) & 31)));
  output = output || CHR(GET_BYTE(encoding, (GET_BYTE(bytes, 11) & 248) >> 3));
  output = output || CHR(GET_BYTE(encoding, ((GET_BYTE(bytes, 11) & 7) << 2) | ((GET_BYTE(bytes, 12) & 192) >> 6)));
  output = output || CHR(GET_BYTE(encoding, (GET_BYTE(bytes, 12) & 62) >> 1));
  output = output || CHR(GET_BYTE(encoding, ((GET_BYTE(bytes, 12) & 1) << 4) | ((GET_BYTE(bytes, 13) & 240) >> 4)));
  output = output || CHR(GET_BYTE(encoding, ((GET_BYTE(bytes, 13) & 15) << 1) | ((GET_BYTE(bytes, 14) & 128) >> 7)));
  output = output || CHR(GET_BYTE(encoding, (GET_BYTE(bytes, 14) & 124) >> 2));
  output = output || CHR(GET_BYTE(encoding, ((GET_BYTE(bytes, 14) & 3) << 3) | ((GET_BYTE(bytes, 15) & 224) >> 5)));
  output = output || CHR(GET_BYTE(encoding, (GET_BYTE(bytes, 15) & 31)));

  RETURN output;
END
$$
LANGUAGE plpgsql
IMMUTABLE;

CREATE OR REPLACE FUNCTION uuid_to_ulid(id uuid) RETURNS text AS $$
BEGIN
    RETURN format_ulid(uuid_send(id));
END
$$
LANGUAGE plpgsql
IMMUTABLE;


CREATE OR REPLACE FUNCTION pgledger_uuidv7_exists() RETURNS BOOL
AS $$
    SELECT EXISTS(SELECT * FROM pg_proc WHERE proname = 'uuidv7');
$$ LANGUAGE sql IMMUTABLE;

CREATE OR REPLACE FUNCTION pgledger_uuidv7_microsecond() RETURNS UUID
AS $$
    select encode(
        substring(int8send(floor(t_ms)::int8) from 3) ||
        int2send((7<<12)::int2 | ((t_ms-floor(t_ms))*4096)::int2) ||
        substring(uuid_send(gen_random_uuid()) from 9 for 8)
        , 'hex')::uuid
    from (select extract(epoch from clock_timestamp())*1000 as t_ms) s
$$ LANGUAGE sql VOLATILE;

CREATE OR REPLACE FUNCTION pgledger_uuidv7() RETURNS UUID
AS $$
DECLARE
    result uuid;
BEGIN
    IF pgledger_uuidv7_exists() THEN
        EXECUTE 'select uuidv7()' INTO result;
        RETURN result;
    ELSE
        RETURN pgledger_uuidv7_microsecond();
    END IF;
END
$$ LANGUAGE plpgsql VOLATILE;

CREATE OR REPLACE FUNCTION pgledger_generate_id(prefix TEXT) RETURNS TEXT
AS $$
    SELECT prefix || '_' || uuid_to_ulid(pgledger_uuidv7())
$$ LANGUAGE sql VOLATILE;


CREATE TABLE IF NOT EXISTS pgledger_balance_types (
    code TEXT PRIMARY KEY,
    name TEXT NOT NULL,
    description TEXT,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE IF NOT EXISTS pgledger_accounts (
    id TEXT PRIMARY KEY DEFAULT pgledger_generate_id('pgla'),
    account_id TEXT NOT NULL,
    balance_type TEXT NOT NULL REFERENCES pgledger_balance_types (code),
    name TEXT NOT NULL,
    currency TEXT NOT NULL,
    balance NUMERIC NOT NULL DEFAULT 0,
    version BIGINT NOT NULL DEFAULT 0,
    allow_negative_balance BOOLEAN NOT NULL,
    allow_positive_balance BOOLEAN NOT NULL,
    metadata JSONB,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    account_class TEXT NOT NULL DEFAULT 'CLIENT',
    deleted BOOLEAN NOT NULL DEFAULT false,
    UNIQUE (account_id, balance_type, currency),
    CONSTRAINT pgledger_accounts_account_class_chk CHECK (
        account_class IN ('CLIENT', 'COMPANY', 'BANK', 'NOSTRO', 'SUSPENSE', 'CONTROL')
    )
);

CREATE INDEX IF NOT EXISTS pgledger_accounts_account_id_idx ON pgledger_accounts (account_id);

CREATE TABLE IF NOT EXISTS pgledger_transfers (
    id TEXT PRIMARY KEY DEFAULT pgledger_generate_id('pglt'),
    from_account_id TEXT NOT NULL REFERENCES pgledger_accounts (id),
    to_account_id TEXT NOT NULL REFERENCES pgledger_accounts (id),
    amount NUMERIC NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    event_at TIMESTAMPTZ NOT NULL,
    metadata JSONB,
    CHECK (amount > 0 AND from_account_id != to_account_id)
);

CREATE INDEX IF NOT EXISTS pgledger_transfers_from_account_id_idx ON pgledger_transfers (from_account_id);
CREATE INDEX IF NOT EXISTS pgledger_transfers_to_account_id_idx ON pgledger_transfers (to_account_id);
CREATE INDEX IF NOT EXISTS pgledger_transfers_event_at_idx ON pgledger_transfers (event_at);
CREATE INDEX IF NOT EXISTS pgledger_transfers_created_at_idx ON pgledger_transfers (created_at DESC, id DESC);

CREATE TABLE IF NOT EXISTS pgledger_entries (
    id TEXT PRIMARY KEY DEFAULT pgledger_generate_id('pgle'),
    account_id TEXT NOT NULL REFERENCES pgledger_accounts (id),
    transfer_id TEXT NOT NULL REFERENCES pgledger_transfers (id),
    amount NUMERIC NOT NULL,
    account_previous_balance NUMERIC NOT NULL,
    account_current_balance NUMERIC NOT NULL,
    account_version BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX IF NOT EXISTS pgledger_entries_account_id_idx ON pgledger_entries (account_id);
CREATE INDEX IF NOT EXISTS pgledger_entries_transfer_id_idx ON pgledger_entries (transfer_id);

-- One pool per (balance_type, currency). Shard rows are BANK accounts. Soft-deleted
-- shards stay here so old transfers still resolve. The picker ignores them.
CREATE TABLE IF NOT EXISTS pgledger_bank_pools (
    currency TEXT NOT NULL,
    balance_type TEXT NOT NULL REFERENCES pgledger_balance_types (code),
    pool_size INT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (currency, balance_type),
    CHECK (pool_size >= 1)
);

CREATE TABLE IF NOT EXISTS pgledger_bank_shards (
    currency TEXT NOT NULL,
    balance_type TEXT NOT NULL,
    shard INT NOT NULL,
    account_id TEXT NOT NULL,
    PRIMARY KEY (currency, balance_type, shard),
    CHECK (shard >= 0),
    UNIQUE (account_id, balance_type, currency),
    FOREIGN KEY (account_id, balance_type, currency)
        REFERENCES pgledger_accounts (account_id, balance_type, currency)
);

CREATE TABLE IF NOT EXISTS pgledger_cash_requests (
    request_id TEXT PRIMARY KEY,
    transfer_id TEXT NOT NULL REFERENCES pgledger_transfers (id),
    direction TEXT NOT NULL,
    client_account_id TEXT NOT NULL,
    balance_type TEXT NOT NULL,
    currency TEXT NOT NULL,
    amount NUMERIC NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CHECK (direction IN ('DEPOSIT', 'WITHDRAWAL')),
    CHECK (amount > 0)
);

CREATE OR REPLACE VIEW pgledger_balance_types_view AS
SELECT
    code,
    name,
    description,
    created_at,
    updated_at
FROM pgledger_balance_types;

CREATE OR REPLACE VIEW pgledger_accounts_view AS
SELECT
    id,
    account_id,
    balance_type,
    name,
    currency,
    balance,
    version,
    allow_negative_balance,
    allow_positive_balance,
    metadata,
    created_at,
    updated_at,
    account_class,
    deleted
FROM pgledger_accounts;

CREATE OR REPLACE VIEW pgledger_transfers_view AS
SELECT
    id,
    from_account_id,
    to_account_id,
    amount,
    created_at,
    event_at,
    metadata
FROM pgledger_transfers;

CREATE OR REPLACE VIEW pgledger_entries_view AS
SELECT
    e.id,
    e.account_id,
    e.transfer_id,
    e.amount,
    e.account_previous_balance,
    e.account_current_balance,
    e.account_version,
    e.created_at,
    t.event_at,
    t.metadata
FROM pgledger_entries e
INNER JOIN pgledger_transfers t ON e.transfer_id = t.id;
