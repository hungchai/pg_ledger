-- Balance-type registry, account create, and transfer functions.
-- A transfer names two balance rows (account_id, balance_type, currency).
-- Balance types may differ (AVAILABLE to LOCKED). Currencies must match.
-- Balance rows are locked in sorted internal id order, same as pgledger.

CREATE OR REPLACE FUNCTION pgledger_check_account_balance_constraints(account pgledger_accounts) RETURNS VOID AS $$
BEGIN
    IF NOT account.allow_negative_balance AND (account.balance < 0) THEN
        RAISE EXCEPTION 'Account (id=%, name=%) does not allow negative balance', account.id, account.name;
    END IF;

    IF NOT account.allow_positive_balance AND (account.balance > 0) THEN
        RAISE EXCEPTION 'Account (id=%, name=%) does not allow positive balance', account.id, account.name;
    END IF;
END;
$$ LANGUAGE plpgsql;

CREATE OR REPLACE FUNCTION pgledger_create_balance_type(
    p_code TEXT,
    p_name TEXT,
    p_description TEXT DEFAULT NULL
)
RETURNS SETOF pgledger_balance_types_view
AS $$
DECLARE
    v_code TEXT;
    v_name TEXT;
BEGIN
    IF p_code IS NULL OR btrim(p_code) = '' THEN
        RAISE EXCEPTION 'balance_type code is required';
    END IF;

    v_code := btrim(p_code);
    v_name := COALESCE(NULLIF(btrim(p_name), ''), v_code);

    RETURN QUERY
    INSERT INTO pgledger_balance_types (code, name, description, created_at, updated_at)
    VALUES (v_code, v_name, NULLIF(btrim(p_description), ''), now(), now())
    RETURNING *;
EXCEPTION
    WHEN unique_violation THEN
        RAISE EXCEPTION 'balance type already exists';
END;
$$ LANGUAGE plpgsql;

DO $do$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_type WHERE typname = 'transfer_request') THEN
        CREATE TYPE transfer_request AS (
            from_account_id TEXT,
            from_balance_type TEXT,
            to_account_id TEXT,
            to_balance_type TEXT,
            currency TEXT,
            amount NUMERIC
        );
    END IF;
END
$do$;

CREATE OR REPLACE FUNCTION pgledger_resolve_balance(
    p_account_id TEXT,
    p_balance_type TEXT,
    p_currency TEXT
) RETURNS pgledger_accounts
AS $$
DECLARE
    found_row pgledger_accounts;
    other_currency TEXT;
BEGIN
    SELECT * INTO found_row
    FROM pgledger_accounts
    WHERE account_id = p_account_id
      AND balance_type = p_balance_type
      AND currency = p_currency;
    IF FOUND THEN
        IF found_row.deleted THEN
            RAISE EXCEPTION 'Account is deleted (account_id=%, balance_type=%, currency=%)',
                p_account_id, p_balance_type, p_currency;
        END IF;
        RETURN found_row;
    END IF;

    SELECT currency INTO other_currency
    FROM pgledger_accounts
    WHERE account_id = p_account_id
      AND balance_type = p_balance_type
      AND NOT deleted
    ORDER BY currency
    LIMIT 1;
    IF FOUND THEN
        RAISE EXCEPTION 'Cannot transfer between different currencies (% and %)', other_currency, p_currency;
    END IF;

    RAISE EXCEPTION 'Account not found (account_id=%, balance_type=%, currency=%)',
        p_account_id, p_balance_type, p_currency;
END;
$$ LANGUAGE plpgsql;

DROP FUNCTION IF EXISTS pgledger_create_account(TEXT, TEXT, TEXT, TEXT, BOOLEAN, BOOLEAN, JSONB);

CREATE OR REPLACE FUNCTION pgledger_create_account(
    p_account_id TEXT,
    p_balance_type TEXT,
    p_name TEXT,
    p_currency TEXT,
    p_allow_negative_balance BOOLEAN DEFAULT TRUE,
    p_allow_positive_balance BOOLEAN DEFAULT TRUE,
    p_metadata JSONB DEFAULT NULL,
    p_account_class TEXT DEFAULT 'CLIENT'
)
RETURNS SETOF pgledger_accounts_view
AS $$
DECLARE
    v_name TEXT;
    v_class TEXT;
    v_allow_negative BOOLEAN;
    v_allow_positive BOOLEAN;
