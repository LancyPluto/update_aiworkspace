#!/usr/bin/env bash
# Lightweight production deploy using an authoritative Git release baseline.
# Password from env only — never commit credentials.
set -euo pipefail

: "${DEPLOY_HOST:?DEPLOY_HOST is required}"
: "${DEPLOY_USER:?DEPLOY_USER is required}"
: "${DEPLOY_PASSWORD:?DEPLOY_PASSWORD is required}"
: "${PRODUCTION_PREFLIGHT_MYSQL_USER:?Runner-generated preflight MySQL user is required}"
: "${PRODUCTION_PREFLIGHT_MYSQL_PASSWORD:?Runner-generated preflight MySQL password is required}"
: "${PPT_SMOKE_AUTH_TOKEN:?PPT_SMOKE_AUTH_TOKEN is required}"

DEPLOY_SYNC_MODE="${DEPLOY_SYNC_MODE:-git}"
if [ "$DEPLOY_SYNC_MODE" != "git" ]; then
  echo "Unsupported DEPLOY_SYNC_MODE=$DEPLOY_SYNC_MODE; production releases require git" >&2
  exit 1
fi
REMOTE_DIR="/root/ai_tool_market"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
DEPLOY_KNOWN_HOSTS_FILE="${DEPLOY_KNOWN_HOSTS_FILE:-$HOME/.ssh/known_hosts}"
if [ ! -f "$DEPLOY_KNOWN_HOSTS_FILE" ] || [ -L "$DEPLOY_KNOWN_HOSTS_FILE" ] || [ ! -r "$DEPLOY_KNOWN_HOSTS_FILE" ]; then
  echo "Pinned SSH host keys are required: $DEPLOY_KNOWN_HOSTS_FILE" >&2
  exit 1
