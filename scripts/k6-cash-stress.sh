#!/usr/bin/env bash
# One-shot: wipe DB, start stack, run deposit + withdrawal + RFQ k6, then recon.
#
# Usage (same shape as jraft test-cycle.sh):
#   ./scripts/k6-cash-stress.sh
#   ./scripts/k6-cash-stress.sh --vus 100 --duration 120m
#   ./scripts/k6-cash-stress.sh --vus 20 --duration 60s --no-wipe --no-build
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

VUS="${VUS:-20}"
DURATION="${DURATION:-60s}"
ACCOUNTS="${ACCOUNTS:-100}"
BASE_URL="${BASE_URL:-http://127.0.0.1:8080}"
SKIP_WIPE="${SKIP_WIPE:-0}"
SKIP_BUILD="${SKIP_BUILD:-0}"
PROM_RW="${K6_PROMETHEUS_RW_SERVER_URL:-http://127.0.0.1:9090/api/v1/write}"
RUN_ID="${RUN_ID:-$(date +%Y%m%d-%H%M%S)}"
REPORT_DIR="${REPORT_DIR:-$ROOT/reports/$RUN_ID}"
FUND_ROUNDS="${FUND_ROUNDS:-30}"

usage() {
  echo "Usage: $0 [--vus N] [--duration M] [--accounts N] [--base-url URL]"
  echo "         [--no-wipe] [--no-build] [--fund-rounds N]"
  echo "  --vus N           Virtual users (default: ${VUS})"
  echo "  --duration M      k6 duration per scenario, e.g. 60s / 120m (default: ${DURATION})"
  echo "  --accounts N      CASH + RFQ accounts (default: ${ACCOUNTS})"
  echo "  --base-url URL    API base (default: ${BASE_URL})"
  echo "  --no-wipe         Keep docker volumes"
  echo "  --no-build        compose up without --build"
  echo "  --fund-rounds N   Seed deposits per CASH account/ccy before withdrawal (default: 30)"
  exit 1
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --vus) VUS="$2"; shift 2 ;;
    --duration) DURATION="$2"; shift 2 ;;
    --accounts) ACCOUNTS="$2"; shift 2 ;;
    --base-url) BASE_URL="$2"; shift 2 ;;
    --no-wipe) SKIP_WIPE=1; shift ;;
    --no-build) SKIP_BUILD=1; shift ;;
    --fund-rounds) FUND_ROUNDS="$2"; shift 2 ;;
    -h|--help) usage ;;
    *) echo "unknown arg: $1" >&2; usage ;;
  esac
done

need() {
  command -v "$1" >/dev/null 2>&1 || {
    echo "missing dependency: $1" >&2
    exit 1
  }
}

need docker
need k6
need curl

mkdir -p "$REPORT_DIR"
mkdir -p "$ROOT/reports"
rm -rf "$ROOT/reports/latest"
ln -sfn "$RUN_ID" "$ROOT/reports/latest"

echo "==> VUs=${VUS} duration=${DURATION} accounts=${ACCOUNTS}"
echo "==> reports → $REPORT_DIR"
echo "==> Grafana  http://127.0.0.1:3000  (admin / pgledger)"
echo "==> API      $BASE_URL"

if [[ "${SKIP_WIPE}" != "1" ]]; then
  echo "==> wipe volumes"
  docker compose down -v --remove-orphans
fi

echo "==> start stack"
if [[ "${SKIP_BUILD}" == "1" ]]; then
  docker compose up -d --wait
else
  docker compose up -d --build --wait
fi

echo "==> wait /health"
for i in $(seq 1 90); do
  if curl -sf "$BASE_URL/health" >/dev/null; then
    break
  fi
  if [[ "$i" -eq 90 ]]; then
    echo "API not healthy at $BASE_URL" >&2
    docker compose logs api --tail 100 >&2 || true
    exit 1
  fi
  sleep 2
done

export BASE_URL VUS DURATION ACCOUNTS FUND_ROUNDS
export K6_PROMETHEUS_RW_SERVER_URL="$PROM_RW"
export K6_PROMETHEUS_RW_TREND_STATS="${K6_PROMETHEUS_RW_TREND_STATS:-p(50),p(95),p(99),avg}"

TESTID_DEP="deposit-${RUN_ID}"
TESTID_WD="withdrawal-${RUN_ID}"
TESTID_RFQ="rfq-${RUN_ID}"

echo "==> k6 deposit (testid=$TESTID_DEP)"
REPORT_PATH="$REPORT_DIR/01-deposit.txt" \
  k6 run -e "VUS=${VUS}" -e "DURATION=${DURATION}" -e "ACCOUNTS=${ACCOUNTS}" \
    -o experimental-prometheus-rw --tag "testid=${TESTID_DEP}" "$ROOT/k6/deposit.js" \
  | tee "$REPORT_DIR/01-deposit.console.txt"

echo "==> k6 withdrawal (testid=$TESTID_WD)"
REPORT_PATH="$REPORT_DIR/02-withdrawal.txt" \
  k6 run -e "VUS=${VUS}" -e "DURATION=${DURATION}" -e "ACCOUNTS=${ACCOUNTS}" -e "FUND_ROUNDS=${FUND_ROUNDS}" \
    -o experimental-prometheus-rw --tag "testid=${TESTID_WD}" "$ROOT/k6/withdrawal.js" \
  | tee "$REPORT_DIR/02-withdrawal.console.txt"

echo "==> k6 rfq (testid=$TESTID_RFQ) company=CO_RFQ"
REPORT_PATH="$REPORT_DIR/03-rfq.txt" \
  k6 run -e "VUS=${VUS}" -e "DURATION=${DURATION}" -e "ACCOUNTS=${ACCOUNTS}" \
    -o experimental-prometheus-rw --tag "testid=${TESTID_RFQ}" "$ROOT/k6/rfq.js" \
  | tee "$REPORT_DIR/03-rfq.console.txt"

echo "==> recon"
RECON_FAIL=0
if ! "$ROOT/scripts/recon.sh" "$REPORT_DIR/04-recon.txt"; then
  echo "recon FAILED — see $REPORT_DIR/04-recon.txt" >&2
  RECON_FAIL=1
fi

cat > "$REPORT_DIR/README.md" <<EOF
# pgledger stress — ${RUN_ID}

| # | Report | File |
|---|---|---|
| 1 | Deposit | \`01-deposit.txt\` |
| 2 | Withdrawal (+ gas) | \`02-withdrawal.txt\` |
| 3 | RFQ (one company \`CO_RFQ\`) | \`03-rfq.txt\` |
| 4 | Recon | \`04-recon.txt\` |

- Grafana: http://127.0.0.1:3000 (admin / pgledger)
- testid: \`${TESTID_DEP}\` / \`${TESTID_WD}\` / \`${TESTID_RFQ}\`
- VUs=${VUS} DURATION=${DURATION} ACCOUNTS=${ACCOUNTS}
EOF

echo
echo "=== reports ==="
echo "1) $REPORT_DIR/01-deposit.txt"
echo "2) $REPORT_DIR/02-withdrawal.txt"
echo "3) $REPORT_DIR/03-rfq.txt"
echo "4) $REPORT_DIR/04-recon.txt"
echo

exit "${RECON_FAIL}"
