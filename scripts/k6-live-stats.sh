#!/usr/bin/env bash
# Live TPS / avg-ms / p95-ms / error-rate for a running k6 run (Prometheus RW backend).
# Reads k6_* remote-write metrics via Prom instant/rate queries; read-only watcher.
#
# Usage:
#   ./scripts/k6-live-stats.sh                        # newest testid per scenario (deposit|withdrawal|rfq)
#   ./scripts/k6-live-stats.sh deposit-20260930-195547  # one exact testid
#   ./scripts/k6-live-stats.sh -n 5                    # print 5 samples then exit
#
# Env: PROM_URL (default http://127.0.0.1:9090), INTERVAL (10s), WINDOW (30s)
set -euo pipefail

PROM="${PROM_URL:-http://127.0.0.1:9090}"
INTERVAL="${INTERVAL:-10}"
WINDOW="${WINDOW:-30s}"
MAX_LINES="${MAX_LINES:-0}"   # 0 = forever

usage() {
  echo "Usage: $0 [testid] [-n N]"
  echo "  PROM_URL=$PROM  INTERVAL=${INTERVAL}s  WINDOW=${WINDOW}"
  exit 1
}

PREFIX=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    -n) MAX_LINES="$2"; shift 2 ;;
    -h|--help) usage ;;
    -*) usage ;;
    *) PREFIX="$1" ;;
  esac
done

query() { curl -sG --max-time 5 "$PROM/api/v1/query" --data-urlencode "query=$1"; }

jsonval() { python3 -c 'import json,sys
d = json.load(sys.stdin)
r = d["data"]["result"]
print(r[0]["value"][1] if r else "")'; }

# Auto-discover: newest testid per scenario family (deposit|withdrawal|rfq).
if [[ -z "$PREFIX" ]]; then
  PREFIX="$(query 'count(k6_http_reqs_total) by (testid)' | python3 -c 'import json,sys
d = json.load(sys.stdin)
xs = [x["metric"]["testid"] for x in d["data"]["result"]]
latest = {}
for x in xs:
    fam = x.split("-")[0]
    latest[fam] = max(latest.get(fam, ""), x)
print(" ".join(latest.values()))')"
fi
[[ -n "$PREFIX" ]] || { echo "no k6 metrics in Prometheus yet"; exit 1; }

echo "watching: ${PREFIX}"
echo "interval ${INTERVAL}s  window ${WINDOW}  prom ${PROM}  (ctrl-c to stop)"
printf '%-9s %-24s %8s %9s %9s %7s %10s\n' time testid tps avg_ms p95_ms errpct iters

printed=0
while :; do
  for tid in $PREFIX; do
    ok_tps=$(query "sum(rate(k6_http_reqs_total{testid=\"$tid\",expected_response=\"true\"}[$WINDOW]))" | jsonval)
    all_tps=$(query "sum(rate(k6_http_reqs_total{testid=\"$tid\"}[$WINDOW]))" | jsonval)
    avg=$(query "avg(k6_http_req_duration_avg{testid=\"$tid\",expected_response=\"true\"}) * 1000" | jsonval)
    p95=$(query "max(k6_http_req_duration_p95{testid=\"$tid\",expected_response=\"true\"}) * 1000" | jsonval)
    iters=$(query "sum(k6_http_reqs_total{testid=\"$tid\"})" | jsonval)
    err=$(python3 -c "print(f'{(1 - (${ok_tps:-0} / ${all_tps:-1})) * 100:.2f}' if ${all_tps:-0} else '0.00')")
    fmt() { python3 -c 'import sys; print(f"{float(sys.argv[1]):{sys.argv[2]}}")' "$1" "$2" 2>/dev/null || echo "-"; }
    printf '%-9s %-24s %8s %9s %9s %7s %10s\n' \
      "$(date +%H:%M:%S)" "$tid" \
      "$(fmt "${ok_tps:-0}" '8.0f')" "$(fmt "${avg:--1}" '9.1f')" "$(fmt "${p95:--1}" '9.1f')" "${err:-0.00}" "${iters:--}"
  done
  printed=$((printed + 1))
  [[ "$MAX_LINES" -gt 0 && "$printed" -ge "$MAX_LINES" ]] && break
  sleep "$INTERVAL"
done
