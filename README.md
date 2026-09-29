# pg_ledger

Postgres ledger. Writes go to the writer. Balance and entry reads go to the reader.

| | Host | Port | Database | User | Password |
|---|---|---|---|---|---|
| Writer | localhost | 5432 | pgledger | pgledger | pgledger |
| Reader | localhost | 5433 | pgledger | pgledger | pgledger |

```bash
docker compose up -d
```

The API is `http://localhost:8080`. Writes use the writer. Balance and journal reads use the reader. Header `X-Pgledger-Role` is `writer` or `reader`. A business rejection is HTTP 422.

The API process is stateless. Any instance can take any request. Every instance needs `PGLEDGER_WRITER_JDBC_URL`, `PGLEDGER_READER_JDBC_URL`, `PGLEDGER_JDBC_USER`, and `PGLEDGER_JDBC_PASSWORD`. `PORT` defaults to 8080. Schema scripts run on the writer at startup and can run on every instance at the same time.

```bash
curl -s http://localhost:8080/health
curl -s -X POST http://localhost:8080/api/v1/balance-types \
  -H 'Content-Type: application/json' \
  -d '{"code":"AVAILABLE","name":"Available"}'
curl -s -X POST http://localhost:8080/api/v1/accounts \
  -H 'Content-Type: application/json' \
  -d '{"accountId":"CLIENT_ACC_001","balanceType":"AVAILABLE","currency":"USD","allowNegativeBalance":false}'
curl -s -X POST http://localhost:8080/api/v1/postings \
  -H 'Content-Type: application/json' \
  -d '{"fromAccountId":"LP_DESK","fromBalanceType":"AVAILABLE","toAccountId":"CLIENT_ACC_001","toBalanceType":"AVAILABLE","currency":"USD","amount":100,"requestId":"fund-1"}'
curl -s -X POST http://localhost:8080/api/v1/deposits \
  -H 'Content-Type: application/json' \
  -d '{"requestId":"dep-1","accountId":"CLIENT_ACC_001","balanceType":"AVAILABLE","currency":"USD","amount":100}'
curl -s -X POST http://localhost:8080/api/v1/withdrawals \
  -H 'Content-Type: application/json' \
  -d '{"requestId":"wd-1","accountId":"CLIENT_ACC_001","balanceType":"AVAILABLE","currency":"USD","amount":40}'
curl -s 'http://localhost:8080/api/v1/balances?accountId=CLIENT_ACC_001&balanceType=AVAILABLE&currency=USD'
curl -s 'http://localhost:8080/api/v1/journals?page=0&size=50'
```

There are no foreign keys. A transfer stores the internal account id as text. An entry stores the internal account id and the transfer id as text. The database does not check that the other row exists. `pgledger_accounts_account_class_chk` is the account-class check. Adding a balance type, biz type, or currency is an `INSERT` into its registry. It is not a new SQL function.

One balance row is `(account_id, balance_type, currency)`. `pgledger_transfers` is the journal. `pgledger_entries` is the journal line. A transfer always has two entries. `pgledger_create_transfers` locks every balance row in sorted internal id order inside one database call, so several legs commit or roll back together.

## Tables

### `pgledger_balance_types`

| Column | |
|---|---|
| `code` | Primary key. |
| `name` | Required display name. |
| `description` | Optional. |
| `created_at`, `updated_at` | Required. |

Seeded rows, `name` equal to `code`: `LIQUID`, `PENDING_INCOMING`, `PENDING_OUTGOING`, `COMPLIANCE_HOLD`, `GAS_FEE`.

### `pgledger_currencies`

| Column | |
|---|---|
| `code` | Primary key. |
| `scale` | Required decimal places for that currency. |

Seeded rows: `USD` scale 2, `EUR` scale 2.

`pgledger_accounts.currency` is `TEXT NOT NULL`. It is not a foreign key and there is no check list of codes. Account create and posting reject a code that is not in this table.

### `pgledger_biz_types`

| Column | |
|---|---|
| `code` | Primary key. |
| `name` | Required. |

Seeded rows: `TRANSFER` / `Transfer`, `DEPOSIT` / `Deposit`, `WITHDRAWAL` / `Withdrawal`.

