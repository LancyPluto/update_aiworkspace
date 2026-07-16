#!/usr/bin/env bash
set -euo pipefail
umask 077

MYSQL_CONTAINER="${MYSQL_CONTAINER:-ai-supermarket-mysql}"
MYSQL_USER="${MYSQL_USER:-root}"
MYSQL_PASS="${MYSQL_PASS:-root123456}"
MYSQL_DB="${MYSQL_DB:-ai_supermarket_v1}"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
BACKUP_DIR="${BACKUP_DIR:-$SCRIPT_DIR/../backup/files}"
BACKUP_RETENTION_DAYS="${BACKUP_RETENTION_DAYS:-14}"
BACKUP_OSS_URI="${BACKUP_OSS_URI:-}"
BACKUP_ENCRYPTION_PASSWORD="${BACKUP_ENCRYPTION_PASSWORD:-}"
APP_PRODUCTION_MODE="${APP_PRODUCTION_MODE:-false}"
APP_ENV="${APP_ENV:-local}"

if [ -z "$BACKUP_ENCRYPTION_PASSWORD" ]; then
  echo "ERROR: BACKUP_ENCRYPTION_PASSWORD is required" >&2
  exit 1
fi
if { [ "$APP_PRODUCTION_MODE" = "true" ] || [ "$APP_ENV" = "production" ]; } && [ -z "$BACKUP_OSS_URI" ]; then
  echo "ERROR: BACKUP_OSS_URI is required in production" >&2
  exit 1
fi

mkdir -p "$BACKUP_DIR"
timestamp="$(date -u +%Y%m%dT%H%M%SZ)"
base="${MYSQL_DB}_${timestamp}"
plain="$BACKUP_DIR/${base}.sql.gz"
encrypted="${plain}.enc"
manifest="$BACKUP_DIR/${base}.manifest"
trap 'rm -f "$plain"' EXIT

echo "==> Dump $MYSQL_DB"
docker exec -e MYSQL_PWD="$MYSQL_PASS" "$MYSQL_CONTAINER" mysqldump \
  -u"$MYSQL_USER" --single-transaction --quick --routines --triggers --events \
  --set-gtid-purged=OFF --default-character-set=utf8mb4 "$MYSQL_DB" | gzip -9 > "$plain"
gzip -t "$plain"

schema_checkpoint="$(
  docker exec -e MYSQL_PWD="$MYSQL_PASS" "$MYSQL_CONTAINER" \
    mysql --default-character-set=utf8mb4 --batch --skip-column-names \
      -u"$MYSQL_USER" "$MYSQL_DB" \
      -e "SELECT COALESCE(MAX(name), 'untracked') FROM _sql_migration_log;" \
      2>/dev/null | tail -1 || true
)"
if [[ ! "$schema_checkpoint" =~ ^[0-9]{3}_[A-Za-z0-9_.-]+$ ]]; then
  schema_checkpoint="untracked"
fi

openssl enc -aes-256-cbc -salt -pbkdf2 -md sha256 -iter 200000 \
  -pass env:BACKUP_ENCRYPTION_PASSWORD -in "$plain" -out "$encrypted"
checksum="$(sha256sum "$encrypted" | awk '{print $1}')"
size="$(wc -c < "$encrypted" | tr -d ' ')"
printf 'manifest_version=1\nencryption=aes-256-cbc\nkdf=pbkdf2-sha256\nkdf_iterations=200000\ndatabase=%s\nschema_checkpoint=%s\ncreated_at=%s\nfile=%s\nbytes=%s\nsha256=%s\n' \
  "$MYSQL_DB" "$schema_checkpoint" "$timestamp" "$(basename "$encrypted")" "$size" "$checksum" > "$manifest"
rm -f "$plain"
trap - EXIT

if [ -n "$BACKUP_OSS_URI" ]; then
  command -v ossutil >/dev/null 2>&1 || { echo "ERROR: ossutil is required for BACKUP_OSS_URI" >&2; exit 1; }
  destination="${BACKUP_OSS_URI%/}/$timestamp/"
  remote_encrypted="$destination$(basename "$encrypted")"
  remote_manifest="$destination$(basename "$manifest")"
  ossutil cp "$encrypted" "$destination"
  ossutil cp "$manifest" "$destination"
  ossutil stat "$remote_encrypted" >/dev/null
  ossutil stat "$remote_manifest" >/dev/null
fi

find "$BACKUP_DIR" -type f \( -name '*.sql.gz.enc' -o -name '*.manifest' \) \
  -mtime "+$BACKUP_RETENTION_DAYS" -delete
echo "Backup complete: $encrypted"
echo "Manifest: $manifest"
