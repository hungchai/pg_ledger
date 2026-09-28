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
        RETURN found_row;
    END IF;

    SELECT currency INTO other_currency
    FROM pgledger_accounts
    WHERE account_id = p_account_id
      AND balance_type = p_balance_type
    ORDER BY currency
    LIMIT 1;
    IF FOUND THEN
        RAISE EXCEPTION 'Cannot transfer between different currencies (% and %)', other_currency, p_currency;
    END IF;

    RAISE EXCEPTION 'Account not found (account_id=%, balance_type=%, currency=%)',
        p_account_id, p_balance_type, p_currency;
END;
$$ LANGUAGE plpgsql;

CREATE OR REPLACE FUNCTION pgledger_create_account(
    p_account_id TEXT,
    p_balance_type TEXT,
    p_name TEXT,
    p_currency TEXT,
    p_allow_negative_balance BOOLEAN DEFAULT TRUE,
    p_allow_positive_balance BOOLEAN DEFAULT TRUE,
    p_metadata JSONB DEFAULT NULL
)
RETURNS SETOF pgledger_accounts_view
AS $$
DECLARE
    v_name TEXT;
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

    v_name := COALESCE(NULLIF(btrim(p_name), ''), p_account_id);

    RETURN QUERY
    INSERT INTO pgledger_accounts (
        account_id, balance_type, name, currency,
        allow_negative_balance, allow_positive_balance, metadata, created_at, updated_at
    )
    VALUES (
        p_account_id, p_balance_type, v_name, p_currency,
        COALESCE(p_allow_negative_balance, TRUE),
        COALESCE(p_allow_positive_balance, TRUE),
        p_metadata, now(), now()
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
