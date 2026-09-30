# pg_ledger

Postgres only. No Kafka. No Redis.

Writes go to the writer. Balance and entry reads go to the reader.

## Tests

Docker daemon required for all of these. Compose is only for stress (and local API).

| Suite | How Postgres is provided | Command |
|---|---|---|
| Core (`PgLedgerTest`) | Testcontainers (single `postgres:16`) | `./gradlew :pgledger-core:test` |
| REST (`PgLedgerRestTest`) | Testcontainers (primary + streaming replica) | `./gradlew :pgledger-restful:test` |
| Stress (`PgLedgerStressTest`) | Docker Compose on localhost `5432` / `5433` | `docker compose up -d` then `./gradlew :pgledger-restful:stressTest` |

`PgLedgerTest` and `PgLedgerRestTest` never use Compose. `stressTest` is excluded from `:pgledger-restful:test` and expects the Compose writer/reader.

Schema scripts (`V001`/`V002`) are idempotent and also run on API/stress startup. Compose `initdb` only runs on an empty volume. If a long-lived volume predates a breaking schema move and migrate still fails, wipe and recreate: `docker compose down -v && docker compose up -d`.

After stress finishes it prints a TPS summary (per-level lines plus total ops, wall duration, TPS, latency percentiles) to stdout and writes `pgledger-restful/build/reports/pgledger-stress-tps.txt` (override with `-Dpgledger.stress.report=...`, blank disables the file). Tunables: `-Dpgledger.stress.levels=50,100,200`, `-Dpgledger.stress.posts=20`, `-Dpgledger.stress.maxLagMs=15000`.

## Local stack (Compose)

| | Host | Port | Database | User | Password |
|---|---|---|---|---|---|
| Writer | localhost | 5432 | pgledger | pgledger | pgledger |
| Reader | localhost | 5433 | pgledger | pgledger | pgledger |

```bash
docker compose up -d
```

The API is `http://localhost:8080`. Writes use the writer. Balance and journal reads use the reader. Header `X-Pgledger-Role` is `writer` or `reader`. A business rejection is HTTP 422.

Each API process keeps an in-process cache of the four registries. It loads them at startup and refetches every 60 seconds. A request does not `SELECT` those tables. Only the refresh does. A row inserted in Postgres shows up on the next refresh, within a minute. A balance type created by this process is remembered immediately. Every instance needs `PGLEDGER_WRITER_JDBC_URL`, `PGLEDGER_READER_JDBC_URL`, `PGLEDGER_JDBC_USER`, and `PGLEDGER_JDBC_PASSWORD`. `PORT` defaults to 8080. Schema scripts run on the writer at startup and can run on every instance at the same time.

