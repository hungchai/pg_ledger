-- Account create and transfer functions.
-- Hot rows store registry ids. These functions still take codes, except
-- pgledger_create_transfer and pgledger_create_transfers, which take balance_type_id.
-- Those two functions do not read pgledger_balance_types.
-- Balance rows are locked in sorted internal id order, same as pgledger.

CREATE OR REPLACE FUNCTION pgledger_check_account_balance_constraints(account pgledger_accounts) RETURNS VOID AS $$
DECLARE
    v_type TEXT;
    v_allow_negative BOOLEAN;
    v_allow_positive BOOLEAN;
BEGIN
    -- Sign policy is on the balance type. BANK class may sit on either side of zero.
    SELECT bt.code,
           bt.allow_negative OR ac.code = 'BANK',
           bt.allow_positive OR ac.code = 'BANK'
    INTO v_type, v_allow_negative, v_allow_positive
    FROM pgledger_balance_types bt
    JOIN pgledger_account_classes ac ON ac.id = account.account_class_id
    WHERE bt.id = account.balance_type_id;

    IF NOT v_allow_negative AND (account.balance < 0) THEN
        RAISE EXCEPTION 'Balance type % does not allow negative balance (account id=%, name=%)',
            v_type, account.id, account.name;
    END IF;

    IF NOT v_allow_positive AND (account.balance > 0) THEN
        RAISE EXCEPTION 'Balance type % does not allow positive balance (account id=%, name=%)',
            v_type, account.id, account.name;
    END IF;
END;
$$ LANGUAGE plpgsql;

CREATE OR REPLACE FUNCTION pgledger_create_balance_type(
    p_code TEXT,
    p_name TEXT,
    p_description TEXT DEFAULT NULL,
    p_allow_negative BOOLEAN DEFAULT FALSE,
    p_allow_positive BOOLEAN DEFAULT TRUE
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

    INSERT INTO pgledger_balance_types (code, name, description, allow_negative, allow_positive, created_at, updated_at)
    VALUES (
        v_code, v_name, NULLIF(btrim(p_description), ''),
        COALESCE(p_allow_negative, FALSE), COALESCE(p_allow_positive, TRUE),
        now(), now()
    );

    RETURN QUERY
    SELECT id, code, name, description, allow_negative, allow_positive, created_at, updated_at
    FROM pgledger_balance_types_view
    WHERE code = v_code;
EXCEPTION
    WHEN unique_violation THEN
        RAISE EXCEPTION 'balance type already exists';
END;
$$ LANGUAGE plpgsql;

DROP FUNCTION IF EXISTS pgledger_create_transfers(transfer_request[], TIMESTAMPTZ, TEXT, TEXT, TEXT);
DROP FUNCTION IF EXISTS pgledger_create_transfers(TEXT, VARIADIC transfer_request[]);
DROP FUNCTION IF EXISTS pgledger_resolve_balance(TEXT, TEXT, TEXT);

DO $do$
DECLARE
    attr_type TEXT;
BEGIN
    SELECT format_type(a.atttypid, a.atttypmod) INTO attr_type
    FROM pg_type t
    JOIN pg_class c ON c.oid = t.typrelid
    JOIN pg_attribute a ON a.attrelid = c.oid AND a.attname = 'from_balance_type' AND NOT a.attisdropped
    WHERE t.typname = 'transfer_request';
    IF attr_type IS NOT NULL AND attr_type <> 'integer' THEN
        DROP TYPE transfer_request CASCADE;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_type WHERE typname = 'transfer_request') THEN
        CREATE TYPE transfer_request AS (
            from_account_id TEXT,
            from_balance_type INT,
            to_account_id TEXT,
            to_balance_type INT,
            currency TEXT,
            amount NUMERIC
        );
    END IF;
END
$do$;

DROP FUNCTION IF EXISTS pgledger_resolve_balance(TEXT, INT, INT);

CREATE OR REPLACE FUNCTION pgledger_resolve_balance(
    p_account_id TEXT,
    p_balance_type_id INT,
    p_currency_id INT,
    p_auto_create BOOLEAN DEFAULT TRUE
) RETURNS pgledger_accounts
AS $$
DECLARE
    found_row pgledger_accounts;
    asked_code TEXT;
    v_class_id INT;