fi
known_hosts_mode="$(stat -c '%a' "$DEPLOY_KNOWN_HOSTS_FILE")"
if [ "$(stat -c '%u' "$DEPLOY_KNOWN_HOSTS_FILE")" != "$(id -u)" ] || (( (8#$known_hosts_mode & 022) != 0 )); then
  echo "Pinned SSH host keys must be owned by the deploy user and not group/other writable" >&2
  exit 1
fi
SSH_OPTS=(-o StrictHostKeyChecking=yes -o UserKnownHostsFile="$DEPLOY_KNOWN_HOSTS_FILE" -o ServerAliveInterval=30 -o ServerAliveCountMax=120 -o TCPKeepAlive=yes)
GIT_REPO="${DEPLOY_GIT_REPO:-https://github.com/AI-miniLab/ai-tool-market.git}"
GIT_BRANCH="${DEPLOY_GIT_BRANCH:-dev}"
DEPLOY_GIT_REF="${DEPLOY_GIT_REF:-${GITHUB_SHA:-dev}}"
DEPLOY_EVENT="${DEPLOY_EVENT:-${GITHUB_EVENT_NAME:-push}}"
DEPLOY_PR_NUMBER="${DEPLOY_PR_NUMBER:-}"
PPT_SMOKE_AUTH_TOKEN_B64="$(printf '%s' "$PPT_SMOKE_AUTH_TOKEN" | base64 -w0)"

ssh_cmd() {
  SSHPASS="$DEPLOY_PASSWORD" sshpass -e ssh "${SSH_OPTS[@]}" "${DEPLOY_USER}@${DEPLOY_HOST}" "$@"
}

scp_cmd() {
  SSHPASS="$DEPLOY_PASSWORD" sshpass -e scp "${SSH_OPTS[@]}" "$@"
}

run_remote_script() {
  # Materialize the complete payload before executing it. Commands such as
  # `docker exec -i` must never be able to consume the remaining shell source.
  ssh_cmd 'set -eu; umask 077; remote_script="$(mktemp /tmp/ai-tool-market-deploy.XXXXXX)"; trap "rm -f -- \"$remote_script\"" EXIT HUP INT TERM; cat > "$remote_script"; chmod 700 "$remote_script"; bash "$remote_script"'
}

if [ -z "${DEPLOY_SERVICES:-}" ]; then
  CHANGED_FILES="$(bash "$SCRIPT_DIR/detect_deploy_changes.sh" || true)"
  if [ -n "$CHANGED_FILES" ]; then
    mapfile -t _changed_arr <<< "$CHANGED_FILES"
    DEPLOY_SERVICES="$(bash "$SCRIPT_DIR/detect_deploy_services.sh" "${_changed_arr[@]}")"
  else
    DEPLOY_SERVICES="$(bash "$SCRIPT_DIR/detect_deploy_services.sh")"
  fi
fi
echo "Deploy mode=$DEPLOY_SYNC_MODE services=$DEPLOY_SERVICES ref=$DEPLOY_GIT_REF event=$DEPLOY_EVENT"
echo "$DEPLOY_SERVICES" > /tmp/ai_tool_market_deploy_services.txt

scp_cmd "$SCRIPT_DIR/remote_production_git_sync.sh" "${DEPLOY_USER}@${DEPLOY_HOST}:/tmp/production_git_sync.sh"
ssh_cmd "chmod +x /tmp/production_git_sync.sh"
printf '%s\n' "${GITHUB_TOKEN:-}" | ssh_cmd env \
  REMOTE_DIR="$REMOTE_DIR" \
  GIT_REPO_URL="$GIT_REPO" \
  GITHUB_TOKEN_STDIN=1 \
  DEPLOY_GIT_REF="$DEPLOY_GIT_REF" \
  DEPLOY_EVENT="$DEPLOY_EVENT" \
  DEPLOY_GIT_BRANCH="$GIT_BRANCH" \
  DEPLOY_PR_NUMBER="$DEPLOY_PR_NUMBER" \
  GITHUB_SHA="${GITHUB_SHA:-}" \
  GITHUB_RUN_ID="${GITHUB_RUN_ID:-}" \
  GITHUB_ACTOR="${GITHUB_ACTOR:-}" \
  /tmp/production_git_sync.sh

full_rollback_services="$(DEPLOY_SERVICES= bash "$SCRIPT_DIR/detect_deploy_services.sh" "")"
FULL_ROLLBACK_SERVICES="$(bash "$SCRIPT_DIR/merge_deploy_services.sh" "$DEPLOY_SERVICES" "$full_rollback_services")"
rollback_after_public_gate_failure() {
  status="${1:-$?}"
  trap - ERR HUP INT TERM
  echo "::error::Release interrupted; checking for a pending production rollback." >&2
  ssh_cmd "if [ -s '$REMOTE_DIR/.deploy_revision.pending' ]; then services=\$(cat '$REMOTE_DIR/deploy/logs/last-deploy.services.txt' 2>/dev/null || true); if [ -z \"\$services\" ]; then services='$FULL_ROLLBACK_SERVICES'; fi; REMOTE_DIR='$REMOTE_DIR' DEPLOY_SERVICES=\"\$services\" bash '$REMOTE_DIR/deploy/scripts/rollback_release.sh'; else echo 'No pending production release to roll back'; fi" || \
    echo "::error::Rollback check after release interruption failed." >&2
  exit "$status"
}
trap 'rollback_after_public_gate_failure $?' ERR
trap 'rollback_after_public_gate_failure 129' HUP
trap 'rollback_after_public_gate_failure 130' INT TERM

# Remote: patch env, rebuild only changed services, health check
run_remote_script <<REMOTE
set -euo pipefail
REMOTE_DIR="$REMOTE_DIR"
DEPLOY_SERVICES="$DEPLOY_SERVICES"
DEPLOY_SYNC_MODE="$DEPLOY_SYNC_MODE"
DEPLOY_EVENT="$DEPLOY_EVENT"
DEPLOY_GIT_BRANCH="$GIT_BRANCH"
DEPLOY_PR_NUMBER="$DEPLOY_PR_NUMBER"
GITHUB_SHA="${GITHUB_SHA:-unknown}"
PRODUCTION_PREFLIGHT_MYSQL_USER="$PRODUCTION_PREFLIGHT_MYSQL_USER"
PRODUCTION_PREFLIGHT_MYSQL_PASSWORD="$PRODUCTION_PREFLIGHT_MYSQL_PASSWORD"
PPT_SMOKE_AUTH_TOKEN_B64="$PPT_SMOKE_AUTH_TOKEN_B64"

if [ "\$DEPLOY_SYNC_MODE" = "git" ]; then
  diff_status_file="\$REMOTE_DIR/deploy/logs/deploy-diff-base.status"
  diff_status="full"
  if [ -s "\$diff_status_file" ]; then
    diff_status="\$(tr -d '\r\n' < "\$diff_status_file")"
  fi
  if [ "\$diff_status" != "valid" ]; then
    full_services="\$(DEPLOY_SERVICES= bash "\$REMOTE_DIR/deploy/scripts/detect_deploy_services.sh" "")"
    DEPLOY_SERVICES="\$(bash "\$REMOTE_DIR/deploy/scripts/merge_deploy_services.sh" "\$DEPLOY_SERVICES" "\$full_services")"
    echo "No trusted production diff base; forcing services: \$DEPLOY_SERVICES"
  elif [ -s "\$REMOTE_DIR/deploy/logs/last-deploy.files.txt" ]; then
    mapfile -t actual_changed_files < "\$REMOTE_DIR/deploy/logs/last-deploy.files.txt"
    DEPLOY_SERVICES="\$(
      bash "\$REMOTE_DIR/deploy/scripts/resolve_deploy_services.sh" \
        "\$DEPLOY_SERVICES" "\${actual_changed_files[@]}"
    )"
    echo "Deploy services after production diff reconciliation: \$DEPLOY_SERVICES"
  fi
fi
RELEASE_SHA="\$(git -C "\$REMOTE_DIR" rev-parse HEAD)"
if [ "\$GITHUB_SHA" != "unknown" ] && [ "\$RELEASE_SHA" != "\$GITHUB_SHA" ]; then
  echo "::error::Checked-out release SHA mismatch: expected \$GITHUB_SHA, got \$RELEASE_SHA" >&2
  exit 1
fi
echo "Capturing current application images for rollback ..."
REMOTE_DIR="\$REMOTE_DIR" \
  DEPLOY_SERVICES="backend worker agent-service admin-frontend user-web banana-slides" \
  bash "\$REMOTE_DIR/deploy/scripts/capture_rollback_images.sh"

read_env_value() {
  python3 - "\$1" <<'PY'
import sys
from pathlib import Path

key = sys.argv[1]
value = ""
for relative in (".env", "deploy/.env"):
    path = Path("/root/ai_tool_market") / relative
    if not path.exists():
        continue
    for line in path.read_text(encoding="utf-8", errors="replace").splitlines():
        if "=" not in line or line.lstrip().startswith("#"):
            continue
        current_key, current_value = line.split("=", 1)
        if current_key.strip() == key:
            value = current_value.strip().strip('"').strip("'")
print(value)
PY
}

read_secret_snapshot() {
  python3 - <<'PY'
import hashlib
from pathlib import Path

SECRET_KEYS = (
    "JWT_SECRET", "INTERNAL_API_TOKEN", "MIHOMO_CONTROLLER_SECRET",
    "RABBITMQ_USERNAME", "RABBITMQ_PASSWORD",
)
root = Path("/root/ai_tool_market")
merged: dict[str, str] = {}
for rel in (".env", "deploy/.env"):
    path = root / rel
    if not path.exists():
        continue
    for line in path.read_text(encoding="utf-8", errors="replace").splitlines():
        if "=" not in line or line.strip().startswith("#"):
            continue
        key, value = line.split("=", 1)
        key, value = key.strip(), value.strip()
        if key in SECRET_KEYS and value:
            merged[key] = value
print("|".join(
    f"{key}={hashlib.sha256(merged[key].encode('utf-8')).hexdigest()}"
    for key in SECRET_KEYS if key in merged
))
PY
}

SECRET_SNAPSHOT_BEFORE="\$(read_secret_snapshot)"

python3 - <<'PY'
import json
from pathlib import Path

patch_lines = """
APP_PRODUCTION_MODE=true
APP_ENV=production
TASK_QUEUE_BACKEND=rabbitmq
VITE_API_BASE_URL=
VITE_DEV_PROXY_TARGET=http://backend:8080
ADMIN_NEXT_PUBLIC_API_BASE_URL=
ADMIN_NEXT_PUBLIC_API_PROXY_TARGET=http://backend:8080
CORS_ALLOWED_ORIGINS=http://wlcloudai.com,http://www.wlcloudai.com,http://8.134.93.203,https://wlcloudai.com,https://www.wlcloudai.com,https://8.134.93.203
ASSET_STORAGE_PUBLIC_BASE_URL=https://cdn.wlcloudai.com
ASSET_STORAGE_PRIVATE_BASE_URL=/api/v1/assets/private
ASSET_STORAGE_IMAGE_TRANSFORM_OPTIONS=image/format,webp/quality,Q_85
ASSET_PUBLIC_CACHE_CONTROL=public,max-age=31536000,immutable
ASSET_PRIVATE_CACHE_CONTROL=private,max-age=3600
ASSET_LEGACY_CACHE_CONTROL=public,max-age=300,must-revalidate
MEDIA_VIDEO_PREVIEW_ENABLED=true
VITE_MEDIA_DELIVERY_OPTIMIZATION=true
PPT_WORKBENCH_ENABLED=true
PPT_MODEL_GATEWAY_BASE_URL=http://backend:8080
PPT_EXECUTION_TOKEN_TTL_SECONDS=3600
MIHOMO_ENABLED=true
NO_PROXY=localhost,127.0.0.1,mysql,redis,rabbitmq,backend,agent-service,admin-frontend,user-web,nginx,host.docker.internal,wlcloudai.com,8.134.93.203,.aliyuncs.com,.aliyun.com,.cn,.klingai.com,api.deepseek.com,.deepseek.com,ark.cn-beijing.volces.com,.volces.com,api.minimaxi.com,.minimaxi.com,api.minimax.chat,.minimax.chat
CONTAINER_NO_PROXY=localhost,127.0.0.1,mysql,redis,rabbitmq,backend,agent-service,admin-frontend,user-web,nginx,host.docker.internal,wlcloudai.com,8.134.93.203,.aliyuncs.com,.aliyun.com,.cn,.klingai.com,api.deepseek.com,.deepseek.com,ark.cn-beijing.volces.com,.volces.com,api.minimaxi.com,.minimaxi.com,api.minimax.chat,.minimax.chat
OSS_ENDPOINT=oss-cn-guangzhou.aliyuncs.com
OSS_REGION=cn-guangzhou
OSS_PUBLIC_BUCKET=wlcloudai-assets-public
OSS_PRIVATE_BUCKET=wlcloudai-assets-private
OSS_LEGACY_BUCKET=wlcloudai-assets-prod
OSS_KEY_PREFIX=
BACKUP_OSS_URI=oss://wlcloudai-db-backup-prod/mysql/full
PROMETHEUS_PORT=9091
GRAFANA_PORT=3001
GRAFANA_ROOT_URL=https://wlcloudai.com/grafana/
PROMETHEUS_RETENTION=15d
GRAFANA_ADMIN_USER=admin
CADVISOR_IMAGE=m.daocloud.io/ghcr.io/google/cadvisor:v0.60.5
""".strip().splitlines()

patch = {}
for line in patch_lines:
    if "=" not in line:
        continue
    key, value = line.split("=", 1)
    patch[key] = value

engine_lock_path = Path("/root/ai_tool_market/engines/versions.lock.json")
engine_lock = json.loads(engine_lock_path.read_text(encoding="utf-8"))
patch["BANANA_SLIDES_IMAGE"] = engine_lock["engines"]["banana-slides"]["image"]["reference"]

LEGACY_APPLICATION_PROXY_KEYS = {
    "HTTP_PROXY", "HTTPS_PROXY", "ALL_PROXY",
    "http_proxy", "https_proxy", "all_proxy",
    "CONTAINER_HTTP_PROXY", "CONTAINER_HTTPS_PROXY",
}

OSS_CREDENTIAL_KEYS = (
    "OSS_ACCESS_KEY_ID",
    "OSS_ACCESS_KEY_SECRET",
    "ALIYUN_ACCESS_KEY_ID",
    "ALIYUN_ACCESS_KEY_SECRET",
    "ALIYUN_CAPTCHA_ACCESS_KEY_ID",
    "ALIYUN_CAPTCHA_ACCESS_KEY_SECRET",
)
OSS_REQUIRED_KEYS = (
    "OSS_ENDPOINT",
    "OSS_PUBLIC_BUCKET",
    "OSS_PRIVATE_BUCKET",
)


def read_env(path: Path) -> dict[str, str]:
    if not path.exists():
        return {}
    out: dict[str, str] = {}
    for line in path.read_text(encoding="utf-8", errors="replace").splitlines():
        if "=" not in line or line.strip().startswith("#"):
            continue
        key, value = line.split("=", 1)
        out[key.strip()] = value.strip()
    return out


def has_oss_credentials(data: dict[str, str]) -> bool:
    if any(data.get(key) for key in OSS_CREDENTIAL_KEYS):
        return True
    return all(data.get(key) for key in OSS_REQUIRED_KEYS)


def patch_env(path: Path) -> None:
    existing = {key: value for key, value in read_env(path).items() if key not in LEGACY_APPLICATION_PROXY_KEYS}
    merged = {**existing, **patch}
    if has_oss_credentials(merged):
        merged["ASSET_STORAGE_PROVIDER"] = "oss"
        print("ASSET_STORAGE_PROVIDER=oss (credentials present)")
    else:
        merged.pop("ASSET_STORAGE_PROVIDER", None)
        print("WARN: OSS credentials missing; not forcing ASSET_STORAGE_PROVIDER=oss", path)
    lines = path.read_text(encoding="utf-8", errors="replace").splitlines() if path.exists() else []
    keys = set(merged) | LEGACY_APPLICATION_PROXY_KEYS
    out = []
    for line in lines:
        key = line.split("=", 1)[0].strip()
        if key in keys:
            continue
        out.append(line)
    for key, value in merged.items():
        out.append(f"{key}={value}")
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text("\n".join(out) + "\n", encoding="utf-8")
    print("patched", path)

# docker compose interpolates deploy/.env and overrides env_file; patch both.
patch_env(Path("/root/ai_tool_market/.env"))
patch_env(Path("/root/ai_tool_market/deploy/.env"))

# Re-merge payment keys from backup if a prior deploy stripped them.
backup_path = Path("/root/ai_tool_market/.env.bak.pre-oss-migration")
if backup_path.exists():
    backup = read_env(backup_path)
    for path in (Path("/root/ai_tool_market/.env"), Path("/root/ai_tool_market/deploy/.env")):
        current = read_env(path)
        payment = {
            k: v for k, v in backup.items()
            if k.startswith("WECHAT_") or k.startswith("ALIPAY_")
        }
        if not payment:
            continue
        merged = {**current, **payment}
        lines = path.read_text(encoding="utf-8", errors="replace").splitlines() if path.exists() else []
        keys = set(merged)
        out = [line for line in lines if line.split("=", 1)[0].strip() not in keys]
        out.extend(f"{k}={v}" for k, v in merged.items())
        path.write_text("\n".join(out) + "\n", encoding="utf-8")
        print("preserved payment keys from backup into", path)
PY

python3 - <<'PY'
import secrets
from pathlib import Path

DEFAULT_JWT = "local-dev-secret"
DEFAULT_INTERNAL = "local-internal-token"
DEFAULT_GRAFANA_PASSWORD = "admin123456"
INITIAL_GRAFANA_PASSWORD = "123456"
MIN_JWT_LEN = 32
env = Path("/root/ai_tool_market/.env")

def read_env() -> dict[str, str]:
    if not env.exists():
        return {}
    out: dict[str, str] = {}
    for line in env.read_text(encoding="utf-8", errors="replace").splitlines():
        if "=" not in line or line.strip().startswith("#"):
            continue
        key, value = line.split("=", 1)
        value = value.strip()
        if len(value) >= 2 and value[0] == value[-1] and value[0] in ("'", '"'):
            value = value[1:-1].strip()
        out[key.strip()] = value
    return out

def upsert(key: str, value: str) -> None:
    lines = env.read_text(encoding="utf-8", errors="replace").splitlines() if env.exists() else []
    out = [line for line in lines if line.split("=", 1)[0].strip() != key]
    out.append(f"{key}={value}")
    env.parent.mkdir(parents=True, exist_ok=True)
    env.write_text("\n".join(out) + "\n", encoding="utf-8")

data = read_env()
jwt = data.get("JWT_SECRET", "")
internal = data.get("INTERNAL_API_TOKEN", "")
grafana_password = data.get("GRAFANA_ADMIN_PASSWORD", "")
mihomo_secret = data.get("MIHOMO_CONTROLLER_SECRET", "")
if jwt in ("", DEFAULT_JWT) or len(jwt) < MIN_JWT_LEN:
    upsert("JWT_SECRET", secrets.token_urlsafe(48))
    print("bootstrapped JWT_SECRET for production")
if internal in ("", DEFAULT_INTERNAL):
    upsert("INTERNAL_API_TOKEN", secrets.token_urlsafe(32))
    print("bootstrapped INTERNAL_API_TOKEN for production")
if grafana_password in ("", DEFAULT_GRAFANA_PASSWORD):
    upsert("GRAFANA_ADMIN_PASSWORD", INITIAL_GRAFANA_PASSWORD)
    print("initialized GRAFANA_ADMIN_PASSWORD for production")
if not mihomo_secret:
    upsert("MIHOMO_CONTROLLER_SECRET", secrets.token_urlsafe(32))
    print("bootstrapped MIHOMO_CONTROLLER_SECRET for production")

# docker compose interpolates JWT_SECRET from deploy/.env — mirror secrets there.
root = read_env()
deploy = Path("/root/ai_tool_market/deploy/.env")
lines = deploy.read_text(encoding="utf-8", errors="replace").splitlines() if deploy.exists() else []
for key in ("JWT_SECRET", "INTERNAL_API_TOKEN", "GRAFANA_ADMIN_PASSWORD", "MIHOMO_CONTROLLER_SECRET"):
    value = root.get(key)
    if not value:
        continue
    lines = [line for line in lines if not line.startswith(f"{key}=")]
    lines.append(f"{key}={value}")
deploy.parent.mkdir(parents=True, exist_ok=True)
deploy.write_text("\n".join(lines) + "\n", encoding="utf-8")
print("mirrored secrets to deploy/.env")

# Worker/agent production checks reject placeholder MODEL_API_KEY; reuse SiliconFlow key when set.
root = read_env()
model_key = (root.get("MODEL_API_KEY") or "").strip()
silicon_key = (root.get("SILICONFLOW_API_KEY") or "").strip()
if (not model_key or model_key.startswith("replace-with-")) and silicon_key and not silicon_key.startswith("replace-with-"):
    upsert("MODEL_API_KEY", silicon_key)
    print("bootstrapped MODEL_API_KEY from SILICONFLOW_API_KEY")
    root = read_env()
    lines = deploy.read_text(encoding="utf-8", errors="replace").splitlines() if deploy.exists() else []
    lines = [line for line in lines if not line.startswith("MODEL_API_KEY=")]
    lines.append(f"MODEL_API_KEY={root['MODEL_API_KEY']}")
    deploy.write_text("\n".join(lines) + "\n", encoding="utf-8")
PY

# Fail before container replacement when production secrets are still placeholders or diverge
# between the root env_file and Compose interpolation env.
python3 - <<'PY'
from pathlib import Path

DEFAULTS = {
    "JWT_SECRET": {"", "local-dev-secret", "replace-with-a-strong-jwt-secret"},
    "INTERNAL_API_TOKEN": {"", "local-internal-token", "replace-with-internal-token"},
    "MIHOMO_CONTROLLER_SECRET": {""},
}
MIN_LENGTH = {"JWT_SECRET": 32, "INTERNAL_API_TOKEN": 32, "MIHOMO_CONTROLLER_SECRET": 32}


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
            value = value[1:-1].strip()
        values[key.strip()] = value
    return values


root = read_env(Path("/root/ai_tool_market/.env"))
deploy = read_env(Path("/root/ai_tool_market/deploy/.env"))
for key, defaults in DEFAULTS.items():
    root_value = root.get(key, "")
    deploy_value = deploy.get(key, "")
    if root_value in defaults or len(root_value) < MIN_LENGTH[key]:
        raise SystemExit(f"production secret preflight failed: {key} is missing, default, or too short")
    if deploy_value != root_value:
        raise SystemExit(f"production secret preflight failed: {key} differs between .env and deploy/.env")
print("production secret preflight passed")
PY

SECRET_SNAPSHOT_AFTER="\$(read_secret_snapshot)"
LAST_SECRET_FILE="\$REMOTE_DIR/deploy/logs/last-secret-keys.snapshot"
LAST_SECRET_SNAPSHOT=""
if [ -f "\$LAST_SECRET_FILE" ]; then
  LAST_SECRET_SNAPSHOT="\$(cat "\$LAST_SECRET_FILE")"
fi

force_secret_services=false
if [ -n "\$SECRET_SNAPSHOT_AFTER" ]; then
  if [ "\$SECRET_SNAPSHOT_BEFORE" != "\$SECRET_SNAPSHOT_AFTER" ]; then
    echo "Secret keys changed during deploy env patch; forcing backend worker agent-service recreate"
    force_secret_services=true
  elif [ "\$SECRET_SNAPSHOT_AFTER" != "\$LAST_SECRET_SNAPSHOT" ]; then
    echo "Secret keys differ from last successful deploy; forcing backend worker agent-service recreate"
    force_secret_services=true
  fi
fi

if [ "\$force_secret_services" = true ]; then
  DEPLOY_SERVICES="\$(bash "\$REMOTE_DIR/deploy/scripts/merge_deploy_services.sh" "\$DEPLOY_SERVICES" backend worker agent-service)"
fi

if [ -f "\$REMOTE_DIR/deploy/logs/last-deploy.json" ]; then
  echo "--- last deploy manifest ---"
  cat "\$REMOTE_DIR/deploy/logs/last-deploy.json"
fi
if [ -f "\$REMOTE_DIR/deploy/logs/last-deploy.files.txt" ]; then
  echo "--- changed files ---"
  cat "\$REMOTE_DIR/deploy/logs/last-deploy.files.txt"
fi

cd "\$REMOTE_DIR/deploy"
COMPOSE_ARGS=(--env-file ../.env -f docker-compose.yml -f docker-compose.ppt.yml -f docker-compose.nginx.yml)
if grep -Eqi '^MIHOMO_ENABLED=true$' "\$REMOTE_DIR/.env"; then
  COMPOSE_ARGS+=(-f docker-compose.proxy.yml)
  echo "Mihomo overlay enabled"
fi
if [ -f docker-compose.monitoring.yml ]; then
  COMPOSE_ARGS+=(-f docker-compose.monitoring.yml)
fi

rollback_on_failure() {
  status="\${1:-\$?}"
  trap - ERR HUP INT TERM
  if declare -F cleanup_preflight_user >/dev/null 2>&1; then
    cleanup_preflight_user || true
    trap - EXIT
  fi
  echo "::error::Release failed; starting application rollback." >&2
  if ! REMOTE_DIR="\$REMOTE_DIR" DEPLOY_SERVICES="\$DEPLOY_SERVICES" \
      bash "\$REMOTE_DIR/deploy/scripts/rollback_release.sh"; then
    echo "::error::Automatic rollback also failed; manual intervention is required." >&2
  fi
  exit "\$status"
}
trap rollback_on_failure ERR
trap 'rollback_on_failure 129' HUP
trap 'rollback_on_failure 130' INT TERM

preflight_user_created=false
cleanup_preflight_user() {
  if [ "\$preflight_user_created" = true ]; then
    bash "\$REMOTE_DIR/deploy/scripts/manage_preflight_mysql_user.sh" drop || true
    preflight_user_created=false
  fi
}
trap cleanup_preflight_user EXIT

echo "Pre-pulling missing external images before database or container changes ..."
bash "\$REMOTE_DIR/deploy/scripts/prepull_compose_images.sh" "\${COMPOSE_ARGS[@]}"

if docker inspect mihomo >/dev/null 2>&1; then
  echo "::error::Found unmanaged Mihomo container named mihomo; run the one-time managed-overlay migration before deployment." >&2
  exit 1
fi
if docker inspect ai-supermarket-mihomo >/dev/null 2>&1; then
  mihomo_config_files="\$(docker inspect --format '{{ index .Config.Labels "com.docker.compose.project.config_files" }}' ai-supermarket-mihomo 2>/dev/null || true)"
  if [[ "\$mihomo_config_files" != *docker-compose.proxy.yml* ]]; then
    echo "Removing legacy Mihomo container before managed overlay startup"
    docker rm -f ai-supermarket-mihomo
  fi
fi
docker compose "\${COMPOSE_ARGS[@]}" up -d --pull never mihomo

echo "DEPLOY_SERVICES=\$DEPLOY_SERVICES" | tee -a "\$REMOTE_DIR/deploy/logs/deploy-history.log"

export APP_PRODUCTION_MODE="\$(read_env_value APP_PRODUCTION_MODE)"
export APP_ENV="\$(read_env_value APP_ENV)"
export PRODUCTION_PREFLIGHT_MYSQL_USER
export PRODUCTION_PREFLIGHT_MYSQL_PASSWORD
echo "Preparing persistent production credentials ..."
COMPOSE_PULL_POLICY=never bash "\$REMOTE_DIR/deploy/scripts/prepare_production_credentials.sh"
CURRENT_SECRET_SNAPSHOT="\$(read_secret_snapshot)"
if [ "\$CURRENT_SECRET_SNAPSHOT" != "\$SECRET_SNAPSHOT_AFTER" ]; then
  DEPLOY_SERVICES="\$(bash "\$REMOTE_DIR/deploy/scripts/merge_deploy_services.sh" "\$DEPLOY_SERVICES" backend worker agent-service)"
  SECRET_SNAPSHOT_AFTER="\$CURRENT_SECRET_SNAPSHOT"
  echo "Persistent credentials changed; forcing dependent services to recreate"
fi
echo "Verifying production environment configuration ..."
bash "\$REMOTE_DIR/deploy/scripts/verify_production_environment.sh"

# Apply pending DB migrations BEFORE rebuilding app containers, so the backend
# always boots against an up-to-date schema. MySQL is long-lived; ensure it is up
# first. A real migration failure aborts the deploy (set -e) instead of shipping a
# backend that crashes on a missing table.
echo "Applying pending SQL migrations ..."
docker compose "\${COMPOSE_ARGS[@]}" up -d --pull never mysql
export MYSQL_PASS="\$(read_env_value MYSQL_ROOT_PASSWORD)"
export MYSQL_DB="\$(read_env_value MYSQL_DATABASE)"
export BACKUP_ENCRYPTION_PASSWORD="\$(read_env_value BACKUP_ENCRYPTION_PASSWORD)"
export BACKUP_OSS_URI="\$(read_env_value BACKUP_OSS_URI)"
export OSS_ENDPOINT="\$(read_env_value OSS_ENDPOINT)"
export OSS_REGION="\$(read_env_value OSS_REGION)"
export OSS_ACCESS_KEY_ID="\$(read_env_value OSS_ACCESS_KEY_ID)"
export OSS_ACCESS_KEY_SECRET="\$(read_env_value OSS_ACCESS_KEY_SECRET)"
if [ -z "\$OSS_ACCESS_KEY_ID" ]; then OSS_ACCESS_KEY_ID="\$(read_env_value ALIYUN_ACCESS_KEY_ID)"; fi
if [ -z "\$OSS_ACCESS_KEY_ID" ]; then OSS_ACCESS_KEY_ID="\$(read_env_value ALIYUN_CAPTCHA_ACCESS_KEY_ID)"; fi
if [ -z "\$OSS_ACCESS_KEY_SECRET" ]; then OSS_ACCESS_KEY_SECRET="\$(read_env_value ALIYUN_ACCESS_KEY_SECRET)"; fi
if [ -z "\$OSS_ACCESS_KEY_SECRET" ]; then OSS_ACCESS_KEY_SECRET="\$(read_env_value ALIYUN_CAPTCHA_ACCESS_KEY_SECRET)"; fi
export OSS_ACCESS_KEY_ID OSS_ACCESS_KEY_SECRET
MYSQL_PASS="\${MYSQL_PASS:-root123456}"
MYSQL_DB="\${MYSQL_DB:-ai_supermarket_v1}"
bash "\$REMOTE_DIR/deploy/scripts/manage_preflight_mysql_user.sh" create
preflight_user_created=true
if [ "\$APP_PRODUCTION_MODE" = "true" ] || [ "\$APP_ENV" = "production" ]; then
  echo "Running historical data read-only preflight ..."
  bash "\$REMOTE_DIR/deploy/scripts/production_readonly_preflight.sh" historical
fi
if [ -n "\$BACKUP_ENCRYPTION_PASSWORD" ]; then
  echo "Creating encrypted pre-migration backup ..."
  bash "\$REMOTE_DIR/deploy/scripts/backup_mysql.sh"
else
  echo "::warning::Pre-migration backup skipped: BACKUP_ENCRYPTION_PASSWORD is not configured. This is allowed for development/internal testing only." >&2
fi
bash "\$REMOTE_DIR/deploy/scripts/apply_sql_migrations.sh"
echo "Verifying PPT platform model pool ..."
bash "\$REMOTE_DIR/deploy/scripts/verify_ppt_model_pool.sh"
if [ "\$APP_PRODUCTION_MODE" = "true" ] || [ "\$APP_ENV" = "production" ]; then
  echo "Running post-migration read-only preflight ..."
bash "\$REMOTE_DIR/deploy/scripts/production_readonly_preflight.sh" post-migration
cleanup_preflight_user
fi
printf '%s\n' "\$DEPLOY_SERVICES" > "\$REMOTE_DIR/deploy/logs/last-deploy.services.txt"
chmod 600 "\$REMOTE_DIR/deploy/logs/last-deploy.services.txt"

# Parallel build: launch all builds concurrently, then wait.
echo "Building services in parallel: \$DEPLOY_SERVICES"
if echo "\$DEPLOY_SERVICES" | grep -qw banana-slides; then
  echo "Pulling public immutable Banana Slides image anonymously"
  docker compose "\${COMPOSE_ARGS[@]}" pull banana-slides
fi
pids=()
for svc in \$DEPLOY_SERVICES; do
  case "\$svc" in
    backend|worker|agent-service|admin-frontend|user-web) ;;
    *)
      echo "  Skipping build for image-only service: \$svc"
      continue
      ;;
  esac
  echo "  Starting build: \$svc"
  docker compose "\${COMPOSE_ARGS[@]}" build "\$svc" &
  pids+=(\$!)
