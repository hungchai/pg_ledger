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
  -d '{"fromAccountId":"LP_DESK","fromBalanceType":"AVAILABLE","toAccountId":"CLIENT_ACC_001","toBalanceType":"AVAILABLE","currency":"USD","amount":100}'
curl -s -X POST http://localhost:8080/api/v1/deposits \
  -H 'Content-Type: application/json' \
  -d '{"requestId":"dep-1","accountId":"CLIENT_ACC_001","balanceType":"AVAILABLE","currency":"USD","amount":100}'
curl -s -X POST http://localhost:8080/api/v1/withdrawals \
  -H 'Content-Type: application/json' \
  -d '{"requestId":"wd-1","accountId":"CLIENT_ACC_001","balanceType":"AVAILABLE","currency":"USD","amount":40}'
curl -s 'http://localhost:8080/api/v1/balances?accountId=CLIENT_ACC_001&balanceType=AVAILABLE&currency=USD'
curl -s 'http://localhost:8080/api/v1/journals?page=0&size=50'
```

One balance row is `(account_id, balance_type, currency)`. `pgledger_transfers` is the journal. `pgledger_entries` is the journal line. A transfer always has two entries. `pgledger_create_transfers` locks every balance row in sorted internal id order inside one database call, so several RFQ legs commit or roll back together.

`account_class` is `CLIENT`, `COMPANY`, `BANK`, `NOSTRO`, `SUSPENSE`, or `CONTROL`. Existing rows default to `CLIENT`. `deleted` is a soft delete (`false` by default). A deleted row stays in the journal. New transfers skip it.

A deposit is one `pgledger_transfers` row from a BANK account to the client, with `biz_type` `DEPOSIT`. A withdrawal is the reverse, with `biz_type` `WITHDRAWAL`. A pairwise posting sets `biz_type` `TRANSFER`. The business type is stored on the transfer and is not inferred from which side is the bank. Shard account ids are `BANK-{currency}-{balanceType}-{n}` for `n` in `0 .. poolSize-1` (default 8, `PGLEDGER_BANK_POOL_SIZE`). The shard is `hash(requestId) % poolSize`. That account and the client are locked in sorted internal id order. `request_id` is unique and nullable on `pgledger_transfers`. The same cash `requestId` returns the original transfer. BANK shards may be negative. The bank position is the sum of the BANK rows for that balance type and currency. Soft-delete is `POST /api/v1/accounts/delete`.

Run the statements below on the **writer**. Repeat creates fail if that balance type or account row already exists.

## Create a balance type

```sql
SELECT * FROM pgledger_create_balance_type('AVAILABLE', 'Available', NULL);
SELECT * FROM pgledger_create_balance_type('LOCKED', 'Locked', NULL);
```

## Create an account

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

## Fund the clients

```sql
SELECT * FROM pgledger_create_transfer(
    'LP_DESK', 'AVAILABLE',
    'CLIENT_ACC_001', 'AVAILABLE',
    'USD', 100,
    NULL, NULL
);

SELECT * FROM pgledger_create_transfer(
    'LP_DESK', 'AVAILABLE',
    'CLIENT_ACC_002', 'AVAILABLE',
    'USD', 80,
    NULL, NULL
);
```

## Multi-transfer RFQ

One call, two fills against the same LP. The first leg holds client 1 funds on `LOCKED`. The second leg pays the LP from client 2.

```sql
SELECT * FROM pgledger_create_transfers(ARRAY[
    ('CLIENT_ACC_001', 'AVAILABLE', 'CLIENT_ACC_001', 'LOCKED', 'USD', 40),
    ('CLIENT_ACC_002', 'AVAILABLE', 'LP_DESK', 'AVAILABLE', 'USD', 25)
]::transfer_request[]);
```

## Get balance

Run on the **reader**.

```sql
SELECT account_id, balance_type, currency, balance, version
FROM pgledger_accounts
WHERE account_id = 'CLIENT_ACC_001'
ORDER BY balance_type, currency;
```

After the samples, client 1 `AVAILABLE` is 60 and `LOCKED` is 40.

## Get entries

Run on the **reader**. `amount` is negative on the debit leg and positive on the credit leg. `account_version` matches `pgledger_accounts.version` on the latest row for that balance.

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
