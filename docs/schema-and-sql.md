# Schema and SQL reference

Deep reference for tables, seeds, and Postgres functions. For setup and HTTP usage, see the root [README](../README.md).

## Design rules

- **No foreign keys.** Hot tables store registry ids (`INT`). Adding a class, balance type, biz type, or currency is an `INSERT`, not a new SQL function.
- **Function names are not table names.** `pgledger_create_account` is fine; a function named `pgledger_accounts` is not.
- **Amounts** are unbounded `NUMERIC`.
- **One balance row** = `(account_id, balance_type_id, currency_id)`.
- **`pgledger_transfers`** is the journal header; **`pgledger_entries`** are the lines (always two per transfer).
- **`pgledger_create_transfers`** locks every balance row in sorted internal-id order in one DB call so multi-leg posts commit or roll back together.
- Transfer functions take **`balance_type_id` (INT)**. They do not look up `pgledger_balance_types`. Currency and `biz_type` stay codes. A bad balance-type id fails on account lookup.

Run write functions on the **writer**. Run balance/entry queries on the **reader**.

---

## Registries

### `pgledger_account_classes`

| Column | Type / notes |
|--------|----------------|
| `id` | `INT` identity, primary key |
| `code` | Unique |

Seeded: `1 CLIENT`, `2 COMPANY`, `3 BANK`, `4 NOSTRO`, `5 SUSPENSE`, `6 CONTROL`.

`pgledger_accounts.account_class_id` is `INT NOT NULL DEFAULT 1` (`CLIENT`). Not a foreign key.

There is no account-class function. Insert a row; `pgledger_create_account` looks the code up. Unknown code → `account_class not found`. Class `BANK` forces both balance signs true.

```sql
INSERT INTO pgledger_account_classes (code) VALUES ('EXAMPLE');
```

### `pgledger_balance_types`

| Column | Type / notes |
|--------|----------------|
| `id` | `INT` identity, primary key |
| `code` | Unique |
| `name` | Required display name |
| `description` | Optional |
| `allow_negative` | Sign policy. Default `FALSE` |
| `allow_positive` | Sign policy. Default `TRUE` |
| `created_at`, `updated_at` | Required |

Seeded (`name` = `code`): `1 LIQUID`, `2 PENDING_INCOMING`, `3 PENDING_OUTGOING`, `4 COMPLIANCE_HOLD`, `5 GAS_FEE`.

Sign policy: `GAS_FEE` is `(TRUE, TRUE)`; the other four are `(FALSE, TRUE)`.

`pgledger_accounts.balance_type_id` stores this id (indexed, not a FK).

```sql
SELECT * FROM pgledger_create_balance_type('AVAILABLE', 'Available', NULL);
SELECT * FROM pgledger_create_balance_type('LOCKED', 'Locked', NULL);
SELECT * FROM pgledger_create_balance_type('GAS_FEE_HOUSE', 'Gas fee', NULL, TRUE, TRUE);
```

`pgledger_create_balance_type(code, name, description, allow_negative, allow_positive)` returns the row including `id`. Flags default to `(FALSE, TRUE)`. Duplicate code fails.

### `pgledger_currencies`

| Column | Type / notes |
|--------|----------------|
| `id` | `INT` identity, primary key |
| `code` | Unique |
| `scale` | Required decimal places |

Seeded: `1 USD` scale 2, `2 EUR` scale 2, `3 BTC` scale 8, `4 ETH` scale 18, `5 USDT` scale 6.

`pgledger_accounts.currency_id` stores this id (indexed, not a FK). Account create and posting reject unknown codes.

There is no currency function:

```sql
INSERT INTO pgledger_currencies (code, scale) VALUES ('EXAMPLE', 2);
```

### `pgledger_biz_types`

| Column | Type / notes |
|--------|----------------|
| `id` | `INT` identity, primary key |
| `code` | Unique |
| `name` | Required |

Seeded: `1 TRANSFER` / Transfer, `2 DEPOSIT` / Deposit, `3 WITHDRAWAL` / Withdrawal.

`pgledger_transfers.biz_type_id` stores this id (indexed, not a FK). Posting rejects unknown codes. Biz type is not inferred from which side is the bank.

```sql
INSERT INTO pgledger_biz_types (code, name) VALUES ('COIN_DEPOSIT', 'Coin deposit');
```

---

## Hot tables

### `pgledger_accounts`

- `id` — internal PK (ULID text, time-ordered). Transfers and entries store this id.
- `account_id` — caller business id.
- One row = one balance: unique `(account_id, balance_type_id, currency_id)`.

| Column | Notes |
|--------|--------|
| `account_id`, `balance_type_id`, `currency_id` | Balance key; last two are registry ids |
| `name` | Display name |
| `balance` | Unbounded `NUMERIC`, starts at 0 |
| `version` | Starts at 0; increments on each posting |
| `metadata` | Optional `JSONB` on the account (transfers have no metadata) |
| `account_class_id` | Registry id; default `1` (`CLIENT`) |
| `deleted` | Soft delete; row stays; new transfers skip it |