BEGIN
    SELECT * INTO found_row
    FROM pgledger_accounts
    WHERE account_id = p_account_id
      AND balance_type_id = p_balance_type_id
      AND currency_id = p_currency_id;
    IF FOUND THEN
        IF found_row.deleted THEN
            SELECT code INTO asked_code FROM pgledger_currencies WHERE id = p_currency_id;
            RAISE EXCEPTION 'Account is deleted (account_id=%, balance_type=%, currency=%)',
                p_account_id, p_balance_type_id, asked_code;
        END IF;
        RETURN found_row;
    END IF;

    IF NOT COALESCE(p_auto_create, TRUE) THEN
        SELECT code INTO asked_code FROM pgledger_currencies WHERE id = p_currency_id;
        RAISE EXCEPTION 'Account not found (account_id=%, balance_type=%, currency=%)',
            p_account_id, p_balance_type_id, asked_code;
    END IF;

    -- Auto-create the missing balance row so deposits/postings do not fail when
    -- the (account_id, balance_type, currency) triple was never registered.
    -- New account ids get class CLIENT; existing ids keep their class.
    SELECT id INTO v_class_id FROM pgledger_account_classes WHERE code = 'CLIENT';
    INSERT INTO pgledger_accounts (
        account_id, balance_type_id, name, currency_id,
        metadata, created_at, updated_at, account_class_id
    )
    SELECT p_account_id, p_balance_type_id, p_account_id, p_currency_id,
           NULL, now(), now(), COALESCE(
               (SELECT a.account_class_id FROM pgledger_accounts a
                WHERE a.account_id = p_account_id
                LIMIT 1), v_class_id)
    ON CONFLICT (account_id, balance_type_id, currency_id) DO NOTHING;

    SELECT * INTO found_row
    FROM pgledger_accounts
    WHERE account_id = p_account_id
      AND balance_type_id = p_balance_type_id
      AND currency_id = p_currency_id;
    IF FOUND THEN
        RETURN found_row;
    END IF;

    SELECT code INTO asked_code FROM pgledger_currencies WHERE id = p_currency_id;
    RAISE EXCEPTION 'Account not found (account_id=%, balance_type=%, currency=%)',
        p_account_id, p_balance_type_id, asked_code;
END;
$$ LANGUAGE plpgsql;

DROP FUNCTION IF EXISTS pgledger_create_account(TEXT, TEXT, TEXT, TEXT, BOOLEAN, BOOLEAN, JSONB);
DROP FUNCTION IF EXISTS pgledger_create_account(TEXT, TEXT, TEXT, TEXT, BOOLEAN, BOOLEAN, JSONB, TEXT);

CREATE OR REPLACE FUNCTION pgledger_create_account(
    p_account_id TEXT,
    p_balance_type TEXT,
    p_name TEXT,
    p_currency TEXT,
    p_metadata JSONB DEFAULT NULL,
    p_account_class TEXT DEFAULT 'CLIENT'
)
RETURNS SETOF pgledger_accounts_view
AS $$
DECLARE
    v_name TEXT;
    v_class TEXT;
    v_balance_type_id INT;
    v_currency_id INT;
    v_class_id INT;
    v_id TEXT;