done
failed=0
for pid in "\${pids[@]}"; do
  wait "\$pid" || failed=1
done
if [ "\$failed" -ne 0 ]; then
  echo "::error::One or more builds failed; aborting deploy before force-recreate." >&2
  exit 1
fi
EXPECTED_USER_WEB_IMAGE_ID=""
if echo "\$DEPLOY_SERVICES" | grep -qw user-web; then
  EXPECTED_USER_WEB_IMAGE_ID="\$(docker image inspect --format '{{.Id}}' deploy-user-web:latest)"
fi

APP_SERVICES=""
MONITORING_SERVICES=""
MONITORING_STACK="prometheus grafana loki alloy node-exporter cadvisor blackbox-exporter"
for svc in \$DEPLOY_SERVICES; do
  case "\$svc" in
    prometheus|grafana|loki|alloy|node-exporter|cadvisor|blackbox-exporter)
      MONITORING_SERVICES="\$MONITORING_SERVICES \$svc"
      ;;
    mihomo|mihomo-init)
      ;;
    *)
      APP_SERVICES="\$APP_SERVICES \$svc"
      ;;
  esac
done

BACKEND_CONTAINER_ID_BEFORE="\$(docker inspect --format '{{.Id}}' ai-supermarket-backend 2>/dev/null || true)"
BACKEND_RESTART_COUNT_BEFORE="\$(docker inspect --format '{{.RestartCount}}' ai-supermarket-backend 2>/dev/null || true)"
BACKEND_WILL_RECREATE=false
if echo "\$APP_SERVICES" | grep -qw backend; then
  BACKEND_WILL_RECREATE=true
