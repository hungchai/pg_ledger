# pg_ledger

Postgres ledger. Writes go to the writer. Balance and entry reads go to the reader.

| | Host | Port | Database | User | Password |
|---|---|---|---|---|---|
| Writer | localhost | 5432 | pgledger | pgledger | pgledger |
| Reader | localhost | 5433 | pgledger | pgledger | pgledger |

```bash
docker compose up -d
```

One balance row is `(account_id, balance_type, currency)`. `pgledger_transfers` is the journal. `pgledger_entries` is the journal line. A transfer always has two entries. `pgledger_create_transfers` locks every balance row in sorted internal id order inside one database call, so several RFQ legs commit or roll back together.

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