BEGIN
    IF p_account_id IS NULL OR btrim(p_account_id) = ''
        OR p_balance_type IS NULL OR btrim(p_balance_type) = ''
        OR p_currency IS NULL OR btrim(p_currency) = '' THEN
        RAISE EXCEPTION 'account_id, balance_type, and currency are required';
    END IF;

    -- Sign policy lives on pgledger_balance_types; accounts do not store allow flags.
    SELECT id INTO v_balance_type_id
    FROM pgledger_balance_types
    WHERE code = btrim(p_balance_type);
    IF NOT FOUND THEN
        RAISE EXCEPTION 'balance type not found: %', btrim(p_balance_type);
    END IF;
    SELECT id INTO v_currency_id
    FROM pgledger_currencies
    WHERE code = btrim(p_currency);
    IF NOT FOUND THEN
        RAISE EXCEPTION 'currency not found';
    END IF;

    IF p_account_class IS NULL OR btrim(p_account_class) = '' THEN
        v_class := 'CLIENT';
    ELSE
        v_class := upper(btrim(p_account_class));
    END IF;
    SELECT id INTO v_class_id FROM pgledger_account_classes WHERE code = v_class;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'account_class not found';
    END IF;

    v_name := COALESCE(NULLIF(btrim(p_name), ''), p_account_id);

    INSERT INTO pgledger_accounts (
        account_id, balance_type_id, name, currency_id,
        metadata, created_at, updated_at, account_class_id
    )
    VALUES (
        btrim(p_account_id), v_balance_type_id, v_name, v_currency_id,
        p_metadata, now(), now(), v_class_id
    )
    RETURNING id INTO v_id;

    RETURN QUERY
    SELECT * FROM pgledger_accounts_view WHERE id = v_id;
EXCEPTION
    WHEN unique_violation THEN
        RAISE EXCEPTION 'account already exists';
END;
$$ LANGUAGE plpgsql;

DROP FUNCTION IF EXISTS pgledger_post_cash(TEXT, TEXT, TEXT, TEXT, TEXT, NUMERIC, TIMESTAMPTZ, JSONB, INT);
DROP FUNCTION IF EXISTS pgledger_post_cash(TEXT, TEXT, TEXT, TEXT, TEXT, NUMERIC, TIMESTAMPTZ, TEXT, INT);
DROP FUNCTION IF EXISTS pgledger_create_transfer(TEXT, TEXT, TEXT, TEXT, TEXT, NUMERIC, TIMESTAMPTZ, JSONB);
DROP FUNCTION IF EXISTS pgledger_create_transfer(TEXT, TEXT, TEXT, TEXT, TEXT, NUMERIC, TIMESTAMPTZ, TEXT, TEXT, TEXT);
DROP FUNCTION IF EXISTS pgledger_create_transfers(VARIADIC transfer_request[]);
DROP FUNCTION IF EXISTS pgledger_create_transfers(transfer_request[], TIMESTAMPTZ, JSONB);
DROP FUNCTION IF EXISTS pgledger_create_transfers(transfer_request[], TIMESTAMPTZ, TEXT, TEXT, TEXT);
DROP FUNCTION IF EXISTS pgledger_create_transfers(transfer_request[], TIMESTAMPTZ, TEXT, TEXT, TEXT, BOOLEAN);