elif [ -z "\$BACKEND_CONTAINER_ID_BEFORE" ] \
    || ! [[ "\$BACKEND_RESTART_COUNT_BEFORE" =~ ^[0-9]+$ ]]; then
  echo "::error::Unable to capture the existing backend stability baseline." >&2
  exit 1
fi

if [ -n "\$APP_SERVICES" ]; then
  echo "Force-recreating application containers:\$APP_SERVICES"
  docker compose "\${COMPOSE_ARGS[@]}" up -d --force-recreate --no-deps --no-build --pull never \$APP_SERVICES
fi
if [ -n "\$EXPECTED_USER_WEB_IMAGE_ID" ]; then
  RUNNING_USER_WEB_IMAGE_ID="\$(docker inspect --format '{{.Image}}' ai-supermarket-user-web)"
  if [ "\$RUNNING_USER_WEB_IMAGE_ID" != "\$EXPECTED_USER_WEB_IMAGE_ID" ]; then
    echo "::error::user-web container is not running the image built by this release" >&2
    false
  fi
  echo "Verified user-web image: \$RUNNING_USER_WEB_IMAGE_ID"
fi

if [ -n "\$MONITORING_SERVICES" ]; then
  echo "Starting/updating monitoring containers:\$MONITORING_SERVICES"
  docker compose "\${COMPOSE_ARGS[@]}" up -d --force-recreate --no-build --pull never \$MONITORING_SERVICES
