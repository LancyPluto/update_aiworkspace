#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"
ROOT_ENV_FILE="${ROOT_ENV_FILE:-$ROOT_DIR/.env}"
DEPLOY_ENV_FILE="${DEPLOY_ENV_FILE:-$ROOT_DIR/deploy/.env}"

read_env_value() {
  local name="$1"
  local file line value
  if [ "${!name+x}" = x ]; then
    printf '%s' "${!name}"
    return
  fi
  for file in "$ROOT_ENV_FILE" "$DEPLOY_ENV_FILE"; do
    [ -f "$file" ] || continue
    line="$(awk -v key="$name" 'index($0, key "=") == 1 { value=substr($0, length(key) + 2) } END { print value }' "$file")"
    if [ -n "$line" ]; then
      value="${line%$'\r'}"
      if [[ "$value" == \"*\" ]] || [[ "$value" == \'*\' ]]; then
        value="${value:1:${#value}-2}"
      fi
      printf '%s' "$value"
      return
    fi
  done
  printf ''
}

errors=0
fail() {
  echo "::error::production environment preflight failed: $1" >&2
  errors=$((errors + 1))
}

require_present() {
  local name="$1"
  if [ -z "$(read_env_value "$name")" ]; then
    fail "$name is required"
  fi
}

require_boolean() {
  local name="$1"
  local value
  value="$(read_env_value "$name")"
  if [ "$value" != "true" ] && [ "$value" != "false" ]; then
    fail "$name must be explicitly set to true or false"
  fi
}

production_mode="$(read_env_value APP_PRODUCTION_MODE)"
app_env="$(read_env_value APP_ENV)"
if [ "$production_mode" != "true" ] && [ "$app_env" != "production" ]; then
  echo "Production environment preflight skipped: application is not in production mode."
  exit 0
fi

rabbitmq_user="$(read_env_value RABBITMQ_USERNAME)"
rabbitmq_password="$(read_env_value RABBITMQ_PASSWORD)"
if [ -z "$rabbitmq_user" ] || [ -z "$rabbitmq_password" ]; then
  fail "RabbitMQ username and password are required"
elif [ "$rabbitmq_user" = "guest" ] || [ "$rabbitmq_password" = "guest" ]; then
  fail "guest RabbitMQ credentials are forbidden"
fi

backup_password="$(read_env_value BACKUP_ENCRYPTION_PASSWORD)"
if [ ${#backup_password} -lt 32 ]; then
  fail "BACKUP_ENCRYPTION_PASSWORD must contain at least 32 characters"
fi

backup_uri="$(read_env_value BACKUP_OSS_URI)"
if [[ "$backup_uri" != oss://* ]]; then
  fail "BACKUP_OSS_URI must use oss:// and point to an off-host backup bucket"
else
  backup_bucket="${backup_uri#oss://}"
  backup_bucket="${backup_bucket%%/*}"
  for asset_setting in OSS_BUCKET OSS_PUBLIC_BUCKET OSS_PRIVATE_BUCKET OSS_LEGACY_BUCKET; do
    asset_bucket="$(read_env_value "$asset_setting")"
    if [ -n "$asset_bucket" ] && [ "$backup_bucket" = "$asset_bucket" ]; then
      fail "backup bucket must be separate from application asset buckets"
    fi
  done
fi

require_present PRODUCTION_PREFLIGHT_MYSQL_USER
require_present PRODUCTION_PREFLIGHT_MYSQL_PASSWORD
preflight_user="$(read_env_value PRODUCTION_PREFLIGHT_MYSQL_USER)"
if [ "$preflight_user" = "root" ]; then
  fail "read-only preflight account cannot be root"
fi

require_boolean WORKFLOW_RUNTIME_ENABLED
require_boolean WORKFLOW_RUNTIME_EXECUTION_ENABLED
require_boolean PPT_WORKBENCH_ENABLED

banana_image="$(read_env_value BANANA_SLIDES_IMAGE)"
approved_banana_repository="crpi-e8y8tegbhx1vbpxm.cn-guangzhou.personal.cr.aliyuncs.com/aitools_wl/banana"
if [[ ! "$banana_image" =~ ^${approved_banana_repository}@sha256:[0-9a-f]{64}$ ]]; then
  fail "BANANA_SLIDES_IMAGE must use the approved ACR repository and an immutable sha256 digest"
fi

if ! python3 - "$ROOT_DIR/engines/versions.lock.json" "$banana_image" <<'PY'
import json
import pathlib
import sys

path = pathlib.Path(sys.argv[1])
deployed = sys.argv[2]
try:
    engine = json.loads(path.read_text(encoding="utf-8"))["engines"]["banana-slides"]
    assert engine["productForkRepository"] == "https://github.com/AI-miniLab/banana-slides"
    assert engine["productForkCommit"]
    assert engine["image"]["reference"] == deployed
    assert engine["contractVersion"] == "banana-project-api-v3"
    assert engine["releaseStatus"] == "READY"
except (AssertionError, KeyError, TypeError, json.JSONDecodeError, OSError):
    raise SystemExit(1)
PY
then
  fail "engines/versions.lock.json is not a READY release matching BANANA_SLIDES_IMAGE"
fi

if [ "$(read_env_value WORKFLOW_RUNTIME_EXECUTION_ENABLED)" = "true" ]; then
  if [ "$(read_env_value WORKFLOW_RUNTIME_ENABLED)" != "true" ]; then
    fail "WORKFLOW_RUNTIME_ENABLED must be true before workflow execution is enabled"
  fi
  if [ "$(read_env_value WORKFLOW_RUNTIME_REAL_BILLING_ENABLED)" != "true" ]; then
    fail "WORKFLOW_RUNTIME_REAL_BILLING_ENABLED must be true for paid production runs"
  fi
fi

if [ "$errors" -ne 0 ]; then
  echo "Production environment preflight found $errors blocking issue(s)." >&2
  exit 1
fi

echo "Production environment preflight passed. Secrets were not printed."