-- Balance type arguments are ids. This function does not read pgledger_balance_types.
CREATE OR REPLACE FUNCTION pgledger_create_transfers(
    p_transfer_requests transfer_request[],
    p_event_at TIMESTAMPTZ DEFAULT NULL,
    p_biz_reference TEXT DEFAULT NULL,
    p_request_id TEXT DEFAULT NULL,
    p_biz_type TEXT DEFAULT 'TRANSFER',
    p_auto_create BOOLEAN DEFAULT TRUE
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
    v_request_id TEXT;
    v_biz_type TEXT;
    v_biz_type_id INT;
    v_biz_reference TEXT;
    v_existing_amount NUMERIC;
    v_existing_biz INT;
    v_from_account TEXT;
    v_to_account TEXT;
    v_from_type INT;
    v_to_type INT;
    v_from_currency INT;
    v_to_currency INT;
    v_existing_ids TEXT[];
    v_currency_ids INT[] := '{}';
    v_currency_id INT;
    v_count INT;
    v_ord INT;
BEGIN
    IF p_transfer_requests IS NULL THEN
        RETURN;
    END IF;

    v_biz_reference := NULLIF(btrim(p_biz_reference), '');
    v_biz_type := upper(btrim(COALESCE(p_biz_type, 'TRANSFER')));
    SELECT id INTO v_biz_type_id FROM pgledger_biz_types WHERE code = v_biz_type;
    IF v_biz_type IS NULL OR v_biz_type = '' OR NOT FOUND THEN
        RAISE EXCEPTION 'biz type not found';
    END IF;
    v_request_id := NULLIF(btrim(p_request_id), '');
    IF v_request_id IS NULL THEN
        RAISE EXCEPTION 'request_id is required';
    END IF;
    IF COALESCE(array_length(p_transfer_requests, 1), 0) < 1 THEN
        RETURN;
    END IF;

    -- Every leg of this call stores the same request_id. A repeat returns those rows.
    -- Account id BANK is the pool. It becomes BANK-{currency}-{balanceType}-{n}.
    PERFORM pg_advisory_xact_lock(hashtextextended(v_request_id, 0));
    FOR v_ord IN 1 .. array_length(p_transfer_requests, 1) LOOP
        req := p_transfer_requests[v_ord];
        SELECT id INTO v_currency_id FROM pgledger_currencies WHERE code = btrim(req.currency);
        IF btrim(req.currency) IS NULL OR btrim(req.currency) = '' OR NOT FOUND THEN
            RAISE EXCEPTION 'currency not found';
        END IF;
        v_currency_ids := array_append(v_currency_ids, v_currency_id);
        req.from_account_id := pgledger_resolve_account(
            req.from_account_id, req.from_balance_type, btrim(req.currency), v_request_id);
        req.to_account_id := pgledger_resolve_account(
            req.to_account_id, req.to_balance_type, btrim(req.currency), v_request_id);
        p_transfer_requests[v_ord] := req;
    END LOOP;
    -- Shard-local idempotency check: one request lives on exactly one shard,
    -- so dedup reads that shard's index instead of scanning the UNION view.
    EXECUTE format(
        'SELECT COALESCE(array_agg(id ORDER BY id), ARRAY[]::TEXT[]) FROM pgledger_transfers_%s WHERE request_id = $1',
        pgledger_request_shard(v_request_id))
    INTO v_existing_ids
    USING v_request_id;
    v_count := COALESCE(array_length(v_existing_ids, 1), 0);
    IF v_count > 0 THEN
        IF v_count <> array_length(p_transfer_requests, 1) THEN
            RAISE EXCEPTION 'request id already used';
        END IF;
        FOR v_ord IN 1 .. v_count LOOP
            req := p_transfer_requests[v_ord];
            SELECT t.amount, t.biz_type_id,
                   fa.account_id, fa.balance_type_id, fa.currency_id,
                   ta.account_id, ta.balance_type_id, ta.currency_id
            INTO v_existing_amount, v_existing_biz,
                 v_from_account, v_from_type, v_from_currency,
                 v_to_account, v_to_type, v_to_currency
            FROM pgledger_transfers t
            JOIN pgledger_accounts fa ON fa.id = t.from_account_id
            JOIN pgledger_accounts ta ON ta.id = t.to_account_id
            WHERE t.id = v_existing_ids[v_ord] AND t.request_id = v_request_id;
            IF v_existing_amount IS DISTINCT FROM req.amount
                OR v_existing_biz IS DISTINCT FROM v_biz_type_id
                OR v_from_account IS DISTINCT FROM btrim(req.from_account_id)
                OR v_from_type IS DISTINCT FROM req.from_balance_type
                OR v_from_currency IS DISTINCT FROM v_currency_ids[v_ord]
                OR v_to_account IS DISTINCT FROM btrim(req.to_account_id)
                OR v_to_type IS DISTINCT FROM req.to_balance_type
                OR v_to_currency IS DISTINCT FROM v_currency_ids[v_ord] THEN
                RAISE EXCEPTION 'request id already used';
            END IF;
        END LOOP;
        RETURN QUERY
        SELECT * FROM pgledger_transfers_view
        WHERE id = ANY(v_existing_ids)
        ORDER BY id;
        RETURN;
    END IF;

    FOR v_ord IN 1 .. array_length(p_transfer_requests, 1) LOOP
        req := p_transfer_requests[v_ord];
        IF req.amount IS NULL OR req.amount <= 0 THEN
            RAISE EXCEPTION 'Amount (%) must be positive', req.amount;
        END IF;

        from_row := pgledger_resolve_balance(
            req.from_account_id, req.from_balance_type, v_currency_ids[v_ord], COALESCE(p_auto_create, TRUE));
        to_row := pgledger_resolve_balance(
            req.to_account_id, req.to_balance_type, v_currency_ids[v_ord], COALESCE(p_auto_create, TRUE));

        IF from_row.id = to_row.id THEN
            RAISE EXCEPTION 'Cannot transfer to the same account (id=%)', from_row.id;
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

    FOR v_ord IN 1 .. array_length(p_transfer_requests, 1) LOOP
        req := p_transfer_requests[v_ord];
        SELECT * INTO from_row
        FROM pgledger_accounts
        WHERE account_id = req.from_account_id
          AND balance_type_id = req.from_balance_type
          AND currency_id = v_currency_ids[v_ord];
        IF NOT FOUND THEN
            IF NOT COALESCE(p_auto_create, TRUE) THEN
                RAISE EXCEPTION 'Account not found (account_id=%, balance_type=%, currency=%)',
                    req.from_account_id, req.from_balance_type, btrim(req.currency);
            END IF;
            from_row := pgledger_resolve_balance(
                req.from_account_id, req.from_balance_type, v_currency_ids[v_ord]);
        END IF;

        SELECT * INTO to_row
        FROM pgledger_accounts
        WHERE account_id = req.to_account_id
          AND balance_type_id = req.to_balance_type
          AND currency_id = v_currency_ids[v_ord];
        IF NOT FOUND THEN
            IF NOT COALESCE(p_auto_create, TRUE) THEN
                RAISE EXCEPTION 'Account not found (account_id=%, balance_type=%, currency=%)',
                    req.to_account_id, req.to_balance_type, btrim(req.currency);
            END IF;
            to_row := pgledger_resolve_balance(
                req.to_account_id, req.to_balance_type, v_currency_ids[v_ord]);
        END IF;

        IF from_row.id = to_row.id THEN
            RAISE EXCEPTION 'Cannot transfer to the same account (id=%)', from_row.id;
        END IF;

        IF from_row.deleted THEN
            RAISE EXCEPTION 'Account is deleted (account_id=%, balance_type=%, currency=%)',
                from_row.account_id, req.from_balance_type, btrim(req.currency);
        END IF;
        IF to_row.deleted THEN
            RAISE EXCEPTION 'Account is deleted (account_id=%, balance_type=%, currency=%)',
                to_row.account_id, req.to_balance_type, btrim(req.currency);
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

        -- Routed inserts: request's shard for the transfer, same shard for both
        -- entries so a request's rows stay colocated for shard-local reads.
        EXECUTE format(
            'INSERT INTO pgledger_transfers_%1$s (
                from_account_id, to_account_id, amount, created_at, event_at, request_id, biz_type_id, biz_reference
            )
            VALUES ($1, $2, $3, now(), $4, $5, $6, $7)
            RETURNING id',
            pgledger_request_shard(v_request_id))
        INTO transfer_id
        USING from_row.id, to_row.id, req.amount, coalesce(p_event_at, now()),
              v_request_id, v_biz_type_id, v_biz_reference;

        transfer_ids := array_append(transfer_ids, transfer_id);

        EXECUTE format(
            'INSERT INTO pgledger_entries_%1$s (
                account_id, transfer_id, amount, account_previous_balance, account_current_balance, account_version, created_at
            )
            VALUES ($1, $2, $3, $4, $5, $6, now())',
            pgledger_request_shard(v_request_id))
        USING from_row.id, transfer_id, -req.amount,
              from_row.balance + req.amount, from_row.balance, from_row.version;

        EXECUTE format(
            'INSERT INTO pgledger_entries_%1$s (
                account_id, transfer_id, amount, account_previous_balance, account_current_balance, account_version, created_at
            )
            VALUES ($1, $2, $3, $4, $5, $6, now())',
            pgledger_request_shard(v_request_id))
        USING to_row.id, transfer_id, req.amount,
              to_row.balance - req.amount, to_row.balance, to_row.version;
    END LOOP;
    RETURN QUERY
    SELECT *
    FROM pgledger_transfers_view
    WHERE id = ANY(transfer_ids)
      AND request_id = v_request_id
    ORDER BY id;