fi

echo "Ensuring complete monitoring stack: \$MONITORING_STACK"
docker compose "\${COMPOSE_ARGS[@]}" up -d --no-build --pull never \$MONITORING_STACK

# nginx 反代静态资源；任意前端/配置变更后都 reload，避免 user_web_dist 已更新但 nginx 仍握旧连接。
docker compose "\${COMPOSE_ARGS[@]}" restart nginx

if [ "\$BACKEND_WILL_RECREATE" = true ]; then
  BACKEND_EXPECTED_CONTAINER_ID="\$(docker inspect --format '{{.Id}}' ai-supermarket-backend 2>/dev/null || true)"
  BACKEND_RESTART_BASELINE=0
  if [ -z "\$BACKEND_EXPECTED_CONTAINER_ID" ] \
      || { [ -n "\$BACKEND_CONTAINER_ID_BEFORE" ] \
        && [ "\$BACKEND_EXPECTED_CONTAINER_ID" = "\$BACKEND_CONTAINER_ID_BEFORE" ]; }; then
    echo "::error::Backend force-recreate did not produce a new inspectable container." >&2
    exit 1
  fi
else
  BACKEND_EXPECTED_CONTAINER_ID="\$BACKEND_CONTAINER_ID_BEFORE"
  BACKEND_RESTART_BASELINE="\$BACKEND_RESTART_COUNT_BEFORE"