```bash
curl -s http://localhost:8080/health
curl -s -X POST http://localhost:8080/api/v1/balance-types \
  -H 'Content-Type: application/json' \
  -d '{"code":"AVAILABLE","name":"Available"}'
curl -s -X POST http://localhost:8080/api/v1/accounts \
  -H 'Content-Type: application/json' \
  -d '{"accountId":"CLIENT_ACC_001","balanceType":"AVAILABLE","currency":"USD"}'
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

The HTTP balance-type field accepts a code or a number. The API resolves a code from the cache and passes `balance_type_id` into `pgledger_create_transfer`. A number is passed through. Account and balance responses return codes. A transfer response returns the balance-type id that was stored.

There are no foreign keys. Hot tables store registry ids. A transfer stores the internal account id as text. An entry stores the internal account id and the transfer id as text. Adding a class, balance type, biz type, or currency is an `INSERT`. It is not a new SQL function. Function names are not table names (`pgledger_create_account` is fine, `pgledger_accounts` is not).

Amounts and balances are unbounded `NUMERIC`.

One balance row is `(account_id, balance_type_id, currency_id)`. `pgledger_transfers` is the journal. `pgledger_entries` is the journal line. A transfer always has two entries. `pgledger_create_transfers` locks every balance row in sorted internal id order inside one database call, so several legs commit or roll back together.

## Tables

### `pgledger_account_classes`

| Column | |
|---|---|
| `id` | `INT` identity, primary key. |
| `code` | Unique. |

Seeded ids: `1 CLIENT`, `2 COMPANY`, `3 BANK`, `4 NOSTRO`, `5 SUSPENSE`, `6 CONTROL`.

`pgledger_accounts.account_class_id` is `INT NOT NULL DEFAULT 1` (`CLIENT`). It is not a foreign key.

### `pgledger_balance_types`

| Column | |
|---|---|
| `id` | `INT` identity, primary key. |
| `code` | Unique. |
| `name` | Required display name. |
| `description` | Optional. |
| `allow_negative` | Sign policy for accounts on this type. Default `FALSE`. |
| `allow_positive` | Sign policy for accounts on this type. Default `TRUE`. |
| `created_at`, `updated_at` | Required. |

Seeded rows, `name` equal to `code`: `1 LIQUID`, `2 PENDING_INCOMING`, `3 PENDING_OUTGOING`, `4 COMPLIANCE_HOLD`, `5 GAS_FEE`. Seed policy: `GAS_FEE` is `(TRUE, TRUE)`; the other four are `(FALSE, TRUE)`.

`pgledger_accounts.balance_type_id` stores this id. Indexed. Not a foreign key.

### `pgledger_currencies`

| Column | |
|---|---|
| `id` | `INT` identity, primary key. |
| `code` | Unique. |
| `scale` | Required decimal places for that currency. |

Seeded rows: `1 USD` scale 2, `2 EUR` scale 2, `3 BTC` scale 8, `4 ETH` scale 18, `5 USDT` scale 6.

`pgledger_accounts.currency_id` stores this id. Indexed. Not a foreign key. Account create and posting reject a code that is not in this table.

### `pgledger_biz_types`

| Column | |
|---|---|
| `id` | `INT` identity, primary key. |
| `code` | Unique. |
| `name` | Required. |

Seeded rows: `1 TRANSFER` / `Transfer`, `2 DEPOSIT` / `Deposit`, `3 WITHDRAWAL` / `Withdrawal`.

`pgledger_transfers.biz_type_id` stores this id. Indexed. Not a foreign key. Posting rejects a code that is not in this table. The code is not inferred from which side is the bank.

### `pgledger_accounts`

`id` is the internal primary key (`pgla_...`). Transfers and entries store that id. `account_id` is the caller's business id. One row is one balance: unique `(account_id, balance_type_id, currency_id)`.

| Column | |
|---|---|
| `account_id`, `balance_type_id`, `currency_id` | The balance key. The last two are registry ids. |
| `name` | Display name. |
| `balance` | Unbounded `NUMERIC`, starts at 0. |
| `version` | Starts at 0. Each posting increments it. |
| `metadata` | Optional `JSONB` on the account. Transfers do not have metadata. |
| `account_class_id` | Registry id. Default `1` (`CLIENT`). |
| `deleted` | Soft delete, default `false`. The row stays. New transfers skip it. |

Sign policy lives on `pgledger_balance_types` (`allow_negative`, `allow_positive`). `pgledger_accounts_view` still returns `allow_negative_balance` and `allow_positive_balance` for the account: the type's flags, with both forced on for a `BANK` class account.

`pgledger_accounts_view` joins the registries and returns `balance_type`, `currency`, and `account_class` as codes.

### `pgledger_transfers`

`id` is `pglt_...`. `from_account_id` and `to_account_id` are `pgledger_accounts.id`, not the business `account_id`. `amount` is unbounded `NUMERIC`, must be positive, and the two sides must differ.

| Column | |
|---|---|
| `request_id` | `TEXT`, indexed, not unique. The idempotency key for one call. Every leg of that call stores the same value. A repeat returns those rows and does not post again. A different payload with the same id is rejected. |
| `biz_type_id` | Required id from `pgledger_biz_types`. A pairwise posting uses `TRANSFER`. A deposit uses `DEPOSIT`. A withdrawal uses `WITHDRAWAL`. |
| `biz_reference` | Optional caller business reference. Not unique. |
| `event_at`, `created_at` | `event_at` is the caller time, or `now()` when omitted. |

`pgledger_transfers_view` returns `biz_type` as the code.

### `pgledger_entries`

`id` is `pgle_...`. Two rows per transfer. `account_id` and `transfer_id` are internal ids. `amount` is unbounded `NUMERIC`, negative on the debit and positive on the credit. `account_previous_balance`, `account_current_balance`, and `account_version` record the balance row at posting time. The latest `account_version` matches `pgledger_accounts.version`. Entries do not store registry ids.

## Calls

Run these on the **writer**. `pgledger_create_transfer` and `pgledger_create_transfers` take `balance_type_id`. They do not look up `pgledger_balance_types`. Currency and `biz_type` inputs stay codes. A bad balance-type id fails on the account lookup.

### Balance type

`pgledger_create_balance_type(code, name, description, allow_negative, allow_positive)` returns the row, including `id`. Flags default to `(FALSE, TRUE)`. A duplicate code fails. A new balance type is that insert, not a new function.

```sql
SELECT * FROM pgledger_create_balance_type('AVAILABLE', 'Available', NULL);
SELECT * FROM pgledger_create_balance_type('LOCKED', 'Locked', NULL);
SELECT * FROM pgledger_create_balance_type('GAS_FEE_HOUSE', 'Gas fee', NULL, TRUE, TRUE);
```

### Currency

There is no currency function. Insert a row. `scale` is the number of decimal places. Omit `id` and the identity assigns it.

```sql
INSERT INTO pgledger_currencies (code, scale) VALUES ('EXAMPLE', 2);
```

### Biz type

There is no biz-type function. Insert a row. A coin deposit is `COIN_DEPOSIT` after this insert, posted with `pgledger_create_transfer` like any other movement.

```sql
INSERT INTO pgledger_biz_types (code, name) VALUES ('COIN_DEPOSIT', 'Coin deposit');
```

### Account class

There is no account-class function. Insert a row. `pgledger_create_account` looks the code up. An unknown code fails with `account_class not found`. Class `BANK` forces both balance signs true.

```sql
INSERT INTO pgledger_account_classes (code) VALUES ('EXAMPLE');
```

### Account

`pgledger_create_account` takes balance-type, currency, and account-class **codes**. It resolves each code once. A duplicate `(account_id, balance_type_id, currency_id)` fails.

```sql
SELECT * FROM pgledger_create_account(
    p_account_id => 'CLIENT_ACC_001',
    p_balance_type => 'AVAILABLE',
    p_name => 'Client available',
    p_currency => 'USD'
);

