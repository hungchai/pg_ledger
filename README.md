# pg_ledger

Stateless double-entry ledger API backed by **Postgres only** — no Kafka, no Redis.

Writes go to the primary (**writer**). Balance and journal reads go to a streaming replica (**reader**).

| | |
|---|---|
| Stack | Java 21, Spring Boot, Postgres 16 |
| Local demo | Docker Compose (writer + reader + API + Prometheus + Grafana) |
| Schema | `db/V001__ledger.sql`, `db/V002__functions.sql` (idempotent) |

## Table of contents

1. [Architecture](#architecture)
2. [Quick start](#quick-start)
3. [HTTP API](#http-api)
4. [Business cases](#business-cases)
5. [Configuration](#configuration)
6. [Deployment](#deployment)
7. [Tests](#tests)
8. [Load / stress](#load--stress)
9. [Schema reference](#schema-reference)
10. [Performance tuning](#performance-tuning)
11. [Observability](#observability)

---

## Architecture

```text
Client ──HTTP──► API (stateless)
                   │
                   ├─ writes ──► writer (Postgres primary :5432)
                   └─ reads  ──► reader (streaming replica :5433)
```

- Every API instance is **stateless**; scale horizontally behind a load balancer (no sticky sessions).
- Registry cache is in-process (refresh every 60s). A type created on one instance is visible there immediately; other instances see it after refresh.
- Response header `X-Pgledger-Role` is `writer` or `reader`.
- Business rejection → HTTP **422**.
- Amounts are unbounded `NUMERIC`. Balance-type fields accept a **code** or numeric **id** (codes resolved from cache).

Do **not** point the writer URL at a replica. Writer and reader URLs may be identical only for single-node embeds/tests.

---

## Quick start

**Prerequisites:** Docker Engine + Compose v2.

```bash
docker compose up -d --build
docker compose ps   # wait until writer, reader, and api are healthy
curl -s http://127.0.0.1:8080/health
```

| Service | URL |
|---------|-----|
| API | http://127.0.0.1:8080 |
| Writer Postgres | `localhost:5432` / db `pgledger` / user+pass `pgledger` |
| Reader Postgres | `localhost:5433` (same credentials) |
| Prometheus | http://127.0.0.1:9090 |
| Grafana | http://127.0.0.1:3000 (`admin` / `pgledger`) |

```bash
# stop (keep data)
docker compose down

# wipe volumes and re-run initdb (schema + replication)
docker compose down -v && docker compose up -d --build
```

Compose is a **local / demo** stack (default passwords, exposed Postgres ports) — not a hardened production topology.

---

## HTTP API

### Endpoints

| Method | Path | Role | Description |
|--------|------|------|-------------|
| `GET` | `/health` | — | Liveness |
| `POST` | `/api/v1/balance-types` | writer | Create balance type |
| `GET` | `/api/v1/balance-types` | reader | List balance types |
| `POST` | `/api/v1/accounts` | writer | Create account balance |
| `POST` | `/api/v1/accounts/delete` | writer | Soft-delete account balance |
| `POST` | `/api/v1/postings` | writer | Transfer between accounts |
| `POST` | `/api/v1/deposits` | writer | Credit from BANK pool |
| `POST` | `/api/v1/withdrawals` | writer | Debit to BANK pool |
| `GET` | `/api/v1/balances?accountId=&balanceType=&currency=` | reader | Single balance |
| `GET` | `/api/v1/accounts/{accountId}/balances` | reader | All balances for account |
| `GET` | `/api/v1/journals?page=&size=` | reader | Journal page |

---

## Business cases

Base URL: `http://127.0.0.1:8080`. Examples use seeded types `LIQUID` and `GAS_FEE`; create `LOCKED` for RFQ hold. Compose must be up and `/health` OK.

### 0. Setup accounts

Create client balances and (for RFQ) a house LP desk + `LOCKED` type.

```bash
# Optional hold bucket for RFQ (not seeded)
curl -s -X POST http://127.0.0.1:8080/api/v1/balance-types \
  -H 'Content-Type: application/json' \
  -d '{"code":"LOCKED","name":"Locked"}'

# Client: LIQUID + GAS_FEE (USDT)
for type in LIQUID GAS_FEE LOCKED; do
  curl -s -X POST http://127.0.0.1:8080/api/v1/accounts \
    -H 'Content-Type: application/json' \
    -d "{\"accountId\":\"CLIENT_ACC_001\",\"balanceType\":\"$type\",\"currency\":\"USDT\",\"name\":\"Client 1 $type\"}"
done

# Second client + LP desk (RFQ pay leg)
curl -s -X POST http://127.0.0.1:8080/api/v1/accounts \
  -H 'Content-Type: application/json' \
  -d '{"accountId":"CLIENT_ACC_002","balanceType":"LIQUID","currency":"USDT","name":"Client 2"}'

curl -s -X POST http://127.0.0.1:8080/api/v1/accounts \
  -H 'Content-Type: application/json' \
  -d '{"accountId":"LP_DESK","balanceType":"LIQUID","currency":"USDT","name":"LP desk","accountClass":"COMPANY"}'
```

Duplicate create returns HTTP **422** (idempotent enough for re-runs).

### 1. Deposit

External cash in: debit `BANK` shard, credit client `LIQUID`. Biz type is `DEPOSIT`.

```text
BANK (LIQUID) ──100 USDT──► CLIENT_ACC_001 (LIQUID)
```

```bash
curl -s -X POST http://127.0.0.1:8080/api/v1/deposits \
  -H 'Content-Type: application/json' \
  -d '{
    "requestId": "dep-1",
    "accountId": "CLIENT_ACC_001",
    "balanceType": "LIQUID",
    "currency": "USDT",
    "amount": 100
  }'

# Fund client 2 for RFQ pay leg
curl -s -X POST http://127.0.0.1:8080/api/v1/deposits \
  -H 'Content-Type: application/json' \
  -d '{"requestId":"dep-2","accountId":"CLIENT_ACC_002","balanceType":"LIQUID","currency":"USDT","amount":80}'

curl -s 'http://127.0.0.1:8080/api/v1/balances?accountId=CLIENT_ACC_001&balanceType=LIQUID&currency=USDT'
```

Same `requestId` + same payload replays the original transfer (no double credit).

### 2. Withdrawal with gas fee

Matches `k6/withdrawal.js`: reserve estimated gas on the client, withdraw principal to `BANK`, then settle gas to the bank `GAS_FEE` bucket (settle may leave client `GAS_FEE` negative — type allows both signs).

```text
1) Reserve   CLIENT (LIQUID) ──1──► CLIENT (GAS_FEE)
2) Principal CLIENT (LIQUID) ──10─► BANK   (LIQUID)     POST /withdrawals
3) Settle    CLIENT (GAS_FEE) ──2──► BANK   (GAS_FEE)   POST /postings
```

```bash
REF=wd-demo-1

# 1) Reserve gas on the same client
curl -s -X POST http://127.0.0.1:8080/api/v1/postings \
  -H 'Content-Type: application/json' \
  -d "{
    \"requestId\": \"${REF}-reserve\",
    \"fromAccountId\": \"CLIENT_ACC_001\",
    \"fromBalanceType\": \"LIQUID\",
    \"toAccountId\": \"CLIENT_ACC_001\",
    \"toBalanceType\": \"GAS_FEE\",
    \"currency\": \"USDT\",
    \"amount\": 1,
    \"bizReference\": \"${REF}\",
    \"bizType\": \"TRANSFER\"
  }"

# 2) Withdraw principal to BANK
curl -s -X POST http://127.0.0.1:8080/api/v1/withdrawals \
  -H 'Content-Type: application/json' \
  -d "{
    \"requestId\": \"${REF}-principal\",
    \"accountId\": \"CLIENT_ACC_001\",
    \"balanceType\": \"LIQUID\",
    \"currency\": \"USDT\",
    \"amount\": 10
  }"

# 3) Settle gas to BANK GAS_FEE (amount may exceed reserve)
curl -s -X POST http://127.0.0.1:8080/api/v1/postings \
  -H 'Content-Type: application/json' \
  -d "{
    \"requestId\": \"${REF}-settle\",
    \"fromAccountId\": \"CLIENT_ACC_001\",
    \"fromBalanceType\": \"GAS_FEE\",
    \"toAccountId\": \"BANK\",
    \"toBalanceType\": \"GAS_FEE\",
    \"currency\": \"USDT\",
    \"amount\": 2,
    \"bizReference\": \"${REF}\",
    \"bizType\": \"TRANSFER\"
  }"

curl -s 'http://127.0.0.1:8080/api/v1/accounts/CLIENT_ACC_001/balances'
```

After the three steps (starting from 100 USDT LIQUID): LIQUID ≈ 89, GAS_FEE ≈ −1 (1 reserved − 2 settled).

### 3. RFQ (multi-leg fill)

RFQ hold + pay in **one atomic DB call** via `pgledger_create_transfers` (same `request_id` on every leg). HTTP `/api/v1/postings` is single-leg only — use SQL for atomic RFQ, or two sequential posts if eventual consistency across legs is OK.

```text
Leg A  CLIENT_ACC_001 (LIQUID) ──40──► CLIENT_ACC_001 (LOCKED)   hold buyer funds
Leg B  CLIENT_ACC_002 (LIQUID) ──25──► LP_DESK        (LIQUID)   pay LP
```

Run on the **writer**:

```bash
docker compose exec -T writer psql -U pgledger -d pgledger <<'SQL'
SELECT * FROM pgledger_create_transfers(
    p_transfer_requests => ARRAY[
        ('CLIENT_ACC_001',
         (SELECT id FROM pgledger_balance_types WHERE code = 'LIQUID'),
         'CLIENT_ACC_001',
         (SELECT id FROM pgledger_balance_types WHERE code = 'LOCKED'),
         'USDT', 40),
        ('CLIENT_ACC_002',
         (SELECT id FROM pgledger_balance_types WHERE code = 'LIQUID'),
         'LP_DESK',
         (SELECT id FROM pgledger_balance_types WHERE code = 'LIQUID'),
         'USDT', 25)
    ]::transfer_request[],
    p_request_id => 'rfq-1'
);
SQL

curl -s 'http://127.0.0.1:8080/api/v1/accounts/CLIENT_ACC_001/balances'
curl -s 'http://127.0.0.1:8080/api/v1/balances?accountId=LP_DESK&balanceType=LIQUID&currency=USDT'
```

Replay with the same `p_request_id` returns the original rows and does not post again.

**HTTP approximation** (not atomic across legs):

```bash
curl -s -X POST http://127.0.0.1:8080/api/v1/postings \
  -H 'Content-Type: application/json' \
  -d '{"requestId":"rfq-http-hold","fromAccountId":"CLIENT_ACC_001","fromBalanceType":"LIQUID","toAccountId":"CLIENT_ACC_001","toBalanceType":"LOCKED","currency":"USDT","amount":40,"bizType":"TRANSFER"}'

curl -s -X POST http://127.0.0.1:8080/api/v1/postings \
  -H 'Content-Type: application/json' \
  -d '{"requestId":"rfq-http-pay","fromAccountId":"CLIENT_ACC_002","fromBalanceType":"LIQUID","toAccountId":"LP_DESK","toBalanceType":"LIQUID","currency":"USDT","amount":25,"bizType":"TRANSFER"}'
```

### 4. Inspect journal

```bash
curl -s 'http://127.0.0.1:8080/api/v1/journals?page=0&size=50'
```

More SQL detail: [docs/schema-and-sql.md](docs/schema-and-sql.md).

---

## Configuration

Set the same variables on every API replica:

| Variable | Required | Default | Meaning |
|----------|----------|---------|---------|
| `PGLEDGER_WRITER_JDBC_URL` | yes | — | Primary JDBC URL |
| `PGLEDGER_READER_JDBC_URL` | yes | — | Replica JDBC URL (may equal writer for single-node) |
| `PGLEDGER_JDBC_USER` | yes | — | DB user |
| `PGLEDGER_JDBC_PASSWORD` | yes | — | DB password |
| `PGLEDGER_JDBC_POOL_SIZE` | no | `10` | Hikari max pool size per role (writer and reader each) |
| `PGLEDGER_BANK_POOL_SIZE` | no | `8` | BANK shard pool size when auto-created |
| `PORT` | no | `8080` | HTTP listen port |

Compose sets writer/reader to service hostnames `writer` / `reader`. Outside Compose, point them at your primary and standby, e.g. `jdbc:postgresql://db-primary:5432/pgledger`.

### Schema lifecycle

- Empty Compose volumes: Postgres runs `db/V001__ledger.sql` and `db/V002__functions.sql` on first init.
- Every API process also applies those scripts on the **writer** at startup (idempotent; safe with concurrent starts).
- Breaking change on an old volume: `docker compose down -v`, then bring the stack back up.
- Timestamps are `TIMESTAMPTZ` (UTC). DB default timezone is `UTC`. Reconnect DBeaver after migrate/wipe so the session shows `+00` / `Z`.

---

## Deployment

### Jar against existing Postgres

Requires JDK 21 (build) / JRE 21 (run), and a Postgres 16 primary (+ optional hot standby).

```bash
./gradlew :pgledger-restful:bootJar
export PGLEDGER_WRITER_JDBC_URL=jdbc:postgresql://localhost:5432/pgledger
export PGLEDGER_READER_JDBC_URL=jdbc:postgresql://localhost:5433/pgledger
export PGLEDGER_JDBC_USER=pgledger
export PGLEDGER_JDBC_PASSWORD=pgledger
java -jar pgledger-restful/build/libs/pgledger-restful-*.jar
```

### API image only

Build context must include `gradle/wrapper/gradle-wrapper.jar`.

```bash
docker build -t pgledger-api .
docker run --rm -p 8080:8080 \
  -e PGLEDGER_WRITER_JDBC_URL=jdbc:postgresql://host.docker.internal:5432/pgledger \
  -e PGLEDGER_READER_JDBC_URL=jdbc:postgresql://host.docker.internal:5433/pgledger \
  -e PGLEDGER_JDBC_USER=pgledger \
  -e PGLEDGER_JDBC_PASSWORD=pgledger \
  pgledger-api
```

---

## Tests

| Suite | Postgres | Command |
|-------|----------|---------|
| Core (`PgLedgerTest`) | Embedded Postgres (zonky, no Docker) | `./gradlew :pgledger-core:test` |
| REST (`PgLedgerRestTest`) | Embedded Postgres (single primary for writer+reader URLs) | `./gradlew :pgledger-restful:test` |
| Stress (`PgLedgerStressTest`) | Compose on `5432` / `5433` | `docker compose up -d` then `./gradlew :pgledger-restful:stressTest` |

Core and REST do not need Docker. `stressTest` is excluded from `:pgledger-restful:test`.

Stress prints a TPS summary to stdout and writes `pgledger-restful/build/reports/pgledger-stress-tps.txt` (override with `-Dpgledger.stress.report=...`; blank disables the file).

Tunables: `-Dpgledger.stress.levels=50,100,200`, `-Dpgledger.stress.posts=20`, `-Dpgledger.stress.maxLagMs=15000`.

---

## Load / stress

One-shot wipe, start Compose (API + Prometheus + Grafana), run deposit → withdrawal (gas reserve/settle) → RFQ (one `CO_RFQ` dealer), then recon. Needs `docker`, `k6`, and `curl`.

```bash
# defaults: 20 VUs, 60s, 100 accounts
./scripts/k6-cash-stress.sh

./scripts/k6-cash-stress.sh --vus 50 --duration 2m
./scripts/k6-cash-stress.sh --vus 100 --duration 120m
./scripts/k6-cash-stress.sh --vus 20 --duration 60s --no-wipe --no-build
./scripts/k6-cash-stress.sh --help
```

| Flag | Default | Meaning |
|------|---------|---------|
| `--vus N` | `20` | Virtual users |
| `--duration M` | `60s` | k6 duration **per scenario** (`60s`, `120m`, …) |
| `--accounts N` | `100` | `CASH-000` / `RFQ-000` … × ETH/BTC/USDT |
| `--base-url URL` | `http://127.0.0.1:8080` | API base |
| `--no-wipe` | off | Skip `docker compose down -v` |
| `--no-build` | off | `compose up` without `--build` |
| `--fund-rounds N` | `30` | Seed deposits per CASH account/ccy before withdrawal |

Reports under `reports/<run-id>/` (also mirrored to `reports/latest/`):

1. `01-deposit.txt` — deposit TPS / latency
2. `02-withdrawal.txt` — withdraw + `LIQUID→GAS_FEE` reserve + settle
3. `03-rfq.txt` — multi-leg RFQ vs one company dealer `CO_RFQ`
4. `04-recon.txt` — money conservation / orphans / versions

Grafana dashboard **pgledger**: pick testid `deposit-*`, `withdrawal-*`, or `rfq-*` for API TPS.

Recon alone (stack already up): `./scripts/recon.sh` or `./scripts/recon.sh reports/latest/04-recon.txt`.

---

## Schema reference

Registries, hot tables, BANK pool, and SQL function examples live in:

**[docs/schema-and-sql.md](docs/schema-and-sql.md)**

Short model:

| Concept | Table / mechanism |
|---------|-------------------|
| Registries | `account_classes`, `balance_types`, `currencies`, `biz_types` (id + code) |
| Balance | `pgledger_accounts` — one row per `(account_id, balance_type, currency)` |
| Journal | `pgledger_transfers` + two `pgledger_entries` per transfer |
| Idempotency | `request_id` on transfers |
| External cash | Sentinel account `BANK` → hashed shard pool |

Design notes for agents/sessions: [docs/ledger-ai-context.md](docs/ledger-ai-context.md).

---

## Performance tuning

Knobs and code paths that actually move throughput or latency here. Measure with the [stress suite](#tests) and the Grafana **pgledger** dashboard before/after.

### Connection pools (start here)

- `PGLEDGER_JDBC_POOL_SIZE` (default `10`, Compose sets `100`) is the Hikari max **per role** — writer and reader each. Total API sessions ≈ `2 × pool × instances`; keep it under Postgres `max_connections` (Compose writer: `400`).
- Reads are cheap and offloaded to the replica — the reader pool can usually be smaller than the writer pool.
- Pool exhaustion shows up as latency spikes, not errors. Watch Hikari `pending` / connection wait in metrics.

### Contention (the real TPS ceiling)

- `pgledger_create_transfers` locks every touched balance row **in sorted internal-id order** (`FOR UPDATE`), so multi-leg posts serialize per-account, not globally. Hotspot ceiling = one account pair.
- The `BANK` sentinel fans out to `BANK-{currency}-{type}-{n}` shards (`n = hash(request_id) % poolSize`, locked `FOR UPDATE`, **no `SKIP LOCKED`**). If deposits/withdrawals queue behind each other, raise `PGLEDGER_BANK_POOL_SIZE` **before first use** — the pool is created once and keeps its size (`pgledger_ensure_bank_pool(..., keep_existing => false)` to force a resize).
- Same-account same-balance contention (RFQ hold + pay hitting one balance) serializes by design; keep hold/pay legs on **different balance types** (`LIQUID` vs `LOCKED`) where possible.

### Postgres writer

| Knob | Why |
|------|-----|
| `synchronous_commit = off` | Latency win on deposits/withdrawals if you can tolerate a small commit-loss window. Do **not** do this for a real ledger without understanding the tradeoff. |
| `max_connections` | Size for `2 × JDBC pool × API instances` + exporters + admin. |
| `shared_buffers`, `wal_keep_size` | Compose defaults are demo-sized; raise for real volume. |
| `hot_standby` feedback / replication lag | Reader lag directly delays balance visibility — monitor `pg_stat_replication` on the writer (exporter panel). |

### Reads

- Balance and journal reads hit the **reader** — scale read throughput by adding replicas, not writer capacity.
- Registry lookups (balance type / currency / biz type / class) never hit the DB per-request; they come from the in-process cache refreshed every 60s. New registry rows are visible only after refresh — don't "fix" slow-looking lookups that aren't there.
- Journal pages use `(created_at DESC, id DESC)` — deep pages (`page` high) get slow; prefer cursoring by time window if you page far back.

### JVM

- The API is Spring Boot on JDK 21. Keep heaps small-to-moderate; the write path is JDBC-bound, not allocation-bound. Virtual threads (used by the stress client) help open-connection concurrency, not lock contention.

---

## Observability

- Grafana dashboard **pgledger** (provisioned under `grafana/`) — http://127.0.0.1:3000, login `admin` / `pgledger`
- Postgres exporters: writer `:9187`, reader `:9188`
- Cash stress reports: `./scripts/k6-cash-stress.sh` → `reports/<run-id>/`