END;
$$ LANGUAGE plpgsql;

DROP FUNCTION IF EXISTS pgledger_create_transfer(TEXT, TEXT, TEXT, TEXT, TEXT, NUMERIC, TIMESTAMPTZ, TEXT, TEXT, TEXT);

CREATE OR REPLACE FUNCTION pgledger_create_transfer(
    p_from_account_id TEXT,
    p_from_balance_type INT,
    p_to_account_id TEXT,
    p_to_balance_type INT,
    p_currency TEXT,
    p_amount NUMERIC,
    p_event_at TIMESTAMPTZ DEFAULT NULL,
    p_biz_reference TEXT DEFAULT NULL,
    p_request_id TEXT DEFAULT NULL,
    p_biz_type TEXT DEFAULT 'TRANSFER',
    p_auto_create BOOLEAN DEFAULT TRUE
)
RETURNS SETOF pgledger_transfers_view
AS $$
BEGIN
    IF NULLIF(btrim(p_request_id), '') IS NULL THEN
        RAISE EXCEPTION 'request_id is required';
    END IF;
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
        p_biz_reference => p_biz_reference,
        p_request_id => p_request_id,
        p_biz_type => p_biz_type,
        p_auto_create => p_auto_create
    );
END;
$$ LANGUAGE plpgsql;