fi

echo "Verifying release health..."
BACKEND_EXPECTED_CONTAINER_ID="\$BACKEND_EXPECTED_CONTAINER_ID" \
BACKEND_RESTART_BASELINE="\$BACKEND_RESTART_BASELINE" \
  bash "\$REMOTE_DIR/deploy/scripts/verify_release_health.sh"
echo "Running authenticated PPT production smoke..."
PPT_SMOKE_AUTH_TOKEN="\$(printf '%s' "\$PPT_SMOKE_AUTH_TOKEN_B64" | base64 -d)" \
  python3 "\$REMOTE_DIR/deploy/scripts/smoke_ppt_workbench.py"
if echo "\$DEPLOY_SERVICES" | grep -qw agent-service; then
  echo "Checking agent-service outbound model connectivity ..."
  python3 "\$REMOTE_DIR/deploy/scripts/check_outbound_proxy.py"
fi
if echo "\$DEPLOY_SERVICES" | grep -qw user-web; then
  echo "Writing user-web build-info.json ..."
  docker exec ai-supermarket-user-web sh -c "printf '%s\\n' '{\"gitSha\":\"'\$RELEASE_SHA'\",\"builtAt\":\"'\"\$(date -Iseconds)\"'\"}' > /dist-out/build-info.json"
  BUILD_INFO_JSON=""
  BUILD_INFO_SHA=""
  for build_info_attempt in \$(seq 1 10); do
    CANDIDATE_BUILD_INFO_JSON=""
    CANDIDATE_BUILD_INFO_SHA=""
    if CANDIDATE_BUILD_INFO_JSON="\$(curl --silent --show-error --fail --noproxy '*' --resolve wlcloudai.com:443:127.0.0.1 --max-time 10 "https://wlcloudai.com/build-info.json?release=\$RELEASE_SHA")" \
      && [ -n "\$CANDIDATE_BUILD_INFO_JSON" ] \
      && CANDIDATE_BUILD_INFO_SHA="\$(printf '%s' "\$CANDIDATE_BUILD_INFO_JSON" | python3 -c 'import json, sys; print(json.load(sys.stdin).get("gitSha", ""))' 2>/dev/null)" \
      && [ "\$CANDIDATE_BUILD_INFO_SHA" = "\$RELEASE_SHA" ]; then
      BUILD_INFO_JSON="\$CANDIDATE_BUILD_INFO_JSON"
      BUILD_INFO_SHA="\$CANDIDATE_BUILD_INFO_SHA"
      break
    fi
    BUILD_INFO_JSON="\$CANDIDATE_BUILD_INFO_JSON"
    BUILD_INFO_SHA="\$CANDIDATE_BUILD_INFO_SHA"
    echo "build-info read attempt \$build_info_attempt/10 failed or did not match release; retrying in 2s" >&2
    sleep 2
  done
  if [ "\$BUILD_INFO_SHA" != "\$RELEASE_SHA" ]; then
    echo "::error::Production user-web build-info remained unavailable after retries or did not match release: expected \$RELEASE_SHA, got \${BUILD_INFO_SHA:-missing}" >&2
    false
  fi
  echo "user-web build-info: \$BUILD_INFO_JSON"
  js_bundle="\$(docker exec ai-supermarket-nginx sh -c 'ls /usr/share/nginx/user-web/assets/index-*.js 2>/dev/null | head -1' || true)"
  echo "user-web bundle: \${js_bundle:-unknown}"
