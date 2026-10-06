-- Increment 001: bank pool default 8, max 400
-- Source: commit ab71aa4 on feat/pg-ledger-first-cut (also in V002 after that commit)
-- For SIT/prod writers that already ran older V001–V003 without this change.
--
-- Defaults / caps after this patch:
--   - New BANK pools created via pgledger_resolve_account: size 8
--   - pgledger_ensure_bank_pool default p_pool_size: 8
--   - Max allowed pool size: 400 (was 1024 in ensure; first-create was 400 in resolve)
--
-- Existing pools are sticky:
--   - This script only CREATE OR REPLACE FUNCTIONs — no DROP TABLE, no DELETE of BANK rows
--   - Does not shrink existing pools (resolve_account uses live shard count when present;
--     ensure_bank_pool with p_keep_existing=TRUE preserves existing size)
--
-- Run on the writer (primary), not a replica:
--   psql -v ON_ERROR_STOP=1 -f db/increments/001_bank_pool_default_8_max_400.sql
--
\set ON_ERROR_STOP on

BEGIN;

-- Creates missing BANK rows for n in 0 .. pool_size-1. Both balance signs are allowed.
-- An existing pool keeps its size when p_keep_existing is true.
-- p_balance_type is the code used in the shard business id.
-- p_balance_type_id is stored on the row. This function does not read pgledger_balance_types.
CREATE OR REPLACE FUNCTION pgledger_ensure_bank_pool(
    p_currency TEXT,
    p_balance_type TEXT,
    p_balance_type_id INT,
    p_pool_size INT DEFAULT 8,
    p_keep_existing BOOLEAN DEFAULT FALSE
)
RETURNS INT
AS $$
DECLARE
    v_currency TEXT;
    v_balance_type TEXT;
    v_prefix TEXT;
    v_size INT;
    v_max INT;
    v_existing INT;
    v_shard INT;
    v_account_id TEXT;
    v_currency_id INT;
    v_bank INT;
BEGIN
    v_currency := btrim(p_currency);
    v_balance_type := btrim(p_balance_type);
    IF v_currency IS NULL OR v_currency = '' OR v_balance_type IS NULL OR v_balance_type = ''
        OR p_balance_type_id IS NULL THEN
        RAISE EXCEPTION 'balance_type and currency are required';
    END IF;
    v_size := COALESCE(p_pool_size, 8);
    IF v_size < 1 OR v_size > 400 THEN
        RAISE EXCEPTION 'bank pool size (%) must be between 1 and 400', v_size;
    END IF;
    SELECT id INTO v_currency_id FROM pgledger_currencies WHERE code = v_currency;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'currency not found';
    END IF;
    SELECT id INTO v_bank FROM pgledger_account_classes WHERE code = 'BANK';
    IF NOT FOUND THEN
        RAISE EXCEPTION 'account_class not found';
    END IF;

    PERFORM pg_advisory_xact_lock(hashtextextended('bank:' || v_currency || ':' || v_balance_type, 0));

    v_prefix := pgledger_bank_account_id(v_currency, v_balance_type, 0);
    v_prefix := left(v_prefix, char_length(v_prefix) - 1);
    SELECT MAX(substring(account_id FROM char_length(v_prefix) + 1)::int)
    INTO v_max
    FROM pgledger_accounts
    WHERE currency_id = v_currency_id
      AND balance_type_id = p_balance_type_id
      AND account_class_id = v_bank
      AND left(account_id, char_length(v_prefix)) = v_prefix
      AND substring(account_id FROM char_length(v_prefix) + 1) ~ '^[0-9]+$';

    IF v_max IS NOT NULL THEN
        v_existing := v_max + 1;
        IF v_existing <> v_size AND NOT COALESCE(p_keep_existing, FALSE) THEN
            RAISE EXCEPTION 'bank pool size is % (balance_type=%, currency=%)',
                v_existing, v_balance_type, v_currency;
        END IF;
        IF COALESCE(p_keep_existing, FALSE) THEN
            v_size := v_existing;
        END IF;
    END IF;

    FOR v_shard IN 0..v_size - 1 LOOP
        v_account_id := pgledger_bank_account_id(v_currency, v_balance_type, v_shard);
        IF NOT EXISTS (
            SELECT 1 FROM pgledger_accounts
            WHERE account_id = v_account_id
              AND balance_type_id = p_balance_type_id
              AND currency_id = v_currency_id
        ) THEN
            INSERT INTO pgledger_accounts (
                account_id, balance_type_id, name, currency_id,
                metadata, created_at, updated_at, account_class_id
            )
            VALUES (
                v_account_id, p_balance_type_id, 'BANK ' || v_shard, v_currency_id,
                jsonb_build_object('shard', v_shard), now(), now(), v_bank
            );
        END IF;
    END LOOP;

    RETURN v_size;
