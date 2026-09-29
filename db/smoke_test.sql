-- Run on the writer only. The reader rejects writes.
--   docker compose exec -T writer psql -U pgledger -d pgledger -v ON_ERROR_STOP=1 < db/smoke_test.sql
-- Uses SMOKE_* rows only and deletes them first, so a second run still works.
-- ROLLBACK clears a session left aborted by the previous error (SQLSTATE 25P02).

ROLLBACK;

DO $$
DECLARE
    available_balance NUMERIC;
    locked_balance NUMERIC;
    company_balance NUMERIC;
    bank_balance NUMERIC;
    available_version BIGINT;
    entry_count INT;
    transfer_count INT;
    bad_sum INT;
    smoke_ids TEXT[];
    deposit_id TEXT;
    replay_id TEXT;
    withdrawal_id TEXT;
    deposit_biz TEXT;
    deposit_request TEXT;
    deposit_ref TEXT;
    withdrawal_biz TEXT;
    deposit_shard INT;
    deposit_from TEXT;
BEGIN
    SELECT COALESCE(array_agg(id), ARRAY[]::TEXT[])
    INTO smoke_ids
    FROM pgledger_accounts
    WHERE account_id IN ('SMOKE_CLIENT', 'SMOKE_COMPANY')
       OR (
            account_class = 'BANK'
            AND currency = 'USD'
            AND balance_type IN ('SMOKE_AVAILABLE', 'SMOKE_LOCKED')
       );

    DELETE FROM pgledger_entries WHERE account_id = ANY(smoke_ids);
    DELETE FROM pgledger_transfers
    WHERE from_account_id = ANY(smoke_ids) OR to_account_id = ANY(smoke_ids);
    DELETE FROM pgledger_accounts WHERE id = ANY(smoke_ids);
    DELETE FROM pgledger_balance_types WHERE code IN ('SMOKE_AVAILABLE', 'SMOKE_LOCKED');

    PERFORM * FROM pgledger_create_balance_type('SMOKE_AVAILABLE', 'Available', NULL);
    PERFORM * FROM pgledger_create_balance_type('SMOKE_LOCKED', 'Locked', NULL);

    BEGIN
        PERFORM * FROM pgledger_create_balance_type('SMOKE_AVAILABLE', 'dup', NULL);
        RAISE EXCEPTION 'expected duplicate balance type to fail';
    EXCEPTION
        WHEN OTHERS THEN
            IF SQLERRM NOT LIKE '%balance type already exists%' THEN
                RAISE;
            END IF;
    END;

    PERFORM * FROM pgledger_create_account('SMOKE_CLIENT', 'SMOKE_AVAILABLE', 'Client', 'USD', FALSE, TRUE, NULL);
    PERFORM * FROM pgledger_create_account('SMOKE_CLIENT', 'SMOKE_LOCKED', 'Client locked', 'USD', FALSE, TRUE, NULL);
    PERFORM * FROM pgledger_create_account('SMOKE_CLIENT', 'SMOKE_AVAILABLE', 'Client EUR', 'EUR', TRUE, TRUE, NULL);
    PERFORM * FROM pgledger_create_account('SMOKE_COMPANY', 'SMOKE_AVAILABLE', 'Company', 'USD', TRUE, TRUE, NULL);

    BEGIN
        PERFORM * FROM pgledger_create_account('SMOKE_CLIENT', 'MISSING', 'x', 'USD', FALSE, TRUE, NULL);
        RAISE EXCEPTION 'expected unknown balance type to fail';
    EXCEPTION
        WHEN OTHERS THEN
            IF SQLERRM NOT LIKE '%balance type not found%' THEN
                RAISE;
            END IF;
    END;

    BEGIN
        PERFORM * FROM pgledger_create_account('SMOKE_CLIENT', 'SMOKE_AVAILABLE', 'dup', 'USD', FALSE, TRUE, NULL);
        RAISE EXCEPTION 'expected duplicate account to fail';
    EXCEPTION
        WHEN OTHERS THEN
            IF SQLERRM NOT LIKE '%account already exists%' THEN
                RAISE;
            END IF;
    END;

    PERFORM * FROM pgledger_create_transfer(
        'SMOKE_COMPANY', 'SMOKE_AVAILABLE', 'SMOKE_CLIENT', 'SMOKE_AVAILABLE', 'USD', 100, NULL, NULL);
    PERFORM * FROM pgledger_create_transfer(
        'SMOKE_CLIENT', 'SMOKE_AVAILABLE', 'SMOKE_CLIENT', 'SMOKE_LOCKED', 'USD', 40, NULL, NULL);

    SELECT balance, version INTO available_balance, available_version
    FROM pgledger_accounts
    WHERE account_id = 'SMOKE_CLIENT' AND balance_type = 'SMOKE_AVAILABLE' AND currency = 'USD';

    SELECT balance INTO locked_balance
    FROM pgledger_accounts
    WHERE account_id = 'SMOKE_CLIENT' AND balance_type = 'SMOKE_LOCKED' AND currency = 'USD';

    SELECT balance INTO company_balance
    FROM pgledger_accounts
    WHERE account_id = 'SMOKE_COMPANY' AND balance_type = 'SMOKE_AVAILABLE' AND currency = 'USD';

    IF available_balance <> 60 OR locked_balance <> 40 OR company_balance <> -100 THEN
        RAISE EXCEPTION 'balances available=% locked=% company=%, expected 60 / 40 / -100',
            available_balance, locked_balance, company_balance;
    END IF;

    IF available_version <> 2 THEN
        RAISE EXCEPTION 'available version %, expected 2', available_version;
    END IF;

    BEGIN
        PERFORM * FROM pgledger_create_transfer(
            'SMOKE_CLIENT', 'SMOKE_AVAILABLE', 'SMOKE_COMPANY', 'SMOKE_AVAILABLE', 'USD', 100, NULL, NULL);
        RAISE EXCEPTION 'expected negative balance to fail';
    EXCEPTION
        WHEN OTHERS THEN
            IF SQLERRM NOT LIKE '%does not allow negative balance%' THEN
                RAISE;
            END IF;
    END;

    SELECT balance INTO available_balance
    FROM pgledger_accounts
    WHERE account_id = 'SMOKE_CLIENT' AND balance_type = 'SMOKE_AVAILABLE' AND currency = 'USD';
    IF available_balance <> 60 THEN
        RAISE EXCEPTION 'balance changed after rejected debit: %', available_balance;
    END IF;

    BEGIN
        PERFORM * FROM pgledger_create_transfer(
            'SMOKE_CLIENT', 'SMOKE_AVAILABLE', 'SMOKE_COMPANY', 'SMOKE_AVAILABLE', 'EUR', 1, NULL, NULL);
        RAISE EXCEPTION 'expected currency mismatch to fail';
    EXCEPTION
        WHEN OTHERS THEN
            IF SQLERRM NOT LIKE '%different currencies%' THEN
                RAISE;
            END IF;
    END;

    BEGIN
        PERFORM * FROM pgledger_create_transfer(
            'SMOKE_CLIENT', 'SMOKE_AVAILABLE', 'SMOKE_CLIENT', 'SMOKE_AVAILABLE', 'USD', 1, NULL, NULL);
        RAISE EXCEPTION 'expected same-row transfer to fail';
    EXCEPTION
        WHEN OTHERS THEN
            IF SQLERRM NOT LIKE '%same account%' THEN
                RAISE;
            END IF;
    END;

    SELECT COALESCE(array_agg(id), ARRAY[]::TEXT[])
    INTO smoke_ids
    FROM pgledger_accounts
    WHERE account_id IN ('SMOKE_CLIENT', 'SMOKE_COMPANY');

    SELECT count(*) INTO transfer_count
    FROM pgledger_transfers
    WHERE from_account_id = ANY(smoke_ids) OR to_account_id = ANY(smoke_ids);

    SELECT count(*) INTO entry_count
    FROM pgledger_entries
    WHERE account_id = ANY(smoke_ids);

    IF transfer_count <> 2 OR entry_count <> 4 THEN
        RAISE EXCEPTION 'transfers=% entries=%, expected 2 and 4', transfer_count, entry_count;
    END IF;

    SELECT count(*) INTO bad_sum
    FROM (
        SELECT transfer_id
        FROM pgledger_entries
        WHERE account_id = ANY(smoke_ids)
        GROUP BY transfer_id
        HAVING count(*) <> 2 OR sum(amount) <> 0
    ) bad;
    IF bad_sum <> 0 THEN
        RAISE EXCEPTION 'a transfer does not have two legs that sum to 0';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM pgledger_accounts a
        WHERE a.id = ANY(smoke_ids)
          AND a.version > 0
          AND a.balance <> (
              SELECT e.account_current_balance
              FROM pgledger_entries e
              WHERE e.account_id = a.id
                AND e.account_version = a.version
          )
    ) THEN
        RAISE EXCEPTION 'account.balance does not match the latest entry';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM pgledger_transfers t
        WHERE (t.from_account_id = ANY(smoke_ids) OR t.to_account_id = ANY(smoke_ids))
          AND (t.biz_type IS DISTINCT FROM 'TRANSFER' OR t.request_id IS NOT NULL OR t.biz_reference IS NOT NULL)
    ) THEN
        RAISE EXCEPTION 'pairwise posting must be TRANSFER with null request_id and biz_reference';
    END IF;

    IF to_regclass('pgledger_bank_pools') IS NOT NULL
        OR to_regclass('pgledger_bank_shards') IS NOT NULL
        OR to_regclass('pgledger_cash_requests') IS NOT NULL THEN
        RAISE EXCEPTION 'redundant bank tables still exist';
    END IF;

    IF EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'pgledger_transfers' AND column_name = 'metadata'
    ) OR NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'pgledger_transfers' AND column_name = 'biz_reference' AND is_nullable = 'YES'
    ) THEN
        RAISE EXCEPTION 'pgledger_transfers must have nullable biz_reference and no metadata';
    END IF;

    SELECT id INTO deposit_id
    FROM pgledger_post_cash(
        'smoke-dep-1', 'DEPOSIT', 'SMOKE_CLIENT', 'SMOKE_AVAILABLE', 'USD', 15, NULL, 'smoke-wire', 8
    );
    SELECT biz_type, request_id, biz_reference INTO deposit_biz, deposit_request, deposit_ref
    FROM pgledger_transfers
    WHERE id = deposit_id;
    IF deposit_biz IS DISTINCT FROM 'DEPOSIT'
        OR deposit_request IS DISTINCT FROM 'smoke-dep-1'
        OR deposit_ref IS DISTINCT FROM 'smoke-wire' THEN
        RAISE EXCEPTION 'deposit biz_type=% request_id=% biz_reference=%, expected DEPOSIT / smoke-dep-1 / smoke-wire',
            deposit_biz, deposit_request, deposit_ref;
    END IF;

    deposit_shard := pgledger_bank_shard('smoke-dep-1', 8);
    SELECT fa.account_id INTO deposit_from
    FROM pgledger_transfers t
    JOIN pgledger_accounts fa ON fa.id = t.from_account_id
    JOIN pgledger_accounts ta ON ta.id = t.to_account_id
    WHERE t.id = deposit_id
      AND t.biz_type = 'DEPOSIT'
      AND fa.account_class = 'BANK'
      AND fa.deleted = FALSE
      AND fa.account_id = pgledger_bank_account_id('USD', 'SMOKE_AVAILABLE', deposit_shard)
      AND fa.currency = 'USD'
      AND fa.balance_type = 'SMOKE_AVAILABLE'
      AND ta.account_id = 'SMOKE_CLIENT';
    IF deposit_from IS NULL THEN
        RAISE EXCEPTION 'deposit did not debit BANK-USD-SMOKE_AVAILABLE-%', deposit_shard;
    END IF;

    SELECT id INTO replay_id
    FROM pgledger_post_cash(
        'smoke-dep-1', 'DEPOSIT', 'SMOKE_CLIENT', 'SMOKE_AVAILABLE', 'USD', 15, NULL, NULL, 8
    );
    IF replay_id IS DISTINCT FROM deposit_id THEN
        RAISE EXCEPTION 'replay posted a second transfer';
    END IF;

    SELECT balance INTO available_balance
    FROM pgledger_accounts
    WHERE account_id = 'SMOKE_CLIENT' AND balance_type = 'SMOKE_AVAILABLE' AND currency = 'USD';
    SELECT balance INTO bank_balance
    FROM pgledger_accounts
    WHERE account_id = deposit_from;
    IF available_balance <> 75 OR bank_balance <> -15 THEN
        RAISE EXCEPTION 'after deposit client=% bank=%, expected 75 / -15', available_balance, bank_balance;
    END IF;

    BEGIN
        PERFORM * FROM pgledger_post_cash(
            'smoke-dep-1', 'DEPOSIT', 'SMOKE_CLIENT', 'SMOKE_AVAILABLE', 'USD', 16, NULL, NULL, 8
        );
        RAISE EXCEPTION 'expected reused request id to fail';
    EXCEPTION
        WHEN OTHERS THEN
            IF SQLERRM NOT LIKE '%request id already used%' THEN
                RAISE;
            END IF;
    END;

    SELECT balance INTO available_balance
    FROM pgledger_accounts
    WHERE account_id = 'SMOKE_CLIENT' AND balance_type = 'SMOKE_AVAILABLE' AND currency = 'USD';
    IF available_balance <> 75 THEN
        RAISE EXCEPTION 'balance changed after rejected replay: %', available_balance;
    END IF;

    SELECT id INTO withdrawal_id
    FROM pgledger_post_cash(
        'smoke-wd-1', 'WITHDRAWAL', 'SMOKE_CLIENT', 'SMOKE_AVAILABLE', 'USD', 15, NULL, NULL, 8
    );
    SELECT t.biz_type INTO withdrawal_biz
    FROM pgledger_transfers t
    JOIN pgledger_accounts fa ON fa.id = t.from_account_id
    JOIN pgledger_accounts ta ON ta.id = t.to_account_id
    WHERE t.id = withdrawal_id
      AND t.request_id = 'smoke-wd-1'
      AND fa.account_id = 'SMOKE_CLIENT'
      AND ta.account_class = 'BANK'
      AND ta.account_id = pgledger_bank_account_id(
          'USD', 'SMOKE_AVAILABLE', pgledger_bank_shard('smoke-wd-1', 8)
      );
    IF withdrawal_biz IS DISTINCT FROM 'WITHDRAWAL' THEN
        RAISE EXCEPTION 'withdrawal biz_type=%, expected WITHDRAWAL', withdrawal_biz;
    END IF;

    SELECT balance INTO available_balance
    FROM pgledger_accounts
    WHERE account_id = 'SMOKE_CLIENT' AND balance_type = 'SMOKE_AVAILABLE' AND currency = 'USD';
    SELECT COALESCE(SUM(balance), 0) INTO bank_balance
    FROM pgledger_accounts
    WHERE account_class = 'BANK' AND balance_type = 'SMOKE_AVAILABLE' AND currency = 'USD';
    IF available_balance <> 60 OR bank_balance <> 0 THEN
        RAISE EXCEPTION 'after withdrawal client=% bank=%, expected 60 / 0', available_balance, bank_balance;
    END IF;

    RAISE NOTICE 'smoke test passed';
END $$;