CREATE OR REPLACE FUNCTION pgledger_create_transfers(
    p_request_id TEXT,
    VARIADIC transfer_requests transfer_request[]
)
RETURNS SETOF pgledger_transfers_view
AS $$
BEGIN
    RETURN QUERY
    SELECT * FROM pgledger_create_transfers(
        p_transfer_requests => transfer_requests,
        p_request_id => p_request_id
    );
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

-- Shard account id is BANK-{currency}-{balanceType}-{n}.
CREATE OR REPLACE FUNCTION pgledger_bank_account_id(
    p_currency TEXT,
    p_balance_type TEXT,
    p_shard INT
) RETURNS TEXT
AS $$
    SELECT 'BANK-' || p_currency || '-' || p_balance_type || '-' || p_shard::text
$$ LANGUAGE sql IMMUTABLE;

-- Creates missing BANK rows for n in 0 .. pool_size-1. Both balance signs are allowed.
-- An existing pool keeps its size when p_keep_existing is true.
DROP FUNCTION IF EXISTS pgledger_ensure_bank_pool(TEXT, TEXT, INT, BOOLEAN);

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
    IF v_size < 1 OR v_size > 1024 THEN
        RAISE EXCEPTION 'bank pool size (%) must be between 1 and 1024', v_size;
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
DROP FUNCTION IF EXISTS pgledger_resolve_account(TEXT, TEXT, TEXT, TEXT);

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
        v_size := pgledger_ensure_bank_pool(v_currency, v_code, p_balance_type_id, 400, TRUE);
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

-- Sum of BANK rows for this balance type and currency, including a soft-deleted row that still holds a balance.
CREATE OR REPLACE FUNCTION pgledger_bank_position(p_balance_type TEXT, p_currency TEXT)
RETURNS NUMERIC
AS $$
    SELECT COALESCE(SUM(a.balance), 0)
    FROM pgledger_accounts a
    JOIN pgledger_account_classes ac ON ac.id = a.account_class_id AND ac.code = 'BANK'
    JOIN pgledger_balance_types bt ON bt.id = a.balance_type_id AND bt.code = btrim(p_balance_type)
    JOIN pgledger_currencies c ON c.id = a.currency_id AND c.code = btrim(p_currency)
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

    SELECT a.id INTO v_id
    FROM pgledger_accounts a
    JOIN pgledger_balance_types bt ON bt.id = a.balance_type_id
    JOIN pgledger_currencies c ON c.id = a.currency_id
    WHERE a.account_id = btrim(p_account_id)
      AND bt.code = btrim(p_balance_type)
      AND c.code = btrim(p_currency)
    FOR UPDATE OF a;
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
    SELECT * FROM pgledger_accounts_view WHERE id = v_id;
END;
$$ LANGUAGE plpgsql;

