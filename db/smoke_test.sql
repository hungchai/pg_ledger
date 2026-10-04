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
    available_type INT;
    locked_type INT;
    liquid_type INT;
BEGIN
    SELECT COALESCE(array_agg(id), ARRAY[]::TEXT[])
    INTO smoke_ids
    FROM pgledger_accounts
    WHERE account_id IN ('SMOKE_CLIENT', 'SMOKE_COMPANY')
       OR account_id LIKE 'BANK-%-SMOKE_%'
       OR account_id LIKE 'BANK-BTC-LIQUID-%'
       OR account_id LIKE 'BANK-ETH-LIQUID-%'
       OR account_id LIKE 'BANK-USDT-LIQUID-%';

    DELETE FROM pgledger_entries WHERE account_id = ANY(smoke_ids);
    DELETE FROM pgledger_transfers
    WHERE from_account_id = ANY(smoke_ids) OR to_account_id = ANY(smoke_ids);
    DELETE FROM pgledger_accounts WHERE id = ANY(smoke_ids);
    DELETE FROM pgledger_balance_types WHERE code IN ('SMOKE_AVAILABLE', 'SMOKE_LOCKED', 'SMOKE_NEG');
    DELETE FROM pgledger_biz_types WHERE code IN ('SMOKE_FEE', 'COIN_DEPOSIT');
    DELETE FROM pgledger_currencies WHERE code = 'SMOKE_CCY';

    IF (SELECT count(*) FROM pgledger_account_classes) <> 6 THEN
        RAISE EXCEPTION 'account classes not seeded';
    END IF;
    IF (SELECT id FROM pgledger_account_classes WHERE code = 'CLIENT') <> 1
        OR (SELECT id FROM pgledger_account_classes WHERE code = 'BANK') <> 3 THEN
        RAISE EXCEPTION 'account class ids are not stable';
    END IF;
    IF (SELECT count(*) FROM pgledger_biz_types WHERE code IN ('TRANSFER', 'DEPOSIT', 'WITHDRAWAL')) <> 3 THEN
        RAISE EXCEPTION 'biz types not seeded';
    END IF;
    IF (SELECT id FROM pgledger_biz_types WHERE code = 'TRANSFER') <> 1 THEN
        RAISE EXCEPTION 'TRANSFER id is not 1';
    END IF;
    IF (
        SELECT count(*)
        FROM pgledger_balance_types
        WHERE code IN ('LIQUID', 'PENDING_INCOMING', 'PENDING_OUTGOING', 'COMPLIANCE_HOLD', 'GAS_FEE')
          AND name = code
    ) <> 5 THEN
        RAISE EXCEPTION 'balance types not seeded';
    END IF;
    IF (SELECT id FROM pgledger_balance_types WHERE code = 'LIQUID') <> 1 THEN
        RAISE EXCEPTION 'LIQUID id is not 1';
    END IF;
    IF (
        SELECT count(*)
        FROM pgledger_currencies
        WHERE (code = 'USD' AND id = 1 AND scale = 2)
           OR (code = 'EUR' AND id = 2 AND scale = 2)
           OR (code = 'BTC' AND id = 3 AND scale = 8)
           OR (code = 'ETH' AND id = 4 AND scale = 18)
           OR (code = 'USDT' AND id = 5 AND scale = 6)
    ) <> 5 THEN
        RAISE EXCEPTION 'currencies not seeded';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_schema = 'public'
          AND (
              (table_name = 'pgledger_accounts' AND column_name IN ('balance_type_id', 'currency_id', 'account_class_id'))
              OR (table_name = 'pgledger_transfers' AND column_name = 'biz_type_id')
              OR (table_name = 'pgledger_account_classes' AND column_name = 'id')
              OR (table_name = 'pgledger_balance_types' AND column_name = 'id')
              OR (table_name = 'pgledger_biz_types' AND column_name = 'id')
              OR (table_name = 'pgledger_currencies' AND column_name = 'id')
          )
          AND data_type <> 'integer'
    ) THEN
        RAISE EXCEPTION 'registry id column is not integer';
    END IF;
    IF EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'pgledger_accounts_account_class_chk'
    ) THEN
        RAISE EXCEPTION 'account class check still exists';
    END IF;
    IF EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE contype = 'f'
          AND conrelid IN (
              'pgledger_accounts'::regclass,
              'pgledger_transfers'::regclass,
              'pgledger_entries'::regclass,
              'pgledger_account_classes'::regclass,
              'pgledger_balance_types'::regclass,
              'pgledger_biz_types'::regclass,
              'pgledger_currencies'::regclass
          )
    ) THEN
        RAISE EXCEPTION 'foreign key exists';
    END IF;
    IF EXISTS (
        SELECT 1
        FROM pg_proc p
        JOIN pg_class c ON c.relname = p.proname AND c.relkind = 'r'
        WHERE p.pronamespace = 'public'::regnamespace
          AND c.relnamespace = 'public'::regnamespace
    ) THEN
        RAISE EXCEPTION 'function name equals a table name';
    END IF;
    IF EXISTS (
        SELECT 1
        FROM pg_proc
        WHERE proname IN ('pgledger_create_transfer', 'pgledger_create_transfers')
          AND pg_get_functiondef(oid) LIKE '%pgledger_balance_types%'
    ) THEN
        RAISE EXCEPTION 'create_transfer reads pgledger_balance_types';
    END IF;
    IF (
        SELECT data_type FROM information_schema.columns
        WHERE table_schema = 'public' AND table_name = 'pgledger_transfers' AND column_name = 'request_id'
    ) IS DISTINCT FROM 'text' THEN
        RAISE EXCEPTION 'request_id is not text';
    END IF;
    IF EXISTS (
        SELECT 1
        FROM pg_index i
        JOIN pg_class c ON c.oid = i.indrelid
        JOIN pg_attribute a ON a.attrelid = c.oid AND a.attnum = ANY(i.indkey)
        WHERE c.relname = 'pgledger_transfers'
          AND a.attname = 'request_id'
          AND i.indisunique
    ) THEN
        RAISE EXCEPTION 'request_id index is unique';
    END IF;
    IF EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = 'public'
          AND table_name = 'pgledger_transfers'
          AND column_name = 'metadata'
    ) THEN
        RAISE EXCEPTION 'transfer metadata exists';
    END IF;

    PERFORM * FROM pgledger_create_balance_type('SMOKE_AVAILABLE', 'Available', NULL);
    PERFORM * FROM pgledger_create_balance_type('SMOKE_LOCKED', 'Locked', NULL);
    SELECT id INTO available_type FROM pgledger_balance_types WHERE code = 'SMOKE_AVAILABLE';
    SELECT id INTO locked_type FROM pgledger_balance_types WHERE code = 'SMOKE_LOCKED';
    IF (
        SELECT count(*)
        FROM pgledger_balance_types
        WHERE code IN ('SMOKE_AVAILABLE', 'SMOKE_LOCKED')
          AND allow_negative = FALSE
          AND allow_positive = TRUE
    ) <> 2 THEN
        RAISE EXCEPTION 'smoke balance types did not default to (false, true)';
    END IF;
    IF (
        SELECT count(*)
        FROM pgledger_balance_types
        WHERE (code = 'LIQUID' AND NOT allow_negative AND allow_positive)
           OR (code = 'PENDING_INCOMING' AND NOT allow_negative AND allow_positive)
           OR (code = 'PENDING_OUTGOING' AND NOT allow_negative AND allow_positive)
           OR (code = 'COMPLIANCE_HOLD' AND NOT allow_negative AND allow_positive)
           OR (code = 'GAS_FEE' AND allow_negative AND allow_positive)
    ) <> 5 THEN
        RAISE EXCEPTION 'seeded balance type sign policy is wrong';
    END IF;

    BEGIN
        PERFORM * FROM pgledger_create_balance_type('SMOKE_AVAILABLE', 'dup', NULL);
        RAISE EXCEPTION 'expected duplicate balance type to fail';
    EXCEPTION
        WHEN OTHERS THEN
            IF SQLERRM NOT LIKE '%balance type already exists%' THEN
                RAISE;
            END IF;
    END;

    PERFORM * FROM pgledger_create_balance_type('SMOKE_NEG', 'Smoke negative', NULL, TRUE, TRUE);
    IF (
        SELECT allow_negative FROM pgledger_balance_types WHERE code = 'SMOKE_NEG'
    ) IS DISTINCT FROM TRUE THEN
        RAISE EXCEPTION 'smoke negative type did not store allow_negative';
    END IF;

    -- SMOKE_COMPANY funds the client and may sit below zero, so it uses the BANK
    -- class override. A CLIENT account on SMOKE_AVAILABLE cannot go negative.
    PERFORM * FROM pgledger_create_account('SMOKE_CLIENT', 'SMOKE_AVAILABLE', 'Client', 'USD', NULL);
    PERFORM * FROM pgledger_create_account('SMOKE_CLIENT', 'SMOKE_LOCKED', 'Client locked', 'USD', NULL);
    PERFORM * FROM pgledger_create_account('SMOKE_CLIENT', 'SMOKE_AVAILABLE', 'Client EUR', 'EUR', NULL);
    PERFORM * FROM pgledger_create_account('SMOKE_COMPANY', 'SMOKE_AVAILABLE', 'Company', 'USD', NULL, 'BANK');

    BEGIN
        PERFORM * FROM pgledger_create_account('SMOKE_CLIENT', 'SMOKE_AVAILABLE', 'x', 'ZZZ', NULL);
        RAISE EXCEPTION 'expected unknown currency to fail';
    EXCEPTION
        WHEN OTHERS THEN
            IF SQLERRM NOT LIKE '%currency not found%' THEN
                RAISE;
            END IF;
    END;

    BEGIN
        PERFORM * FROM pgledger_create_account('SMOKE_CLIENT', 'MISSING', 'x', 'USD', NULL);
        RAISE EXCEPTION 'expected unknown balance type to fail';
    EXCEPTION
        WHEN OTHERS THEN
            IF SQLERRM NOT LIKE '%balance type not found%' THEN
                RAISE;
            END IF;
    END;

    BEGIN
        PERFORM * FROM pgledger_create_account('SMOKE_CLIENT', 'SMOKE_AVAILABLE', 'dup', 'USD', NULL);
        RAISE EXCEPTION 'expected duplicate account to fail';
    EXCEPTION
        WHEN OTHERS THEN
            IF SQLERRM NOT LIKE '%account already exists%' THEN
                RAISE;
            END IF;
    END;

    BEGIN
        PERFORM * FROM pgledger_create_transfer(
            'SMOKE_CLIENT', available_type, 'SMOKE_COMPANY', available_type, 'USD', 1,
            NULL, NULL, 'smoke-bad-biz', 'NOT_A_TYPE');
        RAISE EXCEPTION 'expected unknown biz type to fail';
    EXCEPTION
        WHEN OTHERS THEN
            IF SQLERRM NOT LIKE '%biz type not found%' THEN
                RAISE;
            END IF;
    END;

    BEGIN
        PERFORM * FROM pgledger_create_transfer(
            'SMOKE_CLIENT', available_type, 'SMOKE_COMPANY', available_type, 'ZZZ', 1,
            NULL, NULL, 'smoke-bad-ccy');
        RAISE EXCEPTION 'expected unknown currency to fail';
    EXCEPTION
        WHEN OTHERS THEN
            IF SQLERRM NOT LIKE '%currency not found%' THEN
                RAISE;
            END IF;
    END;

    BEGIN
        PERFORM * FROM pgledger_create_transfer(
            'SMOKE_CLIENT', -1, 'SMOKE_COMPANY', available_type, 'USD', 1,
            NULL, NULL, 'smoke-bad-bt');
        RAISE EXCEPTION 'expected unknown balance type id to fail';
    EXCEPTION
        WHEN OTHERS THEN
            IF SQLERRM NOT LIKE '%-1%' THEN
                RAISE;
            END IF;
    END;

    PERFORM * FROM pgledger_create_transfer(
        'SMOKE_COMPANY', available_type, 'SMOKE_CLIENT', available_type, 'USD', 100, NULL, NULL, 'smoke-xfer-1');
    PERFORM * FROM pgledger_create_transfer(
        'SMOKE_CLIENT', available_type, 'SMOKE_CLIENT', locked_type, 'USD', 40, NULL, NULL, 'smoke-xfer-2');
    PERFORM * FROM pgledger_create_transfer(
        'SMOKE_COMPANY', available_type, 'SMOKE_CLIENT', available_type, 'USD', 100, NULL, NULL, 'smoke-xfer-1');

    BEGIN
        PERFORM * FROM pgledger_create_transfer(
            'SMOKE_COMPANY', available_type, 'SMOKE_CLIENT', available_type, 'USD', 101, NULL, NULL, 'smoke-xfer-1');
        RAISE EXCEPTION 'expected reused request id to fail';
    EXCEPTION
        WHEN OTHERS THEN
            IF SQLERRM NOT LIKE '%request id already used%' THEN
                RAISE;
            END IF;
    END;

    SELECT balance, version INTO available_balance, available_version
    FROM pgledger_accounts_view
    WHERE account_id = 'SMOKE_CLIENT' AND balance_type = 'SMOKE_AVAILABLE' AND currency = 'USD';

    SELECT balance INTO locked_balance
    FROM pgledger_accounts_view
    WHERE account_id = 'SMOKE_CLIENT' AND balance_type = 'SMOKE_LOCKED' AND currency = 'USD';

    SELECT balance INTO company_balance
    FROM pgledger_accounts_view
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
            'SMOKE_CLIENT', available_type, 'SMOKE_COMPANY', available_type, 'USD', 100, NULL, NULL, 'smoke-neg');
        RAISE EXCEPTION 'expected negative balance to fail';
    EXCEPTION
        WHEN OTHERS THEN
            IF SQLERRM NOT LIKE '%does not allow negative balance%' THEN
                RAISE;
            END IF;
    END;

    SELECT balance INTO available_balance
    FROM pgledger_accounts_view
    WHERE account_id = 'SMOKE_CLIENT' AND balance_type = 'SMOKE_AVAILABLE' AND currency = 'USD';
    IF available_balance <> 60 THEN
        RAISE EXCEPTION 'balance changed after rejected debit: %', available_balance;
    END IF;

    BEGIN
        PERFORM * FROM pgledger_create_transfer(
            'SMOKE_CLIENT', available_type, 'SMOKE_COMPANY', available_type, 'EUR', 1, NULL, NULL, 'smoke-fx');
        RAISE EXCEPTION 'expected currency mismatch to fail';
    EXCEPTION
        WHEN OTHERS THEN
            IF SQLERRM NOT LIKE '%different currencies%' THEN
                RAISE;
            END IF;
    END;

    BEGIN
        PERFORM * FROM pgledger_create_transfer(
            'SMOKE_CLIENT', available_type, 'SMOKE_CLIENT', available_type, 'USD', 1, NULL, NULL, 'smoke-same');
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
        FROM pgledger_transfers_view t
        WHERE (t.from_account_id = ANY(smoke_ids) OR t.to_account_id = ANY(smoke_ids))
          AND (t.biz_type IS DISTINCT FROM 'TRANSFER' OR t.request_id IS NULL OR t.biz_reference IS NOT NULL)
    ) THEN
        RAISE EXCEPTION 'pairwise posting must be TRANSFER with a request_id and a null biz_reference';
    END IF;

    PERFORM * FROM pgledger_create_transfers(
        ARRAY[
            ('SMOKE_CLIENT', available_type, 'SMOKE_CLIENT', locked_type, 'USD', 5),
            ('SMOKE_CLIENT', locked_type, 'SMOKE_CLIENT', available_type, 'USD', 5)
        ]::transfer_request[],
        NULL, NULL, 'smoke-batch-1'
    );
    PERFORM * FROM pgledger_create_transfers(
        ARRAY[
            ('SMOKE_CLIENT', available_type, 'SMOKE_CLIENT', locked_type, 'USD', 5),
            ('SMOKE_CLIENT', locked_type, 'SMOKE_CLIENT', available_type, 'USD', 5)
        ]::transfer_request[],
        NULL, NULL, 'smoke-batch-1'
    );
    SELECT count(*) INTO transfer_count
    FROM pgledger_transfers
    WHERE request_id = 'smoke-batch-1';
    IF transfer_count <> 2 THEN
        RAISE EXCEPTION 'batch request_id rows %, expected 2', transfer_count;
    END IF;
    SELECT balance INTO available_balance
    FROM pgledger_accounts_view
    WHERE account_id = 'SMOKE_CLIENT' AND balance_type = 'SMOKE_AVAILABLE' AND currency = 'USD';
    SELECT balance INTO locked_balance
    FROM pgledger_accounts_view
    WHERE account_id = 'SMOKE_CLIENT' AND balance_type = 'SMOKE_LOCKED' AND currency = 'USD';
    IF available_balance <> 60 OR locked_balance <> 40 THEN
        RAISE EXCEPTION 'batch replay changed balances available=% locked=%', available_balance, locked_balance;
    END IF;

    BEGIN
        PERFORM * FROM pgledger_create_transfers(
            ARRAY[
                ('SMOKE_CLIENT', available_type, 'SMOKE_CLIENT', locked_type, 'USD', 5),
                ('SMOKE_CLIENT', locked_type, 'SMOKE_CLIENT', available_type, 'USD', 6)
            ]::transfer_request[],
            NULL, NULL, 'smoke-batch-1'
        );
        RAISE EXCEPTION 'expected reused batch request id to fail';
    EXCEPTION
        WHEN OTHERS THEN
            IF SQLERRM NOT LIKE '%request id already used%' THEN
                RAISE;
            END IF;
    END;

    SELECT id INTO deposit_id
    FROM pgledger_create_transfer(
        'BANK', available_type, 'SMOKE_CLIENT', available_type, 'USD', 15,
        NULL, 'smoke-wire', 'smoke-dep-1', 'DEPOSIT'
    );
    SELECT biz_type, request_id, biz_reference INTO deposit_biz, deposit_request, deposit_ref
    FROM pgledger_transfers_view
    WHERE id = deposit_id;
    IF deposit_biz IS DISTINCT FROM 'DEPOSIT'
        OR deposit_request IS DISTINCT FROM 'smoke-dep-1'
        OR deposit_ref IS DISTINCT FROM 'smoke-wire' THEN
        RAISE EXCEPTION 'deposit biz_type=% request_id=% biz_reference=%, expected DEPOSIT / smoke-dep-1 / smoke-wire',
            deposit_biz, deposit_request, deposit_ref;
    END IF;

    deposit_shard := pgledger_bank_shard('smoke-dep-1', 8);
    SELECT fa.account_id INTO deposit_from
    FROM pgledger_transfers_view t
    JOIN pgledger_accounts_view fa ON fa.id = t.from_account_id
    JOIN pgledger_accounts_view ta ON ta.id = t.to_account_id
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
    FROM pgledger_create_transfer(
        'BANK', available_type, 'SMOKE_CLIENT', available_type, 'USD', 15,
        NULL, NULL, 'smoke-dep-1', 'DEPOSIT'
    );
    IF replay_id IS DISTINCT FROM deposit_id THEN
        RAISE EXCEPTION 'replay posted a second transfer';
    END IF;

    SELECT balance INTO available_balance
    FROM pgledger_accounts_view
    WHERE account_id = 'SMOKE_CLIENT' AND balance_type = 'SMOKE_AVAILABLE' AND currency = 'USD';
    SELECT balance INTO bank_balance
    FROM pgledger_accounts
    WHERE account_id = deposit_from;
    IF available_balance <> 75 OR bank_balance <> -15 THEN
        RAISE EXCEPTION 'after deposit client=% bank=%, expected 75 / -15', available_balance, bank_balance;
    END IF;

    BEGIN
        PERFORM * FROM pgledger_create_transfer(
            'BANK', available_type, 'SMOKE_CLIENT', available_type, 'USD', 16,
            NULL, NULL, 'smoke-dep-1', 'DEPOSIT'
        );
        RAISE EXCEPTION 'expected reused request id to fail';
    EXCEPTION
        WHEN OTHERS THEN
            IF SQLERRM NOT LIKE '%request id already used%' THEN
                RAISE;
            END IF;
    END;

    SELECT balance INTO available_balance
    FROM pgledger_accounts_view
    WHERE account_id = 'SMOKE_CLIENT' AND balance_type = 'SMOKE_AVAILABLE' AND currency = 'USD';
    IF available_balance <> 75 THEN
        RAISE EXCEPTION 'balance changed after rejected replay: %', available_balance;
    END IF;

    SELECT id INTO withdrawal_id
    FROM pgledger_create_transfer(
        'SMOKE_CLIENT', available_type, 'BANK', available_type, 'USD', 15,
        NULL, NULL, 'smoke-wd-1', 'WITHDRAWAL'
    );
    SELECT t.biz_type INTO withdrawal_biz
    FROM pgledger_transfers_view t
    JOIN pgledger_accounts_view fa ON fa.id = t.from_account_id
    JOIN pgledger_accounts_view ta ON ta.id = t.to_account_id
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
    FROM pgledger_accounts_view
    WHERE account_id = 'SMOKE_CLIENT' AND balance_type = 'SMOKE_AVAILABLE' AND currency = 'USD';
    SELECT COALESCE(SUM(balance), 0) INTO bank_balance
    FROM pgledger_accounts_view
    WHERE account_class = 'BANK' AND balance_type = 'SMOKE_AVAILABLE' AND currency = 'USD'
      AND account_id <> 'SMOKE_COMPANY';
    IF available_balance <> 60 OR bank_balance <> 0 THEN
        RAISE EXCEPTION 'after withdrawal client=% bank=%, expected 60 / 0', available_balance, bank_balance;
    END IF;

    INSERT INTO pgledger_biz_types (code, name) VALUES ('SMOKE_FEE', 'Smoke fee');
    PERFORM * FROM pgledger_create_transfers(
        ARRAY[
            ('SMOKE_CLIENT', available_type, 'SMOKE_COMPANY', available_type, 'USD', 1)
        ]::transfer_request[],
        NULL, NULL, 'smoke-fee-1', 'SMOKE_FEE'
    );
    SELECT biz_type INTO deposit_biz
    FROM pgledger_transfers_view
    WHERE request_id = 'smoke-fee-1';
    IF deposit_biz IS DISTINCT FROM 'SMOKE_FEE' THEN
        RAISE EXCEPTION 'inserted biz type was not stored';
    END IF;
    DELETE FROM pgledger_biz_types WHERE code = 'SMOKE_FEE';

    INSERT INTO pgledger_biz_types (code, name) VALUES ('COIN_DEPOSIT', 'Coin deposit');
    SELECT id INTO deposit_id
    FROM pgledger_create_transfer(
        'BANK', available_type, 'SMOKE_CLIENT', available_type, 'USD', 1,
        NULL, NULL, 'smoke-coin-1', 'COIN_DEPOSIT'
    );
    SELECT t.biz_type, fa.account_id INTO deposit_biz, deposit_from
    FROM pgledger_transfers_view t
    JOIN pgledger_accounts_view fa ON fa.id = t.from_account_id
    WHERE t.id = deposit_id;
    IF deposit_biz IS DISTINCT FROM 'COIN_DEPOSIT'
        OR deposit_from IS DISTINCT FROM pgledger_bank_account_id(
            'USD', 'SMOKE_AVAILABLE', pgledger_bank_shard('smoke-coin-1', 8)
        ) THEN
        RAISE EXCEPTION 'coin deposit biz_type=% from=%, expected COIN_DEPOSIT and the bank shard',
            deposit_biz, deposit_from;
    END IF;
    DELETE FROM pgledger_biz_types WHERE code = 'COIN_DEPOSIT';

    INSERT INTO pgledger_currencies (code, scale) VALUES ('SMOKE_CCY', 8);
    PERFORM * FROM pgledger_create_account(
        'SMOKE_CLIENT', 'SMOKE_AVAILABLE', 'Client smoke ccy', 'SMOKE_CCY', NULL);
    PERFORM * FROM pgledger_create_account(
        'SMOKE_COMPANY', 'SMOKE_AVAILABLE', 'Company smoke ccy', 'SMOKE_CCY', NULL, 'BANK');
    PERFORM * FROM pgledger_create_transfer(
        'SMOKE_COMPANY', available_type, 'SMOKE_CLIENT', available_type, 'SMOKE_CCY', 1,
        NULL, NULL, 'smoke-ccy-1');
    DELETE FROM pgledger_currencies WHERE code = 'SMOKE_CCY';

    SELECT id INTO liquid_type FROM pgledger_balance_types WHERE code = 'LIQUID';
    IF pgledger_ensure_bank_pool('BTC', 'LIQUID', liquid_type, 2, FALSE) <> 2
        OR pgledger_ensure_bank_pool('ETH', 'LIQUID', liquid_type, 2, FALSE) <> 2
        OR pgledger_ensure_bank_pool('USDT', 'LIQUID', liquid_type, 2, FALSE) <> 2 THEN
        RAISE EXCEPTION 'crypto bank pool size is not 2';
    END IF;
    IF (
        SELECT count(*)
        FROM pgledger_accounts_view
        WHERE account_class = 'BANK'
          AND balance_type = 'LIQUID'
          AND currency IN ('BTC', 'ETH', 'USDT')
    ) <> 6 THEN
        RAISE EXCEPTION 'expected 6 crypto BANK accounts';
    END IF;

    PERFORM * FROM pgledger_create_account('SMOKE_CLIENT', 'LIQUID', 'Client BTC', 'BTC', NULL);
    PERFORM * FROM pgledger_create_account('SMOKE_CLIENT', 'LIQUID', 'Client ETH', 'ETH', NULL);
    PERFORM * FROM pgledger_create_account('SMOKE_CLIENT', 'LIQUID', 'Client USDT', 'USDT', NULL);

    PERFORM * FROM pgledger_create_transfer(
        'BANK', liquid_type, 'SMOKE_CLIENT', liquid_type, 'BTC', 0.12345678,
        NULL, NULL, 'smoke-btc-1', 'DEPOSIT');
    PERFORM * FROM pgledger_create_transfer(
        'BANK', liquid_type, 'SMOKE_CLIENT', liquid_type, 'ETH', 1.234567890123456789,
        NULL, NULL, 'smoke-eth-1', 'DEPOSIT');
    PERFORM * FROM pgledger_create_transfer(
        'BANK', liquid_type, 'SMOKE_CLIENT', liquid_type, 'USDT', 100.123456,
        NULL, NULL, 'smoke-usdt-1', 'DEPOSIT');

    SELECT balance INTO available_balance
    FROM pgledger_accounts_view
    WHERE account_id = 'SMOKE_CLIENT' AND balance_type = 'LIQUID' AND currency = 'BTC';
    SELECT balance INTO locked_balance
    FROM pgledger_accounts_view
    WHERE account_id = 'SMOKE_CLIENT' AND balance_type = 'LIQUID' AND currency = 'ETH';
    SELECT balance INTO company_balance
    FROM pgledger_accounts_view
    WHERE account_id = 'SMOKE_CLIENT' AND balance_type = 'LIQUID' AND currency = 'USDT';
    IF available_balance <> 0.12345678
        OR locked_balance <> 1.234567890123456789
        OR company_balance <> 100.123456 THEN
        RAISE EXCEPTION 'crypto balances btc=% eth=% usdt=%', available_balance, locked_balance, company_balance;
    END IF;

    SELECT COALESCE(SUM(balance), 0) INTO bank_balance
    FROM pgledger_accounts_view
    WHERE account_class = 'BANK' AND balance_type = 'LIQUID' AND currency = 'BTC';
    IF bank_balance <> -0.12345678 THEN
        RAISE EXCEPTION 'BTC bank position %, expected -0.12345678', bank_balance;
    END IF;
    SELECT fa.account_id INTO deposit_from
    FROM pgledger_transfers_view t
    JOIN pgledger_accounts_view fa ON fa.id = t.from_account_id
    WHERE t.request_id = 'smoke-btc-1'
      AND fa.account_id = pgledger_bank_account_id('BTC', 'LIQUID', pgledger_bank_shard('smoke-btc-1', 2));
    IF deposit_from IS NULL THEN
        RAISE EXCEPTION 'BTC deposit did not debit a LIQUID shard';
    END IF;

    RAISE NOTICE 'smoke test passed';
END $$;