`pgledger_transfers.biz_type` is `TEXT NOT NULL`. It is not a foreign key. Posting rejects a code that is not in this table. The code is not inferred from which side is the bank.

### `pgledger_accounts`

`id` is the internal primary key (`pgla_...`). Transfers and entries store that id. `account_id` is the caller's business id. One row is one balance: unique `(account_id, balance_type, currency)`.

| Column | |
|---|---|
| `account_id`, `balance_type`, `currency` | The balance key. `currency` is text. |
| `name` | Display name. |
| `balance` | `NUMERIC`, starts at 0. |
| `version` | Starts at 0. Each posting increments it. |
| `allow_negative_balance`, `allow_positive_balance` | A `BANK` row allows both signs. |
| `metadata` | Optional `JSONB` on the account. Transfers do not have metadata. |
| `account_class` | `CLIENT` (default), `COMPANY`, `BANK`, `NOSTRO`, `SUSPENSE`, or `CONTROL`. |
| `deleted` | Soft delete, default `false`. The row stays. New transfers skip it. |

### `pgledger_transfers`

`id` is `pglt_...`. `from_account_id` and `to_account_id` are `pgledger_accounts.id`, not the business `account_id`. `amount` must be positive and the two sides must differ.

| Column | |
|---|---|
| `request_id` | `TEXT`, indexed, not unique. The idempotency key for one call. Every leg of that call stores the same value. A repeat returns those rows and does not post again. A different payload with the same id is rejected. |
| `biz_type` | Required code from `pgledger_biz_types`. A pairwise posting uses `TRANSFER`. A deposit uses `DEPOSIT`. A withdrawal uses `WITHDRAWAL`. |
| `biz_reference` | Optional caller business reference. Not unique. |
| `event_at`, `created_at` | `event_at` is the caller time, or `now()` when omitted. |

### `pgledger_entries`

`id` is `pgle_...`. Two rows per transfer. `account_id` and `transfer_id` are internal ids. `amount` is negative on the debit and positive on the credit. `account_previous_balance`, `account_current_balance`, and `account_version` record the balance row at posting time. The latest `account_version` matches `pgledger_accounts.version`.

## Calls

Run these on the **writer**.

### Balance type

`pgledger_create_balance_type(code, name, description)`. A duplicate code fails. This inserts one registry row. A new balance type is that insert, not a new function.

```sql
SELECT * FROM pgledger_create_balance_type('AVAILABLE', 'Available', NULL);
SELECT * FROM pgledger_create_balance_type('LOCKED', 'Locked', NULL);
```

### Currency

There is no currency function. Insert a row. `scale` is the number of decimal places.

```sql
INSERT INTO pgledger_currencies (code, scale) VALUES ('EXAMPLE', 2);
```

### Biz type

There is no biz-type function. Insert a row. A coin deposit is `COIN_DEPOSIT` after this insert, posted with `pgledger_create_transfer` like any other movement.

```sql
INSERT INTO pgledger_biz_types (code, name) VALUES ('COIN_DEPOSIT', 'Coin deposit');
```

### Account

`pgledger_create_account` requires a known balance type and a known currency. A duplicate `(account_id, balance_type, currency)` fails.

```sql
SELECT * FROM pgledger_create_account(
    p_account_id => 'CLIENT_ACC_001',
    p_balance_type => 'AVAILABLE',
    p_name => 'Client available',
    p_currency => 'USD',
    p_allow_negative_balance => FALSE,
    p_allow_positive_balance => TRUE
);

SELECT * FROM pgledger_create_account(
    p_account_id => 'CLIENT_ACC_001',
    p_balance_type => 'LOCKED',
    p_name => 'Client locked',
    p_currency => 'USD',
    p_allow_negative_balance => FALSE,
    p_allow_positive_balance => TRUE
);

SELECT * FROM pgledger_create_account(
    p_account_id => 'CLIENT_ACC_002',
    p_balance_type => 'AVAILABLE',
    p_name => 'Client 2',
    p_currency => 'USD',
    p_allow_negative_balance => FALSE,
    p_allow_positive_balance => TRUE
);

SELECT * FROM pgledger_create_account(
    p_account_id => 'LP_DESK',
    p_balance_type => 'AVAILABLE',
    p_name => 'LP desk',
    p_currency => 'USD',
    p_allow_negative_balance => TRUE,
    p_allow_positive_balance => TRUE
);
```