SELECT * FROM pgledger_create_account(
    p_account_id => 'CLIENT_ACC_001',
    p_balance_type => 'LOCKED',
    p_name => 'Client locked',
    p_currency => 'USD'
);

SELECT * FROM pgledger_create_account(
    p_account_id => 'CLIENT_ACC_002',
    p_balance_type => 'AVAILABLE',
    p_name => 'Client 2',
    p_currency => 'USD'
);

SELECT * FROM pgledger_create_account(
    p_account_id => 'LP_DESK',
    p_balance_type => 'AVAILABLE',
    p_name => 'LP desk',
    p_currency => 'USD',
    p_account_class => 'COMPANY'
);
```

`account_class` defaults to `CLIENT`. Pass `p_account_class => 'COMPANY'` for a house account. Soft-delete is `pgledger_delete_account(account_id, balance_type, currency)` or `POST /api/v1/accounts/delete`. Those arguments stay codes. The returned row is the view, so `balance_type`, `currency`, and `account_class` are codes.

### One transfer

`pgledger_create_transfer(from_account_id, from_balance_type_id, to_account_id, to_balance_type_id, currency, amount, event_at, biz_reference, request_id, biz_type)`.

`from_balance_type_id` and `to_balance_type_id` are integers. `currency` and `biz_type` are codes. `request_id` is required. `biz_type` defaults to `TRANSFER`. `biz_reference` and `event_at` may be null. The returned `biz_type` is the code. The balance-type columns on the transfer are the ids that were passed.

```sql
SELECT * FROM pgledger_create_transfer(
    'LP_DESK', (SELECT id FROM pgledger_balance_types WHERE code = 'AVAILABLE'),
    'CLIENT_ACC_001', (SELECT id FROM pgledger_balance_types WHERE code = 'AVAILABLE'),
    'USD', 100,
    NULL, NULL, 'fund-client-1'
);