BEGIN
    IF p_account_id IS NULL OR btrim(p_account_id) = ''
        OR p_balance_type IS NULL OR btrim(p_balance_type) = ''
        OR p_currency IS NULL OR btrim(p_currency) = '' THEN
        RAISE EXCEPTION 'account_id, balance_type, and currency are required';
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pgledger_balance_types WHERE code = p_balance_type
    ) THEN
        RAISE EXCEPTION 'balance type not found: %', p_balance_type;
    END IF;

    IF p_account_class IS NULL OR btrim(p_account_class) = '' THEN
        v_class := 'CLIENT';
    ELSE
        v_class := upper(btrim(p_account_class));
    END IF;
    IF v_class NOT IN ('CLIENT', 'COMPANY', 'BANK', 'NOSTRO', 'SUSPENSE', 'CONTROL') THEN
        RAISE EXCEPTION 'account_class must be CLIENT, COMPANY, BANK, NOSTRO, SUSPENSE, or CONTROL';
    END IF;

    v_name := COALESCE(NULLIF(btrim(p_name), ''), p_account_id);
    v_allow_negative := COALESCE(p_allow_negative_balance, TRUE);
    v_allow_positive := COALESCE(p_allow_positive_balance, TRUE);
    -- BANK is the deposit counterparty and may sit on either side of zero.
    IF v_class = 'BANK' THEN
        v_allow_negative := TRUE;
        v_allow_positive := TRUE;
    END IF;

    RETURN QUERY
    INSERT INTO pgledger_accounts (
        account_id, balance_type, name, currency,
        allow_negative_balance, allow_positive_balance, metadata,
        created_at, updated_at, account_class
    )
    VALUES (
        p_account_id, p_balance_type, v_name, p_currency,
        v_allow_negative, v_allow_positive, p_metadata, now(), now(), v_class
    )
    RETURNING *;
EXCEPTION
    WHEN unique_violation THEN
        RAISE EXCEPTION 'account already exists';
END;
$$ LANGUAGE plpgsql;

CREATE OR REPLACE FUNCTION pgledger_create_transfers(
    p_transfer_requests transfer_request[],
    p_event_at TIMESTAMPTZ DEFAULT NULL,
    p_metadata JSONB DEFAULT NULL
)
RETURNS SETOF pgledger_transfers_view
AS $$
DECLARE
    req transfer_request;
    from_row pgledger_accounts;
    to_row pgledger_accounts;
    all_ids TEXT[] := '{}';
    locked_id TEXT;
    transfer_id TEXT;
    transfer_ids TEXT[] := '{}';
