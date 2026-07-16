#!/usr/bin/env bash
set -euo pipefail
umask 077

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
MYSQL_CONTAINER="${MYSQL_CONTAINER:-ai-supermarket-mysql}"
MYSQL_USER="${MYSQL_USER:-root}"
MYSQL_PASS="${MYSQL_PASS:-root123456}"
TARGET_DB="${TARGET_DB:-}"
BACKUP_FILE="${BACKUP_FILE:-${1:-}}"
BACKUP_MANIFEST="${BACKUP_MANIFEST:-}"
BACKUP_ENCRYPTION_PASSWORD="${BACKUP_ENCRYPTION_PASSWORD:-}"
started_at="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
started_epoch="$(date +%s)"
RESTORE_DRILL_REPORT="${RESTORE_DRILL_REPORT:-$SCRIPT_DIR/../backup/restore-drills/restore_${TARGET_DB:-unset}_$(date -u +%Y%m%dT%H%M%SZ).report}"
drill_status="FAILED"
actual_checksum="not-verified"
schema_checkpoint="unverified"

write_drill_report() {
  local exit_status="$1"
  local finished_epoch elapsed finished_at
  finished_epoch="$(date +%s)"
  elapsed="$((finished_epoch - started_epoch))"
  finished_at="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  mkdir -p "$(dirname "$RESTORE_DRILL_REPORT")"
  {
    if [ "$drill_status" = "SUCCESS" ] && [ "$exit_status" -eq 0 ]; then
      printf 'status=SUCCESS\n'
    else
      printf 'status=FAILED\n'
    fi
    printf 'target_database=%s\n' "$TARGET_DB"
    printf 'backup_file=%s\n' "$(basename "${BACKUP_FILE:-unset}")"
    printf 'backup_sha256=%s\n' "$actual_checksum"
    printf 'schema_checkpoint=%s\n' "$schema_checkpoint"
    printf 'started_at=%s\n' "$started_at"
    printf 'finished_at=%s\n' "$finished_at"
    printf 'elapsed_seconds=%s\n' "$elapsed"
  } > "$RESTORE_DRILL_REPORT"
  chmod 600 "$RESTORE_DRILL_REPORT"
}

temporary=""
cleanup() {
  local exit_status="$?"
  [ -z "$temporary" ] || rm -f "$temporary"
  write_drill_report "$exit_status"
}
trap cleanup EXIT

if [ -z "$TARGET_DB" ] || [[ ! "$TARGET_DB" =~ ^[A-Za-z0-9_]+(_staging|_restore|_verify)$ ]]; then
  echo "ERROR: TARGET_DB must end with _staging, _restore, or _verify" >&2
  exit 1
fi
if [ -z "$BACKUP_FILE" ] || [ ! -f "$BACKUP_FILE" ]; then
  echo "ERROR: encrypted BACKUP_FILE does not exist" >&2
  exit 1
fi
if [ -z "$BACKUP_ENCRYPTION_PASSWORD" ]; then
  echo "ERROR: BACKUP_ENCRYPTION_PASSWORD is required" >&2
  exit 1
fi

if [ -z "$BACKUP_MANIFEST" ]; then
  BACKUP_MANIFEST="${BACKUP_FILE%.sql.gz.enc}.manifest"
fi
if [ ! -f "$BACKUP_MANIFEST" ]; then
  echo "ERROR: backup manifest does not exist: $BACKUP_MANIFEST" >&2
  exit 1
fi
expected_checksum="$(awk -F= '$1 == "sha256" { print $2 }' "$BACKUP_MANIFEST")"
expected_size="$(awk -F= '$1 == "bytes" { print $2 }' "$BACKUP_MANIFEST")"
manifest_version="$(awk -F= '$1 == "manifest_version" { print $2 }' "$BACKUP_MANIFEST")"
manifest_encryption="$(awk -F= '$1 == "encryption" { print $2 }' "$BACKUP_MANIFEST")"
manifest_kdf="$(awk -F= '$1 == "kdf" { print $2 }' "$BACKUP_MANIFEST")"
schema_checkpoint="$(awk -F= '$1 == "schema_checkpoint" { print $2 }' "$BACKUP_MANIFEST")"
actual_checksum="$(sha256sum "$BACKUP_FILE" | awk '{print $1}')"
if [ -z "$expected_checksum" ] || [ "$actual_checksum" != "$expected_checksum" ]; then
  echo "ERROR: backup checksum verification failed" >&2
  exit 1
fi
actual_size="$(wc -c < "$BACKUP_FILE" | tr -d ' ')"
if [ -z "$expected_size" ] || [ "$actual_size" != "$expected_size" ]; then
  echo "ERROR: backup size verification failed" >&2
  exit 1
fi
if [ "$manifest_version" != "1" ] || [ "$manifest_encryption" != "aes-256-cbc" ] || [ "$manifest_kdf" != "pbkdf2-sha256" ]; then
  echo "ERROR: unsupported backup manifest or encryption parameters" >&2
  exit 1
fi
if [[ ! "$schema_checkpoint" =~ ^([0-9]{3}_[A-Za-z0-9_.-]+|untracked)$ ]]; then
  echo "ERROR: backup schema checkpoint is invalid" >&2
  exit 1
fi

temporary="$(mktemp --suffix=.sql.gz)"
openssl enc -d -aes-256-cbc -pbkdf2 -md sha256 -iter 200000 \
  -pass env:BACKUP_ENCRYPTION_PASSWORD -in "$BACKUP_FILE" -out "$temporary"
gzip -t "$temporary"

mysql_exec() {
  docker exec -i -e MYSQL_PWD="$MYSQL_PASS" "$MYSQL_CONTAINER" \
    mysql --default-character-set=utf8mb4 -u"$MYSQL_USER" "$@"
}

echo "==> Recreate isolated database $TARGET_DB"
mysql_exec -e "DROP DATABASE IF EXISTS \`$TARGET_DB\`; CREATE DATABASE \`$TARGET_DB\` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"
gzip -dc "$temporary" | mysql_exec "$TARGET_DB"

workflow_tables=()
checkpoint_number="${schema_checkpoint%%_*}"
if [[ "$checkpoint_number" =~ ^[0-9]+$ ]] && ((10#$checkpoint_number >= 88)); then
  workflow_tables=(workflow_runs workflow_step_attempts workflow_step_charges)
fi

for table in users credit_accounts credit_recharge_orders ai_tasks credit_logs system_settings "${workflow_tables[@]}"; do
  exists="$(mysql_exec -N -e "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='${TARGET_DB}' AND table_name='${table}';" | tail -1)"
  if [ "$exists" != "1" ]; then
    echo "ERROR: restored database is missing table $table" >&2
    exit 1
  fi
  count="$(mysql_exec -N "$TARGET_DB" -e "SELECT COUNT(*) FROM \`$table\`;" | tail -1)"
  echo "  $table rows=$count"
done

drill_status="SUCCESS"
echo "Restore verification complete: $TARGET_DB"
echo "Drill report: $RESTORE_DRILL_REPORT"