END;
$$ LANGUAGE plpgsql;

-- Account id BANK is the pool for this balance type and currency.
-- The row is BANK-{currency}-{balanceType}-{hash(request_id) % pool size}.
-- Any other account id is returned unchanged. The same helper is used for every currency.
CREATE OR REPLACE FUNCTION pgledger_resolve_account(
    p_account_id TEXT,
    p_balance_type_id INT,
    p_currency TEXT,
    p_request_id TEXT
) RETURNS TEXT
AS $$
DECLARE
    v_account TEXT;
    v_currency TEXT;
    v_code TEXT;
    v_currency_id INT;
    v_bank INT;
    v_max INT;
    v_size INT;
    v_ord INT;
    v_shard_id TEXT;
BEGIN
    v_account := btrim(p_account_id);
    IF v_account IS DISTINCT FROM 'BANK' THEN
        RETURN v_account;
    END IF;
    v_currency := btrim(p_currency);
    SELECT id INTO v_currency_id FROM pgledger_currencies WHERE code = v_currency;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'currency not found';
    END IF;
    SELECT id INTO v_bank FROM pgledger_account_classes WHERE code = 'BANK';
    IF NOT FOUND THEN
        RAISE EXCEPTION 'account_class not found';
    END IF;

    SELECT MAX(substring(account_id FROM '([0-9]+)$')::int)
    INTO v_max
    FROM pgledger_accounts
    WHERE currency_id = v_currency_id
      AND balance_type_id = p_balance_type_id
      AND account_class_id = v_bank
      AND account_id ~ ('^BANK-' || v_currency || '-.+-[0-9]+$');

    IF v_max IS NULL THEN
        SELECT code INTO v_code FROM pgledger_balance_types WHERE id = p_balance_type_id;
        IF NOT FOUND THEN
            RETURN NULL;
        END IF;
        v_size := pgledger_ensure_bank_pool(v_currency, v_code, p_balance_type_id, 8, TRUE);
    ELSE
        v_size := v_max + 1;
    END IF;

    v_ord := pgledger_bank_shard(p_request_id, v_size);
    IF v_ord IS NULL THEN
        RAISE EXCEPTION 'bank shard not found';
    END IF;

    SELECT account_id INTO v_shard_id
    FROM pgledger_accounts
    WHERE currency_id = v_currency_id
      AND balance_type_id = p_balance_type_id
      AND account_class_id = v_bank
      AND account_id ~ ('^BANK-' || v_currency || '-.+-' || v_ord::text || '$')
    LIMIT 1;
    IF FOUND THEN
        RETURN v_shard_id;
    END IF;

    SELECT code INTO v_code FROM pgledger_balance_types WHERE id = p_balance_type_id;
    IF NOT FOUND THEN
        RETURN NULL;
    END IF;
    RETURN pgledger_bank_account_id(v_currency, v_code, v_ord);
END;
$$ LANGUAGE plpgsql;

COMMIT;