else
  echo "user-web was not deployed; preserving its existing build-info.json"
fi
docker compose "\${COMPOSE_ARGS[@]}" ps

if [ -n "\${SECRET_SNAPSHOT_AFTER:-}" ]; then
  mkdir -p "\$REMOTE_DIR/deploy/logs"
  SECRET_TMP="\$(mktemp "\$REMOTE_DIR/deploy/logs/.last-secret-keys.XXXXXX")"
  printf '%s' "\$SECRET_SNAPSHOT_AFTER" > "\$SECRET_TMP"
  chmod 600 "\$SECRET_TMP"
  mv -f "\$SECRET_TMP" "\$LAST_SECRET_FILE"
fi

# The runner promotes these files only after the public production gate.
PENDING_META_TMP="\$(mktemp "\$REMOTE_DIR/.deploy_meta.pending.XXXXXX")"
PENDING_REVISION_TMP="\$(mktemp "\$REMOTE_DIR/.deploy_revision.pending.XXXXXX")"
printf '%s\n' "\$DEPLOY_EVENT" "\$DEPLOY_GIT_BRANCH" "\$DEPLOY_PR_NUMBER" > "\$PENDING_META_TMP"
printf '%s\n' "\$RELEASE_SHA" > "\$PENDING_REVISION_TMP"
chmod 600 "\$PENDING_META_TMP" "\$PENDING_REVISION_TMP"
mv -f "\$PENDING_META_TMP" "\$REMOTE_DIR/.deploy_meta.pending"
mv -f "\$PENDING_REVISION_TMP" "\$REMOTE_DIR/.deploy_revision.pending"
echo "Internal release gate passed; awaiting public verification for \$RELEASE_SHA"
trap - ERR HUP INT TERM
trap - EXIT
REMOTE

