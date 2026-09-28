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
    available_version BIGINT;
    entry_count INT;
    transfer_count INT;
    bad_sum INT;
    smoke_ids TEXT[];
BEGIN
    SELECT COALESCE(array_agg(id), ARRAY[]::TEXT[])
    INTO smoke_ids
    FROM pgledger_accounts
    WHERE account_id IN ('SMOKE_CLIENT', 'SMOKE_COMPANY');

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

    RAISE NOTICE 'smoke test passed';
END $$;
