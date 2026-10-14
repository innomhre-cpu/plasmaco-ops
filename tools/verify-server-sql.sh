#!/usr/bin/env bash
# Checks the server's PostgreSQL schema and SQL against a real, throwaway PostgreSQL instance:
#   - applies the Flyway migrations (backend/src/main/resources/db/migration) in order;
#   - prepares every statement in StoreSql (backend/.../web/JdbcServerStore.kt), so syntax, column names and types are
#     checked by PostgreSQL itself;
#   - runs the store's write/read paths and the schema's guarantees (versions, change-feed order, idempotency,
#     case-insensitive usernames, append-only audit log and history, commit-ordered sequence numbers under concurrency).
# It does not run the Kotlin JDBC code (no JDBC driver here); `./gradlew :backend:test` does that with Testcontainers.
#
# Usage: tools/verify-server-sql.sh        (PG_BIN defaults to /usr/lib/postgresql/16/bin)
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PG_BIN="${PG_BIN:-$(ls -d /usr/lib/postgresql/*/bin 2>/dev/null | sort -V | tail -1)}"
[ -x "$PG_BIN/initdb" ] || { echo "PostgreSQL server binaries not found; set PG_BIN"; exit 2; }
WORK="$(mktemp -d)"
PORT="${PG_PORT:-55432}"
# PostgreSQL refuses to run as root; use the postgres account when the script is run as root.
AS=()
if [ "$(id -u)" = 0 ]; then chown postgres "$WORK"; AS=(runuser -u postgres --); fi
stop() { "${AS[@]}" "$PG_BIN/pg_ctl" -D "$WORK/data" -m immediate stop >/dev/null 2>&1 || true; rm -rf "$WORK"; }
trap stop EXIT

"${AS[@]}" "$PG_BIN/initdb" -D "$WORK/data" -U postgres --auth=trust -E UTF8 --locale=C.UTF-8 >/dev/null
"${AS[@]}" "$PG_BIN/pg_ctl" -D "$WORK/data" -l "$WORK/server.log" -w \
  -o "-c listen_addresses='' -c unix_socket_directories=$WORK -p $PORT -c TimeZone=Africa/Kigali" start >/dev/null
echo "== PostgreSQL $("$PG_BIN/postgres" --version | grep -o '[0-9][0-9.]*' | head -1) started"
PG_BIN="$PG_BIN" PGHOST="$WORK" PGPORT="$PORT" PGUSER=postgres python3 "$ROOT/tools/sql/verify_store_sql.py" "$ROOT"
