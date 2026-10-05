# pg_ledger design brief

Short facts for another session. Setup and HTTP usage: root `README.md`. Tables and SQL: `docs/schema-and-sql.md`.

Postgres only. No Kafka. No Redis.

Four registries, each an integer `id` plus a unique `code`:

- `pgledger_account_classes`
- `pgledger_balance_types`
- `pgledger_biz_types`
- `pgledger_currencies` (`scale` is decimal places)

Hot tables store those ids: `pgledger_accounts.account_class_id` (default 1, CLIENT), `balance_type_id`, `currency_id`, and `pgledger_transfers.biz_type_id`. No foreign keys. A new class, balance type, biz type, or currency is an `INSERT`. Entries do not store registry ids.

The API keeps an in-process cache of the four registries. Load at startup. Refetch every 60 seconds. A request does not `SELECT` those tables. A row inserted in Postgres shows up on the next refresh. A balance type created by this process is remembered immediately.

`pgledger_create_transfer` and `pgledger_create_transfers` take `balance_type_id`. They do not read `pgledger_balance_types`. Currency and `biz_type` inputs stay codes. A bad balance-type id fails on the account lookup. Account create still takes codes and resolves them once.

`request_id` is `TEXT`, indexed, not unique. One `pgledger_create_transfers` call writes the same `request_id` on every leg. A repeat returns those rows. `biz_reference` is an optional transfer reference and is not unique. Transfers have no metadata. Account `metadata` is `JSONB`.

Account id `BANK` is the pool. Stored rows are class `BANK`. Shard business id is `BANK-{currency code}-{balance type code}-{n}`, where `n = hash(request_id) % poolSize`. Default pool size is **8**; max is **400**. First empty-pool create (SQL `pgledger_resolve_account` / Java `PGLEDGER_BANK_POOL_SIZE` / `pgledger_ensure_bank_pool`) uses 8. Existing pools stay sticky when `keep_existing` is true. The chosen shard is locked with `FOR UPDATE` and the call waits. No `SKIP LOCKED`.

Amounts and balances are unbounded `NUMERIC`.

Function names are not table names. `pgledger_create_account` is fine. A function named `pgledger_accounts` is not.

Seeds (explicit ids, then `setval` to `MAX(id)`):

- Classes: 1 CLIENT, 2 COMPANY, 3 BANK, 4 NOSTRO, 5 SUSPENSE, 6 CONTROL
- Balance types (`name` = `code`): 1 LIQUID, 2 PENDING_INCOMING, 3 PENDING_OUTGOING, 4 COMPLIANCE_HOLD, 5 GAS_FEE
- Biz types: 1 TRANSFER / Transfer, 2 DEPOSIT / Deposit, 3 WITHDRAWAL / Withdrawal
- Currencies: 1 USD scale 2, 2 EUR scale 2, 3 BTC scale 8, 4 ETH scale 18, 5 USDT scale 6

Smoke, on a fresh database (scripts use `CREATE TABLE IF NOT EXISTS`):

```bash
sudo -u postgres psql -v ON_ERROR_STOP=1 -c "DROP DATABASE IF EXISTS pgledger_smoke;" -c "CREATE DATABASE pgledger_smoke;"
sudo -u postgres psql -v ON_ERROR_STOP=1 -d pgledger_smoke \
  -f db/V001__ledger.sql -f db/V002__functions.sql -f db/smoke_test.sql
```

With Docker already migrated: `docker compose exec -T writer psql -U pgledger -d pgledger -v ON_ERROR_STOP=1 < db/smoke_test.sql`.
