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

epoch_millis() {
  # GNU date supports %N, but the BSD date bundled with macOS does not.
  # Second-level timing is sufficient for migration audit records.
  printf '%s000\n' "$(date +%s)"
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

workflow_p0_logged="$(mysql_q "$MYSQL_DB" -N -e "SELECT COUNT(*) FROM _sql_migration_log WHERE name='088_workflow_tools_p0.sql';" | tail -1)"
has_ai_tasks="$(mysql_q "$MYSQL_DB" -N -e "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='${MYSQL_DB}' AND table_name='ai_tasks';" | tail -1)"
has_workflow_runs="$(mysql_q "$MYSQL_DB" -N -e "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='${MYSQL_DB}' AND table_name='workflow_runs';" | tail -1)"

if [ "$workflow_p0_logged" = "0" ] && [ "$has_ai_tasks" = "1" ] && [ "$has_workflow_runs" = "1" ]; then
  duplicate_task_keys="$(mysql_q "$MYSQL_DB" -N -e "
    SELECT COUNT(*) FROM (
      SELECT 1
      FROM ai_tasks
      WHERE idempotency_key IS NOT NULL
      GROUP BY user_id, idempotency_key
      HAVING COUNT(*) > 1
      LIMIT 1
    ) duplicate_keys;
  " | tail -1)"
  duplicate_root_tasks="$(mysql_q "$MYSQL_DB" -N -e "
    SELECT COUNT(*) FROM (
      SELECT 1
      FROM workflow_runs
      WHERE root_task_id IS NOT NULL
      GROUP BY root_task_id
      HAVING COUNT(*) > 1
      LIMIT 1
    ) duplicate_roots;
  " | tail -1)"
  partial_workflow_p0="$(mysql_q "$MYSQL_DB" -N -e "
    SELECT
      (SELECT COUNT(*) FROM information_schema.statistics
       WHERE table_schema='${MYSQL_DB}' AND table_name='ai_tasks'
         AND index_name='uk_ai_tasks_user_idempotency')
      +
      (SELECT COUNT(*) FROM information_schema.columns
       WHERE table_schema='${MYSQL_DB}' AND table_name='workflow_runs'
         AND column_name='workflow_version_id')
      +
      (SELECT COUNT(*) FROM information_schema.tables
       WHERE table_schema='${MYSQL_DB}' AND table_name='workflow_step_attempts');
  " | tail -1)"

  if [ "$duplicate_task_keys" != "0" ] || [ "$duplicate_root_tasks" != "0" ]; then
    echo "ERROR: workflow P0 migration preflight found historical idempotency duplicates" >&2
    echo "  duplicate ai_tasks(user_id,idempotency_key)=$duplicate_task_keys" >&2
    echo "  duplicate workflow_runs(root_task_id)=$duplicate_root_tasks" >&2
    echo "Resolve duplicates and rerun the zero-duplicate preflight before deployment." >&2
    exit 1
  fi
  if [ "$partial_workflow_p0" != "0" ]; then
    echo "ERROR: workflow P0 migration appears partially applied but is not recorded" >&2
    echo "Repair or roll forward the schema explicitly before rerunning migrations." >&2
    exit 1
  fi
fi

echo "==> Verify and apply SQL files from $SQL_DIR"
applied=0
skipped=0
shopt -s nullglob
# macOS ships Bash 3.2, which does not provide `mapfile`. Build the same
# sorted file list with a portable read loop so this deployment utility works
# both on developer machines and Linux servers.
migration_files=()
while IFS= read -r file; do
  migration_files+=("$file")
done < <(printf '%s\n' "$SQL_DIR"/*.sql | sort)
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

  if [ "$name" = "074_vendor_account_console_cookie.sql" ]; then
    compatible_cookie_columns="$(mysql_q "$MYSQL_DB" -N -e "
      SELECT COUNT(*)
      FROM information_schema.columns
      WHERE table_schema='${MYSQL_DB}'
        AND table_name='model_vendor_accounts'
        AND (
          (column_name='console_cookie'
            AND data_type='text'
            AND is_nullable='YES')
          OR
          (column_name='console_cookie_status'
            AND data_type='varchar'
            AND character_maximum_length=20
            AND is_nullable='YES'
            AND column_default='UNKNOWN')
        );
    " | tail -1)"
    if [ "$compatible_cookie_columns" = "2" ]; then
      echo "  ADOPT $name (compatible columns already supplied by historical 044)"
      mysql_q "$MYSQL_DB" -e "INSERT INTO _sql_migration_log(name, checksum_sha256, execution_ms) VALUES ('${name}', '${checksum}', 0);"
      skipped=$((skipped + 1))
      continue
    fi
  fi

  if [ "$name" = "084_configurable_image_token_estimates.sql" ]; then
    compatible_image_estimate_columns="$(mysql_q "$MYSQL_DB" -N -e "
      SELECT COUNT(*)
      FROM information_schema.columns
      WHERE table_schema='${MYSQL_DB}'
        AND table_name='pricing_margins'
        AND column_name IN ('image_estimate_input_tokens', 'image_estimate_output_tokens')
        AND data_type='int'
        AND is_nullable='YES'
        AND column_default IS NULL;
    " | tail -1)"
    if [ "$compatible_image_estimate_columns" = "2" ]; then
      echo "  ADOPT $name (compatible columns already supplied by historical 061)"
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
  started_ms="$(epoch_millis)"
  docker exec -i -e MYSQL_PWD="$MYSQL_PASS" "$MYSQL_CONTAINER" \
    mysql --default-character-set=utf8mb4 -u"$MYSQL_USER" "$MYSQL_DB" < "$file"
  finished_ms="$(epoch_millis)"
  execution_ms=$((finished_ms - started_ms))
  mysql_q "$MYSQL_DB" -e "INSERT INTO _sql_migration_log(name, checksum_sha256, execution_ms) VALUES ('${name}', '${checksum}', ${execution_ms});"
  applied=$((applied + 1))
done

echo "SQL migration summary: applied=$applied skipped=$skipped verify_only=$VERIFY_ONLY"
