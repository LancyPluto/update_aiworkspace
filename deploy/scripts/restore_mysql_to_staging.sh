#!/usr/bin/env bash
set -euo pipefail

MYSQL_CONTAINER="${MYSQL_CONTAINER:-ai-supermarket-mysql}"
MYSQL_USER="${MYSQL_USER:-root}"
MYSQL_PASS="${MYSQL_PASS:-root123456}"
TARGET_DB="${TARGET_DB:-}"
BACKUP_FILE="${BACKUP_FILE:-${1:-}}"
BACKUP_MANIFEST="${BACKUP_MANIFEST:-}"
BACKUP_ENCRYPTION_PASSWORD="${BACKUP_ENCRYPTION_PASSWORD:-}"

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
actual_checksum="$(sha256sum "$BACKUP_FILE" | awk '{print $1}')"
if [ -z "$expected_checksum" ] || [ "$actual_checksum" != "$expected_checksum" ]; then
  echo "ERROR: backup checksum verification failed" >&2
  exit 1
fi

temporary="$(mktemp --suffix=.sql.gz)"
trap 'rm -f "$temporary"' EXIT
openssl enc -d -aes-256-cbc -pbkdf2 -iter 200000 \
  -pass env:BACKUP_ENCRYPTION_PASSWORD -in "$BACKUP_FILE" -out "$temporary"
gzip -t "$temporary"

mysql_exec() {
  docker exec -i -e MYSQL_PWD="$MYSQL_PASS" "$MYSQL_CONTAINER" \
    mysql --default-character-set=utf8mb4 -u"$MYSQL_USER" "$@"
}

echo "==> Recreate isolated database $TARGET_DB"
mysql_exec -e "DROP DATABASE IF EXISTS \`$TARGET_DB\`; CREATE DATABASE \`$TARGET_DB\` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"
gzip -dc "$temporary" | mysql_exec "$TARGET_DB"

for table in users credit_accounts credit_recharge_orders ai_tasks credit_logs system_settings; do
  exists="$(mysql_exec -N -e "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='${TARGET_DB}' AND table_name='${table}';" | tail -1)"
  if [ "$exists" != "1" ]; then
    echo "ERROR: restored database is missing table $table" >&2
    exit 1
  fi
  count="$(mysql_exec -N "$TARGET_DB" -e "SELECT COUNT(*) FROM \`$table\`;" | tail -1)"
  echo "  $table rows=$count"
done

echo "Restore verification complete: $TARGET_DB"