BEGIN
    IF p_transfer_requests IS NULL THEN
        RETURN;
    END IF;

    FOREACH req IN ARRAY p_transfer_requests LOOP
        IF req.amount IS NULL OR req.amount <= 0 THEN
            RAISE EXCEPTION 'Amount (%) must be positive', req.amount;
        END IF;

        from_row := pgledger_resolve_balance(req.from_account_id, req.from_balance_type, req.currency);
        to_row := pgledger_resolve_balance(req.to_account_id, req.to_balance_type, req.currency);

        IF from_row.id = to_row.id THEN
            RAISE EXCEPTION 'Cannot transfer to the same account (id=%)', from_row.id;
        END IF;

        IF from_row.currency != to_row.currency THEN
            RAISE EXCEPTION 'Cannot transfer between different currencies (% and %)', from_row.currency, to_row.currency;
        END IF;

        all_ids := array_append(all_ids, from_row.id);
        all_ids := array_append(all_ids, to_row.id);
    END LOOP;

    SELECT ARRAY(SELECT DISTINCT unnest FROM unnest(all_ids) ORDER BY unnest)
    INTO all_ids;

    FOREACH locked_id IN ARRAY all_ids LOOP
        PERFORM pgledger_accounts.id
        FROM pgledger_accounts
        WHERE pgledger_accounts.id = locked_id
        FOR UPDATE;
    END LOOP;

    FOREACH req IN ARRAY p_transfer_requests LOOP
        SELECT * INTO from_row
        FROM pgledger_accounts
        WHERE account_id = req.from_account_id
          AND balance_type = req.from_balance_type
          AND currency = req.currency;
        IF NOT FOUND THEN
            RAISE EXCEPTION 'Account not found (account_id=%, balance_type=%, currency=%)',
                req.from_account_id, req.from_balance_type, req.currency;
        END IF;

        SELECT * INTO to_row
        FROM pgledger_accounts
        WHERE account_id = req.to_account_id
          AND balance_type = req.to_balance_type
          AND currency = req.currency;
        IF NOT FOUND THEN
            RAISE EXCEPTION 'Account not found (account_id=%, balance_type=%, currency=%)',
                req.to_account_id, req.to_balance_type, req.currency;
        END IF;

        IF from_row.id = to_row.id THEN
            RAISE EXCEPTION 'Cannot transfer to the same account (id=%)', from_row.id;
        END IF;

        IF from_row.deleted THEN
            RAISE EXCEPTION 'Account is deleted (account_id=%, balance_type=%, currency=%)',
                from_row.account_id, from_row.balance_type, from_row.currency;
        END IF;
        IF to_row.deleted THEN
            RAISE EXCEPTION 'Account is deleted (account_id=%, balance_type=%, currency=%)',
                to_row.account_id, to_row.balance_type, to_row.currency;
        END IF;

        UPDATE pgledger_accounts
        SET balance = balance - req.amount,
            version = version + 1,
            updated_at = now()
        WHERE pgledger_accounts.id = from_row.id
        RETURNING * INTO from_row;

        PERFORM pgledger_check_account_balance_constraints(from_row);

        UPDATE pgledger_accounts
        SET balance = balance + req.amount,
            version = version + 1,
            updated_at = now()
        WHERE pgledger_accounts.id = to_row.id
        RETURNING * INTO to_row;

        PERFORM pgledger_check_account_balance_constraints(to_row);

        IF from_row.currency != to_row.currency THEN
            RAISE EXCEPTION 'Cannot transfer between different currencies (% and %)', from_row.currency, to_row.currency;
        END IF;

        INSERT INTO pgledger_transfers (from_account_id, to_account_id, amount, created_at, event_at, metadata)
        VALUES (from_row.id, to_row.id, req.amount, now(), coalesce(p_event_at, now()), p_metadata)
        RETURNING pgledger_transfers.id INTO transfer_id;

        transfer_ids := array_append(transfer_ids, transfer_id);

        INSERT INTO pgledger_entries (
            account_id, transfer_id, amount, account_previous_balance, account_current_balance, account_version, created_at
        )
        VALUES (
            from_row.id, transfer_id, -req.amount,
            from_row.balance + req.amount, from_row.balance, from_row.version, now()
        );

        INSERT INTO pgledger_entries (
            account_id, transfer_id, amount, account_previous_balance, account_current_balance, account_version, created_at
        )
        VALUES (
            to_row.id, transfer_id, req.amount,
            to_row.balance - req.amount, to_row.balance, to_row.version, now()
        );
    END LOOP;

    RETURN QUERY
    SELECT *
    FROM pgledger_transfers_view
    WHERE id = ANY(transfer_ids)
    ORDER BY id;
END;
$$ LANGUAGE plpgsql;

CREATE OR REPLACE FUNCTION pgledger_create_transfer(
    p_from_account_id TEXT,
    p_from_balance_type TEXT,
    p_to_account_id TEXT,
    p_to_balance_type TEXT,
    p_currency TEXT,
    p_amount NUMERIC,
    p_event_at TIMESTAMPTZ DEFAULT NULL,
    p_metadata JSONB DEFAULT NULL
)
RETURNS SETOF pgledger_transfers_view
AS $$
BEGIN
    RETURN QUERY
    SELECT * FROM pgledger_create_transfers(
        p_transfer_requests => ARRAY[(
            p_from_account_id,
            p_from_balance_type,
            p_to_account_id,
            p_to_balance_type,
            p_currency,
            p_amount
        )::transfer_request],
        p_event_at => p_event_at,
        p_metadata => p_metadata
    );
