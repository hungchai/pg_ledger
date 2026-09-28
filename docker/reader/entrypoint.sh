#!/bin/bash
# Physical streaming replica of the writer. Does not run initdb.
set -euo pipefail

PGDATA="${PGDATA:-/var/lib/postgresql/data}"
PRIMARY_HOST="${PRIMARY_HOST:-writer}"
PRIMARY_PORT="${PRIMARY_PORT:-5432}"
PGUSER="${POSTGRES_USER:-pgledger}"
PGPASSWORD="${POSTGRES_PASSWORD:-pgledger}"
PGDATABASE="${POSTGRES_DB:-pgledger}"
SLOT="pgledger_reader"

if [ "$(id -u)" = "0" ]; then
  mkdir -p "$PGDATA"
  chown postgres:postgres "$PGDATA"
  chmod 700 "$PGDATA"
  exec gosu postgres /bin/bash /reader-entrypoint.sh
fi

export PGDATA PGUSER PGPASSWORD PGDATABASE

clone() {
  local create_slot="$1"
  local args=(-h "$PRIMARY_HOST" -p "$PRIMARY_PORT" -U "$PGUSER" -D "$PGDATA" -Fp -Xs -P -R -w -S "$SLOT")
  if [ "$create_slot" = "yes" ]; then
    args+=(-C)
  fi
  pg_basebackup "${args[@]}"
}

if [ ! -f "$PGDATA/PG_VERSION" ] || [ ! -f "$PGDATA/standby.signal" ]; then
  echo "pgledger reader: cloning ${PRIMARY_HOST}:${PRIMARY_PORT}"
  cloned=0
  for ((attempt = 1; attempt <= 40; attempt++)); do
    find "$PGDATA" -mindepth 1 -delete
    if clone yes; then
      cloned=1
      break
    fi
    find "$PGDATA" -mindepth 1 -delete
    if clone no; then
      cloned=1
      break
    fi
    echo "pgledger reader: basebackup attempt ${attempt} failed, retrying"
    find "$PGDATA" -mindepth 1 -delete
    psql -w -h "$PRIMARY_HOST" -p "$PRIMARY_PORT" -U "$PGUSER" -d "$PGDATABASE" -c \
      "SELECT pg_drop_replication_slot(slot_name) FROM pg_replication_slots WHERE slot_name = '${SLOT}' AND NOT active;" \
      || true
    sleep 2
  done
  if [ "$cloned" -ne 1 ]; then
    echo "pgledger reader: could not clone the writer" >&2
    exit 1
  fi
  cat >> "$PGDATA/postgresql.auto.conf" <<EOF
primary_conninfo = 'host=${PRIMARY_HOST} port=${PRIMARY_PORT} user=${PGUSER} password=${PGPASSWORD} application_name=${SLOT}'
hot_standby = on
hot_standby_feedback = on
EOF
  touch "$PGDATA/standby.signal"
  echo "pgledger reader: clone finished"
fi

exec postgres -c hot_standby=on -c hot_standby_feedback=on -c max_connections=400