ACTUAL_DEPLOY_SERVICES="$(ssh_cmd "cat '$REMOTE_DIR/deploy/logs/last-deploy.services.txt'")"
if [[ -z "$ACTUAL_DEPLOY_SERVICES" || ! "$ACTUAL_DEPLOY_SERVICES" =~ ^[a-z0-9-]+([[:space:]][a-z0-9-]+)*$ ]]; then
  echo "Invalid actual deploy services from production: ${ACTUAL_DEPLOY_SERVICES:-missing}" >&2
  false
fi
echo "Actual production services: $ACTUAL_DEPLOY_SERVICES"
if [ -n "${GITHUB_OUTPUT:-}" ]; then
  printf 'services=%s\n' "$ACTUAL_DEPLOY_SERVICES" >> "$GITHUB_OUTPUT"
fi

for url in http://wlcloudai.com/ http://wlcloudai.com/api/health; do
  code="$(curl --silent --show-error --location --output /dev/null --write-out '%{http_code}' --max-time 30 "$url")"
  echo "$url -> $code"
  if [[ ! "$code" =~ ^2[0-9]{2}$ ]]; then
    echo "::error::Public release gate failed for $url (HTTP $code)." >&2
    false
  fi
done
if [[ " $ACTUAL_DEPLOY_SERVICES " == *" user-web "* ]]; then
  public_build_info="$(curl --silent --show-error --location --fail --max-time 30 https://wlcloudai.com/build-info.json)"
  public_user_web_sha="$(printf '%s' "$public_build_info" | python3 -c 'import json, sys; print(json.load(sys.stdin).get("gitSha", ""))')"
  if [ "$public_user_web_sha" != "${GITHUB_SHA:-unknown}" ]; then
    echo "::error::Public user-web SHA mismatch: expected ${GITHUB_SHA:-unknown}, got ${public_user_web_sha:-missing}." >&2
    false
  fi
fi

ssh_cmd env REMOTE_DIR="$REMOTE_DIR" EXPECTED_SHA="${GITHUB_SHA:-unknown}" \
  bash "$REMOTE_DIR/deploy/scripts/finalize_production_release.sh"
trap - ERR HUP INT TERM
echo "Light deploy finished and publicly verified (mode=$DEPLOY_SYNC_MODE)."