END;
$$ LANGUAGE plpgsql;

CREATE OR REPLACE FUNCTION pgledger_create_transfers(VARIADIC transfer_requests transfer_request[])
RETURNS SETOF pgledger_transfers_view
AS $$
BEGIN
    RETURN QUERY
    SELECT * FROM pgledger_create_transfers(p_transfer_requests => transfer_requests);
END;
$$ LANGUAGE plpgsql;

-- 0 .. pool_size-1. The sign bit is cleared so abs() cannot overflow.
CREATE OR REPLACE FUNCTION pgledger_bank_shard(p_request_id TEXT, p_pool_size INT)
RETURNS INT
AS $$
    SELECT CASE
        WHEN p_request_id IS NULL OR p_pool_size IS NULL OR p_pool_size < 1 THEN NULL
        ELSE mod(
            (hashtextextended(p_request_id, 0) & 9223372036854775807::bigint),
            p_pool_size::bigint
        )::int
    END
$$ LANGUAGE sql IMMUTABLE;

CREATE OR REPLACE FUNCTION pgledger_ensure_bank_pool(
    p_currency TEXT,
    p_balance_type TEXT,
    p_pool_size INT DEFAULT 8,
    p_keep_existing BOOLEAN DEFAULT FALSE
)
RETURNS INT
AS $$
DECLARE
    v_currency TEXT;
    v_balance_type TEXT;
    v_size INT;
    v_existing INT;
    v_shard INT;
    v_account_id TEXT;
BEGIN
    v_currency := btrim(p_currency);
    v_balance_type := btrim(p_balance_type);
    IF v_currency IS NULL OR v_currency = '' OR v_balance_type IS NULL OR v_balance_type = '' THEN
        RAISE EXCEPTION 'balance_type and currency are required';
    END IF;
    v_size := COALESCE(p_pool_size, 8);
    IF v_size < 1 OR v_size > 1024 THEN
        RAISE EXCEPTION 'bank pool size (%) must be between 1 and 1024', v_size;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pgledger_balance_types WHERE code = v_balance_type) THEN
        RAISE EXCEPTION 'balance type not found: %', v_balance_type;
    END IF;

    SELECT pool_size INTO v_existing
    FROM pgledger_bank_pools
    WHERE currency = v_currency AND balance_type = v_balance_type;
    IF FOUND THEN
        IF v_existing <> v_size AND NOT COALESCE(p_keep_existing, FALSE) THEN
            RAISE EXCEPTION 'bank pool size is % (balance_type=%, currency=%)',
                v_existing, v_balance_type, v_currency;
        END IF;
        RETURN v_existing;
    END IF;

    INSERT INTO pgledger_bank_pools (currency, balance_type, pool_size, created_at, updated_at)
    VALUES (v_currency, v_balance_type, v_size, now(), now())
    ON CONFLICT (currency, balance_type) DO NOTHING;

    SELECT pool_size INTO v_existing
    FROM pgledger_bank_pools
    WHERE currency = v_currency AND balance_type = v_balance_type
    FOR UPDATE;
    IF v_existing <> v_size THEN
        IF COALESCE(p_keep_existing, FALSE) THEN
            RETURN v_existing;
        END IF;
        RAISE EXCEPTION 'bank pool size is % (balance_type=%, currency=%)',
            v_existing, v_balance_type, v_currency;
    END IF;

    FOR v_shard IN 0..v_existing - 1 LOOP
        v_account_id := 'bankpool.' || v_balance_type || '.' || v_currency || '.' || v_shard;
        IF NOT EXISTS (
            SELECT 1 FROM pgledger_accounts
            WHERE account_id = v_account_id
              AND balance_type = v_balance_type
              AND currency = v_currency
        ) THEN
            PERFORM pgledger_create_account(
                v_account_id, v_balance_type, 'bank shard ' || v_shard, v_currency,
                TRUE, TRUE, jsonb_build_object('shard', v_shard), 'BANK'
            );
        END IF;
        INSERT INTO pgledger_bank_shards (currency, balance_type, shard, account_id)
        VALUES (v_currency, v_balance_type, v_shard, v_account_id)
        ON CONFLICT (currency, balance_type, shard) DO NOTHING;
    END LOOP;

    RETURN v_existing;
