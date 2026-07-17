#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"
ROOT_ENV_FILE="${ROOT_ENV_FILE:-$ROOT_DIR/.env}"
DEPLOY_ENV_FILE="${DEPLOY_ENV_FILE:-$ROOT_DIR/deploy/.env}"
BACKUP_OSS_URI_DEFAULT="${BACKUP_OSS_URI_DEFAULT:-oss://wlcloudai-db-backup-prod/mysql/full}"

python3 - "$ROOT_ENV_FILE" "$DEPLOY_ENV_FILE" "$BACKUP_OSS_URI_DEFAULT" <<'PY'
import re
import secrets
import subprocess
import sys
from pathlib import Path

root_env = Path(sys.argv[1])
deploy_env = Path(sys.argv[2])
backup_uri_default = sys.argv[3]


def read_env(path: Path) -> dict[str, str]:
    values: dict[str, str] = {}
    if not path.exists():
        return values
    for line in path.read_text(encoding="utf-8", errors="replace").splitlines():
        if "=" not in line or line.lstrip().startswith("#"):
            continue
        key, value = line.split("=", 1)
        value = value.strip()
        if len(value) >= 2 and value[0] == value[-1] and value[0] in ("'", '"'):
            value = value[1:-1]
        values[key.strip()] = value
    return values


def container_env(name: str) -> dict[str, str]:
    result = subprocess.run(
        ["docker", "inspect", "--format", "{{range .Config.Env}}{{println .}}{{end}}", name],
        text=True,
        capture_output=True,
        check=False,
    )
    values: dict[str, str] = {}
    if result.returncode != 0:
        return values
    for line in result.stdout.splitlines():
        if "=" in line:
            key, value = line.split("=", 1)
            values[key] = value
    return values


def write_env(path: Path, patch: dict[str, str], remove: set[str]) -> None:
    lines = path.read_text(encoding="utf-8", errors="replace").splitlines() if path.exists() else []
    managed = set(patch) | remove
    output = [line for line in lines if line.split("=", 1)[0].strip() not in managed]
    output.extend(f"{key}={value}" for key, value in patch.items())
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(path.suffix + ".tmp")
    temporary.write_text("\n".join(output) + "\n", encoding="utf-8")
    temporary.chmod(0o600)
    temporary.replace(path)
    path.chmod(0o600)


root = read_env(root_env)
deploy = read_env(deploy_env)
container = container_env("ai-supermarket-rabbitmq")

rabbit_candidates = (
    (root.get("RABBITMQ_USERNAME", ""), root.get("RABBITMQ_PASSWORD", ""), "root env"),
    (deploy.get("RABBITMQ_USERNAME", ""), deploy.get("RABBITMQ_PASSWORD", ""), "deploy env"),
    (container.get("RABBITMQ_DEFAULT_USER", ""), container.get("RABBITMQ_DEFAULT_PASS", ""), "container"),
)
rabbit_user = ""
rabbit_password = ""
rabbit_source = "generated"
for username, password, source in rabbit_candidates:
    if (re.fullmatch(r"[A-Za-z0-9_.-]{3,64}", username or "") \
            and username.lower() != "guest" and password and password.lower() != "guest":
        rabbit_user, rabbit_password, rabbit_source = username, password, source
        break
if not rabbit_user:
    rabbit_user = "aitoolmarket_prod"
    rabbit_password = secrets.token_hex(24)

backup_candidates = (
    deploy.get("BACKUP_ENCRYPTION_PASSWORD", ""),
    root.get("BACKUP_ENCRYPTION_PASSWORD", ""),
)
backup_password = next((value for value in backup_candidates if len(value) >= 32), "")
backup_source = "existing" if backup_password else "generated"
if not backup_password:
    backup_password = secrets.token_urlsafe(36)

host_only = {
    "BACKUP_ENCRYPTION_PASSWORD",
    "BACKUP_OSS_URI",
    "PRODUCTION_PREFLIGHT_MYSQL_USER",
    "PRODUCTION_PREFLIGHT_MYSQL_PASSWORD",
}
obsolete = {
    "WORKFLOW_RUNTIME_MAX_PROVIDER_DAILY_COST_CNY",
    "WORKFLOW_RUNTIME_COST_ALERT_WEBHOOK_URL",
}
write_env(
    root_env,
    {
        "RABBITMQ_USERNAME": rabbit_user,
        "RABBITMQ_PASSWORD": rabbit_password,
        "WORKFLOW_RUNTIME_ENABLED": "true",
        "WORKFLOW_RUNTIME_EXECUTION_ENABLED": "true",
        "WORKFLOW_RUNTIME_REAL_BILLING_ENABLED": "true",
        "WORKFLOW_RUNTIME_SHADOW_BILLING_ENABLED": "false",
    },
    host_only | obsolete,
)
write_env(
    deploy_env,
    {
        "RABBITMQ_USERNAME": rabbit_user,
        "RABBITMQ_PASSWORD": rabbit_password,
        "BACKUP_ENCRYPTION_PASSWORD": backup_password,
        "BACKUP_OSS_URI": backup_uri_default,
    },
    {
        "PRODUCTION_PREFLIGHT_MYSQL_USER",
        "PRODUCTION_PREFLIGHT_MYSQL_PASSWORD",
    } | obsolete,
)
print(f"RabbitMQ credentials prepared from {rabbit_source}; values were not logged")
print(f"Backup encryption password {backup_source}; value was not logged")
PY

read_env_value() {
  local name="$1"
  local file line
  for file in "$ROOT_ENV_FILE" "$DEPLOY_ENV_FILE"; do
    [ -f "$file" ] || continue
    line="$(awk -v key="$name" 'index($0, key "=") == 1 { value=substr($0, length(key) + 2) } END { print value }' "$file")"
    if [ -n "$line" ]; then
      printf '%s' "${line%$'\r'}"
      return
    fi
  done
}

cd "$ROOT_DIR/deploy"
docker compose --env-file "$ROOT_ENV_FILE" -f docker-compose.yml up -d mysql rabbitmq

for _ in $(seq 1 60); do
  if docker exec ai-supermarket-rabbitmq rabbitmq-diagnostics -q ping >/dev/null 2>&1; then
    break
  fi
  sleep 2
done
docker exec ai-supermarket-rabbitmq rabbitmq-diagnostics -q ping >/dev/null

rabbit_user="$(read_env_value RABBITMQ_USERNAME)"
rabbit_password="$(read_env_value RABBITMQ_PASSWORD)"
if docker exec ai-supermarket-rabbitmq rabbitmqctl -q list_users \
    | awk '{print $1}' | grep -Fxq "$rabbit_user"; then
  docker exec ai-supermarket-rabbitmq rabbitmqctl change_password "$rabbit_user" "$rabbit_password" >/dev/null
else
  docker exec ai-supermarket-rabbitmq rabbitmqctl add_user "$rabbit_user" "$rabbit_password" >/dev/null
fi
docker exec ai-supermarket-rabbitmq rabbitmqctl set_permissions -p / "$rabbit_user" '.*' '.*' '.*' >/dev/null
echo "RabbitMQ production account reconciled; credentials were not logged"