SELECT * FROM pgledger_create_transfer(
    'LP_DESK', (SELECT id FROM pgledger_balance_types WHERE code = 'AVAILABLE'),
    'CLIENT_ACC_002', (SELECT id FROM pgledger_balance_types WHERE code = 'AVAILABLE'),
    'USD', 80,
    NULL, 'wire-80', 'fund-client-2', 'TRANSFER'
);
```

### Several transfers, one `request_id`

`pgledger_create_transfers(requests, event_at, biz_reference, request_id, biz_type)`. `transfer_request.from_balance_type` and `to_balance_type` are `INT`. Every leg stores that same `request_id` and `biz_type`. A repeat of the same call returns the original rows.

```sql
SELECT * FROM pgledger_create_transfers(
    p_transfer_requests => ARRAY[
        ('CLIENT_ACC_001', (SELECT id FROM pgledger_balance_types WHERE code = 'AVAILABLE'),
         'CLIENT_ACC_001', (SELECT id FROM pgledger_balance_types WHERE code = 'LOCKED'), 'USD', 40),
        ('CLIENT_ACC_002', (SELECT id FROM pgledger_balance_types WHERE code = 'AVAILABLE'),
         'LP_DESK', (SELECT id FROM pgledger_balance_types WHERE code = 'AVAILABLE'), 'USD', 25)
    ]::transfer_request[],
    p_request_id => 'rfq-1'
);
```

The first leg holds client 1 funds on `LOCKED`. The second leg pays the LP from client 2. After the fund and RFQ calls, client 1 `AVAILABLE` is 60 and `LOCKED` is 40.

## BANK pool

Account id `BANK` is the pool, not a stored row. The stored rows use account class `BANK`. `pgledger_create_transfer` and `pgledger_create_transfers` replace the sentinel with:

`BANK-{currency}-{balanceType}-{n}`

`currency` and `balanceType` in that string are codes. `n` is `hash(request_id) % poolSize`, in `0 .. poolSize-1`. The chosen shard is locked with `FOR UPDATE` and the call waits. There is no `SKIP LOCKED`. The same helper is used for every currency. If the pool does not exist yet, the transfer creates it at size 8 and keeps that size afterwards. `pgledger_ensure_bank_pool(currency, balance_type_code, balance_type_id, pool_size, keep_existing)` can create a different size first. It stores the id and does not read `pgledger_balance_types`. `PGLEDGER_BANK_POOL_SIZE` is that size for the API (default 8).

A deposit debits the shard and credits the client. A withdrawal is the reverse. Set `biz_type` yourself. The balance-type arguments below are ids.

```sql
SELECT * FROM pgledger_create_transfer(
    'BANK', (SELECT id FROM pgledger_balance_types WHERE code = 'AVAILABLE'),
    'CLIENT_ACC_001', (SELECT id FROM pgledger_balance_types WHERE code = 'AVAILABLE'),
    'USD', 100,
    NULL, 'wire-100', 'dep-1', 'DEPOSIT'
);

SELECT * FROM pgledger_create_transfer(
    'CLIENT_ACC_001', (SELECT id FROM pgledger_balance_types WHERE code = 'AVAILABLE'),
    'BANK', (SELECT id FROM pgledger_balance_types WHERE code = 'AVAILABLE'),
    'USD', 40,
    NULL, NULL, 'wd-1', 'WITHDRAWAL'
);
```

BANK shards may be negative. `pgledger_bank_position(balance_type, currency)` takes codes and is the sum of the BANK rows for that balance type and currency. The HTTP deposit and withdrawal routes do this same transfer, resolving the balance-type id from the cache.

## Get balance

Run on the **reader**. The view returns codes.

```sql
SELECT account_id, balance_type, currency, balance, version
FROM pgledger_accounts_view
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
JOIN pgledger_accounts_view a ON a.id = e.account_id
WHERE a.account_id = 'CLIENT_ACC_001'
ORDER BY e.account_version, e.id;
```