END;
$$ LANGUAGE plpgsql;

-- Sum of every shard row, including a soft-deleted shard that still holds a balance.
CREATE OR REPLACE FUNCTION pgledger_bank_position(p_balance_type TEXT, p_currency TEXT)
RETURNS NUMERIC
AS $$
    SELECT COALESCE(SUM(a.balance), 0)
    FROM pgledger_bank_shards s
    JOIN pgledger_accounts a
      ON a.account_id = s.account_id
     AND a.balance_type = s.balance_type
     AND a.currency = s.currency
    WHERE s.balance_type = p_balance_type
      AND s.currency = p_currency
$$ LANGUAGE sql STABLE;

CREATE OR REPLACE FUNCTION pgledger_delete_account(
    p_account_id TEXT,
    p_balance_type TEXT,
    p_currency TEXT
)
RETURNS SETOF pgledger_accounts_view
AS $$
DECLARE
    v_id TEXT;
BEGIN
    IF p_account_id IS NULL OR btrim(p_account_id) = ''
        OR p_balance_type IS NULL OR btrim(p_balance_type) = ''
        OR p_currency IS NULL OR btrim(p_currency) = '' THEN
        RAISE EXCEPTION 'account_id, balance_type, and currency are required';
    END IF;

    SELECT id INTO v_id
    FROM pgledger_accounts
    WHERE account_id = btrim(p_account_id)
      AND balance_type = btrim(p_balance_type)
      AND currency = btrim(p_currency)
    FOR UPDATE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'Account not found (account_id=%, balance_type=%, currency=%)',
            p_account_id, p_balance_type, p_currency;
    END IF;

    UPDATE pgledger_accounts
    SET deleted = true,
        updated_at = now()
    WHERE id = v_id
      AND NOT deleted;

    RETURN QUERY
    SELECT
        id, account_id, balance_type, name, currency, balance, version,
        allow_negative_balance, allow_positive_balance, metadata,
        created_at, updated_at, account_class, deleted
    FROM pgledger_accounts
    WHERE id = v_id;
END;
$$ LANGUAGE plpgsql;

-- Deposit is one transfer, live bank shard -> client. Withdrawal is the reverse.
-- The shard is hash(request_id) among rows with deleted = false, then
-- pgledger_create_transfer locks that bank id and the client id in sorted order
-- (WHERE id = chosen FOR UPDATE). The picker does not skip a locked shard and
-- does not take the first pool row. A repeated request_id returns the original transfer.
CREATE OR REPLACE FUNCTION pgledger_post_cash(
    p_request_id TEXT,
    p_direction TEXT,
    p_client_account_id TEXT,
    p_balance_type TEXT,
    p_currency TEXT,
    p_amount NUMERIC,
    p_event_at TIMESTAMPTZ DEFAULT NULL,
    p_metadata JSONB DEFAULT NULL,
    p_pool_size INT DEFAULT 8
)
RETURNS SETOF pgledger_transfers_view
AS $$
DECLARE
    v_request_id TEXT;
    v_direction TEXT;
    v_client TEXT;
    v_balance_type TEXT;
    v_currency TEXT;
    v_pool_size INT;
    v_active INT;
    v_ord INT;
    v_bank_account_id TEXT;
    v_physical_shard INT;
    v_from TEXT;
    v_to TEXT;
    v_existing pgledger_cash_requests;
    v_transfer_id TEXT;
    v_meta JSONB;