Sign policy lives on `pgledger_balance_types`. `pgledger_accounts_view` returns `allow_negative_balance` / `allow_positive_balance` from the type, with both forced on for `BANK` class accounts. The view also returns `balance_type`, `currency`, and `account_class` as codes.

### `pgledger_transfers`

- `id` — ULID text, time-ordered; `seq` — global ledger order
- `from_account_id` / `to_account_id` — internal `pgledger_accounts.id`, not business `account_id`
- `amount` — unbounded `NUMERIC`, must be positive; sides must differ

| Column | Notes |
|--------|--------|
| `request_id` | `TEXT`, indexed, not unique. Idempotency key for one call. Every leg shares it. Repeat returns existing rows. Different payload + same id is rejected. |
| `biz_type_id` | Required. Pairwise posting → `TRANSFER`; deposit → `DEPOSIT`; withdrawal → `WITHDRAWAL`. |
| `biz_reference` | Optional caller reference; not unique |
| `event_at`, `created_at` | `event_at` is caller time, or `now()` when omitted |

`pgledger_transfers_view` returns `biz_type` as the code.

### `pgledger_entries`

- `id` — ULID text, time-ordered
- Two rows per transfer
- `account_id` / `transfer_id` — internal ids
- `amount` — unbounded `NUMERIC`; negative on debit, positive on credit
- `account_previous_balance`, `account_current_balance`, `account_version` — balance snapshot at posting time
- Latest `account_version` matches `pgledger_accounts.version`
- Entries do not store registry ids

---

## Account create / delete

`pgledger_create_account` takes balance-type, currency, and account-class **codes**. Duplicate `(account_id, balance_type_id, currency_id)` fails. `account_class` defaults to `CLIENT`.

```sql
SELECT * FROM pgledger_create_account(
    p_account_id => 'CLIENT_ACC_001',
    p_balance_type => 'AVAILABLE',
    p_name => 'Client available',
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

Soft-delete: `pgledger_delete_account(account_id, balance_type, currency)` or `POST /api/v1/accounts/delete`. Arguments stay codes. Returned row is the view (codes, not ids).

---

## Transfers

### One transfer

```text
pgledger_create_transfer(
  from_account_id, from_balance_type_id,
  to_account_id, to_balance_type_id,
  currency, amount, event_at, biz_reference, request_id, biz_type
)
```

- Balance-type args are **integers**; `currency` and `biz_type` are **codes**
- `request_id` is required; `biz_type` defaults to `TRANSFER`
- `biz_reference` and `event_at` may be null
- Returned `biz_type` is the code; balance-type columns are the ids that were passed

```sql
SELECT * FROM pgledger_create_transfer(
    'LP_DESK', (SELECT id FROM pgledger_balance_types WHERE code = 'AVAILABLE'),
    'CLIENT_ACC_001', (SELECT id FROM pgledger_balance_types WHERE code = 'AVAILABLE'),
    'USD', 100,
    NULL, NULL, 'fund-client-1'
);
```

### Multi-leg (one `request_id`)

```text
pgledger_create_transfers(requests, event_at, biz_reference, request_id, biz_type)
```

`transfer_request.from_balance_type` / `to_balance_type` are `INT`. Every leg stores the same `request_id` and `biz_type`. A repeat returns the original rows.

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

---

## BANK pool

Account id `BANK` is a sentinel, not a stored row. Stored shards use account class `BANK`. Transfer functions replace the sentinel with:

```text
BANK-{currency}-{balanceType}-{n}
```

where `currency` / `balanceType` are codes and `n = hash(request_id) % poolSize` (`0 .. poolSize-1`). The chosen shard is locked with `FOR UPDATE` (no `SKIP LOCKED`).

If the pool does not exist, the transfer creates it at size **8** and keeps that size. Pool size must be between 1 and **400**. Create a different size first with:

```text
pgledger_ensure_bank_pool(currency, balance_type_code, balance_type_id, pool_size, keep_existing)
```

API env `PGLEDGER_BANK_POOL_SIZE` sets that size (default 8, max 400). An existing pool stays sticky when `keep_existing` is true.

- Deposit: debit shard, credit client, `biz_type = DEPOSIT`
- Withdrawal: reverse, `biz_type = WITHDRAWAL`
- BANK shards may be negative
- `pgledger_bank_position(balance_type, currency)` (codes) = sum of BANK rows for that type/currency

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

---

## Read queries (reader)

### Balance

```sql
SELECT account_id, balance_type, currency, balance, version
FROM pgledger_accounts_view
WHERE account_id = 'CLIENT_ACC_001'
ORDER BY balance_type, currency;
```

### Entries

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
