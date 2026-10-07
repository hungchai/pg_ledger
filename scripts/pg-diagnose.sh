#!/usr/bin/env bash
# Sample PG wait events + pg_stat_statements during a k6 run.
# Usage: ./scripts/pg-diagnose.sh <duration_seconds> [interval_seconds]
set -euo pipefail
W="docker exec pgledger-writer psql -U pgledger -d pgledger -tAc"
DUR="${1:-300}"
ITV="${2:-5}"
END=$((SECONDS + DUR))

echo "sampling wait events every ${ITV}s for ${DUR}s..."
: > /tmp/pg-waits.log
while [[ $SECONDS -lt $END ]]; do
  TS=$(date +%H:%M:%S)
  $W "SELECT coalesce(wait_event_type||':'||wait_event,'running'), count(*)
      FROM pg_stat_activity
      WHERE datname='pgledger' AND state='active'
      GROUP BY 1 ORDER BY 2 DESC" 2>/dev/null \
    | paste -sd'|' - | sed "s/^/${TS} /" >> /tmp/pg-waits.log
  sleep "$ITV"
done

echo
echo "=== wait-event histogram (whole window) ==="
awk '{$1=""; print}' /tmp/pg-waits.log | tr '|' '\n' | sed 's/^ //' \
  | awk -F: '{n=$NF; sub(/:[^:]*$/,"",$0)} {print}' | sort | uniq -c | sort -rn | head -12

echo
echo "=== top statements by total time ==="
$W "SELECT calls, round(mean_exec_time::numeric,2) AS mean_ms, round(total_exec_time::numeric,0) AS total_ms, left(regexp_replace(query,'[[:space:]]+',' ','g'),70)
    FROM pg_stat_statements
    ORDER BY total_exec_time DESC LIMIT 8" 2>/dev/null

echo
echo "=== io timing ==="
$W "SELECT round(blk_read_time::numeric,1) AS read_ms, round(blk_write_time::numeric,1) AS write_ms, buffers_backend_fsync
    FROM pg_stat_database WHERE datname='pgledger'"