BEGIN
    v_request_id := btrim(p_request_id);
    IF v_request_id IS NULL OR v_request_id = '' THEN
        RAISE EXCEPTION 'request_id is required';
    END IF;
    v_direction := upper(btrim(p_direction));
    IF v_direction IS NULL OR v_direction NOT IN ('DEPOSIT', 'WITHDRAWAL') THEN
        RAISE EXCEPTION 'direction must be DEPOSIT or WITHDRAWAL';
    END IF;
    IF p_amount IS NULL OR p_amount <= 0 THEN
        RAISE EXCEPTION 'Amount (%) must be positive', p_amount;
    END IF;
    v_client := btrim(p_client_account_id);
    v_balance_type := btrim(p_balance_type);
    v_currency := btrim(p_currency);
    IF v_client IS NULL OR v_client = '' OR v_balance_type IS NULL OR v_balance_type = ''
        OR v_currency IS NULL OR v_currency = '' THEN
        RAISE EXCEPTION 'account_id, balance_type, and currency are required';
    END IF;

    -- Same request id waits here until the in-flight post commits or rolls back.
    PERFORM pg_advisory_xact_lock(hashtextextended(v_request_id, 0));

    SELECT * INTO v_existing
    FROM pgledger_cash_requests
    WHERE request_id = v_request_id;
    IF FOUND THEN
        IF v_existing.direction <> v_direction
            OR v_existing.client_account_id <> v_client
            OR v_existing.balance_type <> v_balance_type
            OR v_existing.currency <> v_currency
            OR v_existing.amount <> p_amount THEN
            RAISE EXCEPTION 'request id already used';
        END IF;
        RETURN QUERY
        SELECT * FROM pgledger_transfers_view WHERE id = v_existing.transfer_id;
        RETURN;
    END IF;

    SELECT pool_size INTO v_pool_size
    FROM pgledger_bank_pools
    WHERE currency = v_currency AND balance_type = v_balance_type;
    IF NOT FOUND THEN
        v_pool_size := pgledger_ensure_bank_pool(v_currency, v_balance_type, COALESCE(p_pool_size, 8), TRUE);
    END IF;

    SELECT count(*) INTO v_active
    FROM pgledger_bank_shards s
    JOIN pgledger_accounts a
      ON a.account_id = s.account_id
     AND a.balance_type = s.balance_type
     AND a.currency = s.currency
    WHERE s.currency = v_currency
      AND s.balance_type = v_balance_type
      AND NOT a.deleted;
    IF v_active < 1 THEN
        RAISE EXCEPTION 'bank pool has no active shard (balance_type=%, currency=%)',
            v_balance_type, v_currency;
    END IF;

    v_ord := pgledger_bank_shard(v_request_id, v_active);

    -- One live shard, by ordinal. The row lock happens later, inside the transfer.
    SELECT picked.account_id, picked.shard
    INTO v_bank_account_id, v_physical_shard
    FROM (
        SELECT s.account_id, s.shard,
               (row_number() OVER (ORDER BY s.shard) - 1)::int AS ord
        FROM pgledger_bank_shards s
        JOIN pgledger_accounts a
          ON a.account_id = s.account_id
         AND a.balance_type = s.balance_type
         AND a.currency = s.currency
        WHERE s.currency = v_currency
          AND s.balance_type = v_balance_type
          AND NOT a.deleted
    ) picked
    WHERE picked.ord = v_ord;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'bank shard not found (balance_type=%, currency=%, shard=%)',
            v_balance_type, v_currency, v_ord;
    END IF;

    IF v_direction = 'DEPOSIT' THEN
        v_from := v_bank_account_id;
        v_to := v_client;
    ELSE
        v_from := v_client;
        v_to := v_bank_account_id;
    END IF;

    v_meta := COALESCE(p_metadata, '{}'::jsonb) || jsonb_build_object(
        'requestId', v_request_id,
        'direction', v_direction,
        'bankShard', v_physical_shard
    );

    SELECT id INTO v_transfer_id
    FROM pgledger_create_transfer(
        v_from, v_balance_type, v_to, v_balance_type, v_currency, p_amount, p_event_at, v_meta
    );

    INSERT INTO pgledger_cash_requests (
        request_id, transfer_id, direction, client_account_id, balance_type, currency, amount, created_at
    ) VALUES (
        v_request_id, v_transfer_id, v_direction, v_client, v_balance_type, v_currency, p_amount, now()
    );

    RETURN QUERY
    SELECT * FROM pgledger_transfers_view WHERE id = v_transfer_id;
END;
$$ LANGUAGE plpgsql;
