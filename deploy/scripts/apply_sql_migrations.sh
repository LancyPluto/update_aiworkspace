#!/usr/bin/env bash
# Apply pending sql/*.sql migrations against the running MySQL container.
#
# Idempotent + tracked: every applied file is recorded in `_sql_migration_log`,
# so re-runs only execute files that have never succeeded. Designed to be called
# from the CD pipeline (ci_remote_deploy_light.sh) on every deploy, BEFORE the app
# containers are rebuilt, so the new schema exists before the backend boots.
#
# Safe to run by hand on dev or prod:
#   bash deploy/scripts/apply_sql_migrations.sh
#
# Env overrides (all optional, defaults match deploy/docker-compose.yml):
#   MYSQL_CONTAINER (ai-supermarket-mysql)
#   MYSQL_USER (root)  MYSQL_PASS (root123456)  MYSQL_DB (ai_supermarket_v1)
#   SQL_DIR (repo sql/ resolved from this script's location)
set -uo pipefail

MYSQL_CONTAINER="${MYSQL_CONTAINER:-ai-supermarket-mysql}"
MYSQL_USER="${MYSQL_USER:-root}"
MYSQL_PASS="${MYSQL_PASS:-root123456}"
MYSQL_DB="${MYSQL_DB:-ai_supermarket_v1}"

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
SQL_DIR="${SQL_DIR:-$(cd "$SCRIPT_DIR/../../sql" && pwd)}"

MYSQL_CLI="mysql --default-character-set=utf8mb4"

# Query helper (suppresses the cosmetic password-on-cli warning).
mysql_q() {
  docker exec "$MYSQL_CONTAINER" $MYSQL_CLI -u"$MYSQL_USER" -p"$MYSQL_PASS" "$@" 2>&1 \
    | grep -v "Using a password" || true
}

echo "==> Waiting for MySQL ($MYSQL_CONTAINER) to accept connections"
ready=0
for i in $(seq 1 60); do
  if docker exec "$MYSQL_CONTAINER" mysqladmin ping -h127.0.0.1 -u"$MYSQL_USER" -p"$MYSQL_PASS" --silent >/dev/null 2>&1; then
    ready=1
    break
  fi
  sleep 2
done
if [ "$ready" != "1" ]; then
  echo "ERROR: MySQL did not become ready in time" >&2
  exit 1
fi

echo "==> Ensure migration log table"
mysql_q "$MYSQL_DB" -e "
CREATE TABLE IF NOT EXISTS _sql_migration_log (
  name VARCHAR(255) NOT NULL PRIMARY KEY,
  applied_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
"

echo "==> Apply pending SQL files from $SQL_DIR (sorted)"
applied=0
skipped=0
failed=0
failed_files=""
shopt -s nullglob
for f in $(ls -1 "$SQL_DIR"/*.sql 2>/dev/null | sort); do
  base=$(basename "$f")
  exists=$(mysql_q "$MYSQL_DB" -N -e "SELECT COUNT(*) FROM _sql_migration_log WHERE name='$base';" | tail -1)
  if [ "${exists:-0}" = "1" ]; then
    skipped=$((skipped+1))
    continue
  fi

  # 001_init bootstraps the whole schema; if `users` already exists the DB was
  # initialized out-of-band — record it as done instead of re-running.
  if [ "$base" = "001_init_v1.sql" ]; then
    has_users=$(mysql_q "$MYSQL_DB" -N -e "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='$MYSQL_DB' AND table_name='users';" | tail -1)
    if [ "${has_users:-0}" = "1" ]; then
      echo "  SKIP $base (schema already initialized)"
      mysql_q "$MYSQL_DB" -e "INSERT IGNORE INTO _sql_migration_log (name) VALUES ('$base');"
      skipped=$((skipped+1))
      continue
    fi
  fi

  echo "  RUN $base"
  out=$(docker exec -i "$MYSQL_CONTAINER" $MYSQL_CLI -u"$MYSQL_USER" -p"$MYSQL_PASS" "$MYSQL_DB" < "$f" 2>&1)
  code=$?
  if [ $code -eq 0 ]; then
    mysql_q "$MYSQL_DB" -e "INSERT INTO _sql_migration_log (name) VALUES ('$base');"
    applied=$((applied+1))
  elif echo "$out" | grep -qiE 'Duplicate (column|key|entry)|already exists|1060|1061|1062'; then
    # Schema-changing file partly applied before tracking existed: treat the
    # "already there" outcome as success so it stops retrying every deploy.
    echo "    (idempotent skip) $(echo "$out" | grep -v 'Using a password' | head -1)"
    mysql_q "$MYSQL_DB" -e "INSERT IGNORE INTO _sql_migration_log (name) VALUES ('$base');"
    applied=$((applied+1))
  else
    echo "    FAILED: $(echo "$out" | grep -v 'Using a password' | head -4)"
    failed=$((failed+1))
    failed_files="$failed_files $base"
  fi
done

echo "SQL migration summary: applied=$applied skipped=$skipped failed=$failed"
if [ "$failed" -gt 0 ]; then
  echo "::error::SQL migrations failed:$failed_files" >&2
  exit 1
fi
