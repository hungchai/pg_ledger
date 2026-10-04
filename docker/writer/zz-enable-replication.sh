#!/bin/bash
# Runs once on an empty writer volume, after V001 and V002 (psql -f keeps $fn$ quotes).
# Opens streaming replication to other containers on this Docker network.
set -euo pipefail

marker="${PGDATA}/.replication-enabled"
if [ -f "$marker" ]; then
  exit 0
fi

cat >> "${PGDATA}/pg_hba.conf" <<'EOF'
# pgledger local streaming replica
host replication all 0.0.0.0/0 scram-sha-256
host replication all ::/0 scram-sha-256
EOF

pg_ctl -D "$PGDATA" reload
touch "$marker"
