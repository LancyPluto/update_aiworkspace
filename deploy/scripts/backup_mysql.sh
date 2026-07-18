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
OSS_ENDPOINT="${OSS_ENDPOINT:-}"
OSS_ACCESS_KEY_ID="${OSS_ACCESS_KEY_ID:-${ALIYUN_ACCESS_KEY_ID:-${ALIYUN_CAPTCHA_ACCESS_KEY_ID:-}}}"
OSS_ACCESS_KEY_SECRET="${OSS_ACCESS_KEY_SECRET:-${ALIYUN_ACCESS_KEY_SECRET:-${ALIYUN_CAPTCHA_ACCESS_KEY_SECRET:-}}}"
OSSUTIL_VERSION="${OSSUTIL_VERSION:-2.3.0}"
APP_PRODUCTION_MODE="${APP_PRODUCTION_MODE:-false}"
APP_ENV="${APP_ENV:-local}"
OSSUTIL=""
ossutil_archive=""
ossutil_extract=""
ossutil_install_dir=""

cleanup() {
  rm -f "${plain:-}" "${ossutil_archive:-}"
  [ -z "${ossutil_extract:-}" ] || rm -rf "$ossutil_extract"
  [ -z "${ossutil_install_dir:-}" ] || rm -rf "$ossutil_install_dir"
}
trap cleanup EXIT

ensure_ossutil() {
  if command -v ossutil >/dev/null 2>&1; then
    OSSUTIL="$(command -v ossutil)"
    return
  fi

  command -v curl >/dev/null 2>&1 || { echo "ERROR: curl is required to install ossutil" >&2; exit 1; }
  command -v python3 >/dev/null 2>&1 || { echo "ERROR: python3 is required to install ossutil" >&2; exit 1; }
  command -v sha256sum >/dev/null 2>&1 || { echo "ERROR: sha256sum is required to verify ossutil" >&2; exit 1; }

  local arch expected_ossutil_sha256 actual_ossutil_sha256 found
  arch="$(uname -m)"
  case "$arch" in
    x86_64|amd64)
      arch=amd64
      expected_ossutil_sha256=3ae4d9fc85a7a6e9f5654d1599766f1a3a42a3692870887b5ae9338d582ef65a
      ;;
    aarch64|arm64)
      arch=arm64
      expected_ossutil_sha256=f6c95ba0c2d2ef30290af686ce4d706c701f4734ce8090bee4288a77e3f1d764
      ;;
    *)
      echo "ERROR: unsupported ossutil architecture: $arch" >&2
      exit 1
      ;;
  esac

  ossutil_archive="$(mktemp "${TMPDIR:-/tmp}/ossutil-${OSSUTIL_VERSION}.XXXXXX.zip")"
  ossutil_extract="$(mktemp -d "${TMPDIR:-/tmp}/ossutil-${OSSUTIL_VERSION}.XXXXXX")"
  echo "==> Installing verified temporary ossutil $OSSUTIL_VERSION for linux-$arch"
  env -u OSS_ACCESS_KEY_ID -u OSS_ACCESS_KEY_SECRET curl -fsSL \
    "https://gosspublic.alicdn.com/ossutil/v2/${OSSUTIL_VERSION}/ossutil-${OSSUTIL_VERSION}-linux-${arch}.zip" \
    -o "$ossutil_archive"
  actual_ossutil_sha256="$(sha256sum "$ossutil_archive" | awk '{print $1}')"
  if [ "$actual_ossutil_sha256" != "$expected_ossutil_sha256" ]; then
    echo "ERROR: ossutil archive checksum mismatch for linux-$arch" >&2
    exit 1
  fi
  python3 - "$ossutil_archive" "$ossutil_extract" <<'PY'
import sys
import zipfile

with zipfile.ZipFile(sys.argv[1]) as archive:
    archive.extractall(sys.argv[2])
PY
  found="$(find "$ossutil_extract" -name ossutil -type f -print -quit)"
  if [ -z "$found" ]; then
    echo "ERROR: verified ossutil archive does not contain the executable" >&2
    exit 1
  fi
  ossutil_install_dir="$(mktemp -d "${TMPDIR:-/tmp}/ai-tool-market-ossutil.XXXXXX")"
  cp "$found" "$ossutil_install_dir/ossutil"
  chmod 700 "$ossutil_install_dir/ossutil"
  OSSUTIL="$ossutil_install_dir/ossutil"
}

ossutil() {
  "$OSSUTIL" "$@"
}

if [ -z "$BACKUP_ENCRYPTION_PASSWORD" ]; then
  echo "ERROR: BACKUP_ENCRYPTION_PASSWORD is required" >&2
  exit 1
fi
if { [ "$APP_PRODUCTION_MODE" = "true" ] || [ "$APP_ENV" = "production" ]; } && [ -z "$BACKUP_OSS_URI" ]; then
  echo "ERROR: BACKUP_OSS_URI is required in production" >&2
  exit 1
fi
if [ -n "$BACKUP_OSS_URI" ]; then
  [ -n "$OSS_ENDPOINT" ] || { echo "ERROR: OSS_ENDPOINT is required for BACKUP_OSS_URI" >&2; exit 1; }
  [ -n "$OSS_ACCESS_KEY_ID" ] || { echo "ERROR: OSS access key ID is required for BACKUP_OSS_URI" >&2; exit 1; }
  [ -n "$OSS_ACCESS_KEY_SECRET" ] || { echo "ERROR: OSS access key secret is required for BACKUP_OSS_URI" >&2; exit 1; }
  export OSS_ACCESS_KEY_ID OSS_ACCESS_KEY_SECRET
  ensure_ossutil
fi

mkdir -p "$BACKUP_DIR"
timestamp="$(date -u +%Y%m%dT%H%M%SZ)"
base="${MYSQL_DB}_${timestamp}"
plain="$BACKUP_DIR/${base}.sql.gz"
encrypted="${plain}.enc"
manifest="$BACKUP_DIR/${base}.manifest"

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

if [ -n "$BACKUP_OSS_URI" ]; then
  destination="${BACKUP_OSS_URI%/}/$timestamp/"
  remote_encrypted="$destination$(basename "$encrypted")"
  remote_manifest="$destination$(basename "$manifest")"
  ossutil cp -f --endpoint "$OSS_ENDPOINT" "$encrypted" "$remote_encrypted"
  ossutil cp -f --endpoint "$OSS_ENDPOINT" "$manifest" "$remote_manifest"
  ossutil stat --endpoint "$OSS_ENDPOINT" "$remote_encrypted" >/dev/null
  ossutil stat --endpoint "$OSS_ENDPOINT" "$remote_manifest" >/dev/null
fi

find "$BACKUP_DIR" -type f \( -name '*.sql.gz.enc' -o -name '*.manifest' \) \
  -mtime "+$BACKUP_RETENTION_DAYS" -delete
echo "Backup complete: $encrypted"
echo "Manifest: $manifest"
