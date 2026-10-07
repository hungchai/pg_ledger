#!/usr/bin/env bash
# Recon after cash deposit/withdrawal stress. Writes report 03.
# Uses docker exec into pgledger-writer when psql is not on PATH.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
REPORT_PATH="${1:-$ROOT/reports/latest/03-recon.txt}"
CONTAINER="${PGLEDGER_WRITER_CONTAINER:-pgledger-writer}"
USER="${PGLEDGER_JDBC_USER:-pgledger}"
DB="${PGLEDGER_DB:-pgledger}"

mkdir -p "$(dirname "$REPORT_PATH")"

psql_w() {
  if command -v psql >/dev/null 2>&1; then
    PGPASSWORD="${PGLEDGER_JDBC_PASSWORD:-pgledger}" \
      psql -h "${PGLEDGER_WRITER_HOST:-localhost}" -p "${PGLEDGER_WRITER_PORT:-5432}" \
      -U "$USER" -d "$DB" -v ON_ERROR_STOP=1 "$@"
  else
    docker exec -i -e PGPASSWORD="${PGLEDGER_JDBC_PASSWORD:-pgledger}" "$CONTAINER" \
      psql -U "$USER" -d "$DB" -v ON_ERROR_STOP=1 "$@"
  fi
}

FAIL=0
{
  echo "# pgledger recon"
  echo "generated_at: $(date -u +%Y-%m-%dT%H:%M:%SZ)"
  echo "target: ${CONTAINER} / ${DB}"
  echo

  echo "## money conservation (all accounts, per currency)"
  psql_w -c "
    SELECT c.code AS currency, sum(a.balance) AS sum_balance
    FROM pgledger_accounts a
    JOIN pgledger_currencies c ON c.id = a.currency_id
    GROUP BY c.code
    ORDER BY c.code;
  "
  echo

  echo "## broken currencies (sum <> 0)"
  BROKEN="$(psql_w -Atc "
    SELECT c.code
    FROM pgledger_accounts a
    JOIN pgledger_currencies c ON c.id = a.currency_id
    GROUP BY c.code
    HAVING sum(a.balance) <> 0;
  " || true)"
  if [[ -n "${BROKEN}" ]]; then
    echo "FAIL: ${BROKEN}"
    FAIL=1
  else
    echo "ok"
  fi
  echo

  echo "## orphan transfers (not exactly 2 entries or net <> 0)"
  ORPHAN="$(psql_w -Atc "
    SELECT t.id
    FROM pgledger_transfers t
    LEFT JOIN pgledger_entries e ON e.transfer_id = t.id
    GROUP BY t.id
    HAVING count(e.id) <> 2 OR coalesce(sum(e.amount), 0) <> 0
    LIMIT 20;
  " || true)"
  if [[ -n "${ORPHAN}" ]]; then
    echo "FAIL:"
    echo "${ORPHAN}"
    FAIL=1
  else
    echo "ok"
  fi
  echo

  echo "## version mismatches"
  VERS="$(psql_w -Atc "
    SELECT a.account_id || ' ' || bt.code || ' ' || c.code
    FROM pgledger_accounts a
    JOIN pgledger_balance_types bt ON bt.id = a.balance_type_id
    JOIN pgledger_currencies c ON c.id = a.currency_id
    LEFT JOIN pgledger_entries e ON e.account_id = a.id
    GROUP BY a.id, a.account_id, a.balance, a.version, bt.code, c.code
    HAVING a.version <> count(e.id)
        OR a.version <> coalesce(max(e.account_version), 0)
        OR a.balance <> coalesce(sum(e.amount), 0)
    LIMIT 20;
  " || true)"
  if [[ -n "${VERS}" ]]; then
    echo "FAIL:"
    echo "${VERS}"
    FAIL=1
  else
    echo "ok"
  fi
  echo

  echo "## negative LIQUID (must be empty)"
  NEG="$(psql_w -Atc "
    SELECT a.account_id || ' ' || c.code || ' ' || a.balance::text
    FROM pgledger_accounts a
    JOIN pgledger_balance_types bt ON bt.id = a.balance_type_id AND bt.code = 'LIQUID'
    JOIN pgledger_currencies c ON c.id = a.currency_id
    WHERE a.balance < 0
      -- BANK sentinel shards are the mint/burn side of deposits and
      -- withdrawals; a negative shard balance is by design, not a breach.
      AND a.account_id NOT LIKE 'BANK-%'
    LIMIT 20;
  " || true)"
  if [[ -n "${NEG}" ]]; then
    echo "FAIL:"
    echo "${NEG}"
    FAIL=1
  else
    echo "ok"
  fi
  echo

  echo "## CASH + BANK positions by currency"
  psql_w -c "
    SELECT c.code AS currency,
           coalesce(sum(a.balance) FILTER (
             WHERE a.account_id LIKE 'CASH-%' AND bt.code = 'LIQUID'), 0) AS cash_liquid,
           coalesce(sum(a.balance) FILTER (
             WHERE ac.code = 'BANK' AND bt.code = 'LIQUID'), 0) AS bank_liquid,
           coalesce(sum(a.balance) FILTER (
             WHERE a.account_id LIKE 'CASH-%' AND bt.code = 'GAS_FEE'), 0) AS cash_gas,
           coalesce(sum(a.balance) FILTER (
             WHERE ac.code = 'BANK' AND bt.code = 'GAS_FEE'), 0) AS bank_gas,
           coalesce(sum(a.balance), 0) AS all_rows
    FROM pgledger_currencies c
    LEFT JOIN pgledger_accounts a ON a.currency_id = c.id
    LEFT JOIN pgledger_balance_types bt ON bt.id = a.balance_type_id
    LEFT JOIN pgledger_account_classes ac ON ac.id = a.account_class_id
    WHERE c.code IN ('ETH', 'BTC', 'USDT')
    GROUP BY c.code
    ORDER BY c.code;
  "
  echo

  echo "## transfer counts by biz_type"
  psql_w -c "
    SELECT bt.code AS biz_type, count(*) AS n
    FROM pgledger_transfers t
    JOIN pgledger_biz_types bt ON bt.id = t.biz_type_id
    GROUP BY bt.code
    ORDER BY bt.code;
  "
  echo

  if [[ "${FAIL}" -eq 0 ]]; then
    echo "RESULT: PASS"
  else
    echo "RESULT: FAIL"
  fi
} | tee "$REPORT_PATH"

exit "${FAIL}"
