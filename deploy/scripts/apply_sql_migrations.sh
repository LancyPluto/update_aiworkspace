#!/usr/bin/env bash
# Apply immutable sql/*.sql migrations and record their SHA-256 checksums.
set -euo pipefail

MYSQL_CONTAINER="${MYSQL_CONTAINER:-ai-supermarket-mysql}"
MYSQL_USER="${MYSQL_USER:-root}"
MYSQL_PASS="${MYSQL_PASS:-root123456}"
MYSQL_DB="${MYSQL_DB:-ai_supermarket_v1}"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
SQL_DIR="${SQL_DIR:-$(cd "$SCRIPT_DIR/../../sql" && pwd)}"
VERIFY_ONLY="${VERIFY_ONLY:-false}"

mysql_q() {
  docker exec -e MYSQL_PWD="$MYSQL_PASS" "$MYSQL_CONTAINER" \
    mysql --default-character-set=utf8mb4 -u"$MYSQL_USER" "$@" 2>&1 \
    | sed '/Using a password/d'
}

file_checksum() {
  sha256sum "$1" | awk '{print $1}'
}

echo "==> Waiting for MySQL ($MYSQL_CONTAINER)"
ready=0
for _ in $(seq 1 60); do
  if docker exec -e MYSQL_PWD="$MYSQL_PASS" "$MYSQL_CONTAINER" \
      mysqladmin ping -h127.0.0.1 -u"$MYSQL_USER" --silent >/dev/null 2>&1; then
    ready=1
    break
  fi
  sleep 2
done
if [ "$ready" != "1" ]; then
  echo "ERROR: MySQL did not become ready in time" >&2
  exit 1
fi

mysql_q "$MYSQL_DB" -e "
CREATE TABLE IF NOT EXISTS _sql_migration_log (
  name VARCHAR(255) NOT NULL PRIMARY KEY,
  checksum_sha256 CHAR(64) NULL,
  execution_ms BIGINT NULL,
  applied_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
"

for column in checksum_sha256 execution_ms; do
  exists="$(mysql_q "$MYSQL_DB" -N -e "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema='${MYSQL_DB}' AND table_name='_sql_migration_log' AND column_name='${column}';" | tail -1)"
  if [ "$exists" != "1" ]; then
    if [ "$column" = "checksum_sha256" ]; then
      mysql_q "$MYSQL_DB" -e "ALTER TABLE _sql_migration_log ADD COLUMN checksum_sha256 CHAR(64) NULL AFTER name;"
    else
      mysql_q "$MYSQL_DB" -e "ALTER TABLE _sql_migration_log ADD COLUMN execution_ms BIGINT NULL AFTER checksum_sha256;"
    fi
  fi
done

echo "==> Verify and apply SQL files from $SQL_DIR"
applied=0
skipped=0
shopt -s nullglob
mapfile -t migration_files < <(printf '%s\n' "$SQL_DIR"/*.sql | sort)
for file in "${migration_files[@]}"; do
  name="$(basename "$file")"
  checksum="$(file_checksum "$file")"
  stored="$(mysql_q "$MYSQL_DB" -N -e "SELECT COALESCE(checksum_sha256, '') FROM _sql_migration_log WHERE name='${name}';" | tail -1)"

  if [ -n "$stored" ]; then
    if [ "$stored" != "$checksum" ]; then
      echo "ERROR: applied migration changed: $name" >&2
      echo "  stored=$stored" >&2
      echo "  current=$checksum" >&2
      exit 1
    fi
    skipped=$((skipped + 1))
    continue
  fi

  logged="$(mysql_q "$MYSQL_DB" -N -e "SELECT COUNT(*) FROM _sql_migration_log WHERE name='${name}';" | tail -1)"
  if [ "$logged" = "1" ]; then
    echo "  ADOPT checksum for legacy log entry $name"
    mysql_q "$MYSQL_DB" -e "UPDATE _sql_migration_log SET checksum_sha256='${checksum}' WHERE name='${name}' AND checksum_sha256 IS NULL;"
    skipped=$((skipped + 1))
    continue
  fi

  if [ "$name" = "001_init_v1.sql" ]; then
    has_users="$(mysql_q "$MYSQL_DB" -N -e "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='${MYSQL_DB}' AND table_name='users';" | tail -1)"
    if [ "$has_users" = "1" ]; then
      echo "  ADOPT $name (schema already initialized)"
      mysql_q "$MYSQL_DB" -e "INSERT INTO _sql_migration_log(name, checksum_sha256, execution_ms) VALUES ('${name}', '${checksum}', 0);"
      skipped=$((skipped + 1))
      continue
    fi
  fi

  if [ "$VERIFY_ONLY" = "true" ]; then
    echo "  PENDING $name"
    continue
  fi

  echo "  RUN $name"
  started_ms="$(date +%s%3N)"
  docker exec -i -e MYSQL_PWD="$MYSQL_PASS" "$MYSQL_CONTAINER" \
    mysql --default-character-set=utf8mb4 -u"$MYSQL_USER" "$MYSQL_DB" < "$file"
  finished_ms="$(date +%s%3N)"
  execution_ms=$((finished_ms - started_ms))
  mysql_q "$MYSQL_DB" -e "INSERT INTO _sql_migration_log(name, checksum_sha256, execution_ms) VALUES ('${name}', '${checksum}', ${execution_ms});"
  applied=$((applied + 1))
done

echo "SQL migration summary: applied=$applied skipped=$skipped verify_only=$VERIFY_ONLY"