`account_class` defaults to `CLIENT`. Pass `p_account_class => 'COMPANY'` for a house account. Soft-delete is `pgledger_delete_account(account_id, balance_type, currency)` or `POST /api/v1/accounts/delete`.

### One transfer

`pgledger_create_transfer(from_account_id, from_balance_type, to_account_id, to_balance_type, currency, amount, event_at, biz_reference, request_id, biz_type)`.

`request_id` is required. `biz_type` defaults to `TRANSFER`. `biz_reference` and `event_at` may be null.

```sql
SELECT * FROM pgledger_create_transfer(
    'LP_DESK', 'AVAILABLE',
    'CLIENT_ACC_001', 'AVAILABLE',
    'USD', 100,
    NULL, NULL, 'fund-client-1'
);

SELECT * FROM pgledger_create_transfer(
    'LP_DESK', 'AVAILABLE',
    'CLIENT_ACC_002', 'AVAILABLE',
    'USD', 80,
    NULL, 'wire-80', 'fund-client-2', 'TRANSFER'
);
```

### Several transfers, one `request_id`

`pgledger_create_transfers(requests, event_at, biz_reference, request_id, biz_type)`. Every leg stores that same `request_id` and `biz_type`. A repeat of the same call returns the original rows.

```sql
SELECT * FROM pgledger_create_transfers(
    p_transfer_requests => ARRAY[
        ('CLIENT_ACC_001', 'AVAILABLE', 'CLIENT_ACC_001', 'LOCKED', 'USD', 40),
        ('CLIENT_ACC_002', 'AVAILABLE', 'LP_DESK', 'AVAILABLE', 'USD', 25)
    ]::transfer_request[],
    p_request_id => 'rfq-1'
);
```

The first leg holds client 1 funds on `LOCKED`. The second leg pays the LP from client 2. After the fund and RFQ calls, client 1 `AVAILABLE` is 60 and `LOCKED` is 40.

## BANK pool

Account id `BANK` is the pool, not a stored row. `pgledger_create_transfer` and `pgledger_create_transfers` replace it with:

`BANK-{currency}-{balanceType}-{n}`

`n` is `hash(request_id) % poolSize`, in `0 .. poolSize-1`. The same helper is used for every currency. If the pool does not exist yet, the transfer creates it at size 8 and keeps that size afterwards. `pgledger_ensure_bank_pool(currency, balance_type, pool_size)` can create a different size first. `PGLEDGER_BANK_POOL_SIZE` is that size for the API (default 8).

A deposit debits the shard and credits the client. A withdrawal is the reverse. Set `biz_type` yourself.

```sql
SELECT * FROM pgledger_create_transfer(
    'BANK', 'AVAILABLE',
    'CLIENT_ACC_001', 'AVAILABLE',
    'USD', 100,
    NULL, 'wire-100', 'dep-1', 'DEPOSIT'
);

SELECT * FROM pgledger_create_transfer(
    'CLIENT_ACC_001', 'AVAILABLE',
    'BANK', 'AVAILABLE',
    'USD', 40,
    NULL, NULL, 'wd-1', 'WITHDRAWAL'
);
```

BANK shards may be negative. `pgledger_bank_position(balance_type, currency)` is the sum of the BANK rows for that balance type and currency. The HTTP deposit and withdrawal routes do this same transfer.

## Get balance

Run on the **reader**.

```sql
SELECT account_id, balance_type, currency, balance, version
FROM pgledger_accounts
WHERE account_id = 'CLIENT_ACC_001'
ORDER BY balance_type, currency;
```

## Get entries

Run on the **reader**.

```sql
SELECT
    a.account_id,
    a.balance_type,
    a.currency,
    e.transfer_id,
    e.amount,
    e.account_previous_balance,
    e.account_current_balance,
    e.account_version,
    e.created_at
FROM pgledger_entries e
JOIN pgledger_accounts a ON a.id = e.account_id
WHERE a.account_id = 'CLIENT_ACC_001'
ORDER BY e.account_version, e.id;
```
