#!/usr/bin/env bash
# Lightweight production deploy: git incremental sync (default) or rsync fallback.
# Password from env only — never commit credentials.
set -euo pipefail

: "${DEPLOY_HOST:?DEPLOY_HOST is required}"
: "${DEPLOY_USER:?DEPLOY_USER is required}"
: "${DEPLOY_PASSWORD:?DEPLOY_PASSWORD is required}"
: "${PRODUCTION_PREFLIGHT_MYSQL_USER:?Runner-generated preflight MySQL user is required}"
: "${PRODUCTION_PREFLIGHT_MYSQL_PASSWORD:?Runner-generated preflight MySQL password is required}"

DEPLOY_SYNC_MODE="${DEPLOY_SYNC_MODE:-git}"
REMOTE_DIR="/root/ai_tool_market"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
SSH_OPTS=(-o StrictHostKeyChecking=accept-new -o UserKnownHostsFile=/dev/null -o ServerAliveInterval=30 -o ServerAliveCountMax=120 -o TCPKeepAlive=yes)
GIT_REPO="${DEPLOY_GIT_REPO:-https://github.com/AI-miniLab/ai-tool-market.git}"
GIT_BRANCH="${DEPLOY_GIT_BRANCH:-dev}"
DEPLOY_GIT_REF="${DEPLOY_GIT_REF:-${GITHUB_SHA:-dev}}"
DEPLOY_EVENT="${DEPLOY_EVENT:-${GITHUB_EVENT_NAME:-push}}"
DEPLOY_PR_NUMBER="${DEPLOY_PR_NUMBER:-}"

ssh_cmd() {
  sshpass -p "$DEPLOY_PASSWORD" ssh "${SSH_OPTS[@]}" "${DEPLOY_USER}@${DEPLOY_HOST}" "$@"
}

scp_cmd() {
  sshpass -p "$DEPLOY_PASSWORD" scp "${SSH_OPTS[@]}" "$@"
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

if [ "$DEPLOY_SYNC_MODE" = "rsync" ]; then
  ssh_cmd "mkdir -p '$REMOTE_DIR'"
  rsync -az --delete \
    --exclude-from="$SCRIPT_DIR/rsync_exclude.txt" \
    -e "sshpass -p '$DEPLOY_PASSWORD' ssh ${SSH_OPTS[*]}" \
    "$ROOT/" "${DEPLOY_USER}@${DEPLOY_HOST}:${REMOTE_DIR}/"
elif [ "$DEPLOY_SYNC_MODE" = "git" ]; then
  CLONE_URL="$GIT_REPO"
  if [ -n "${GITHUB_TOKEN:-}" ]; then
    CLONE_URL="https://x-access-token:${GITHUB_TOKEN}@github.com/AI-miniLab/ai-tool-market.git"
  fi
  scp_cmd "$SCRIPT_DIR/remote_production_git_sync.sh" "${DEPLOY_USER}@${DEPLOY_HOST}:/tmp/production_git_sync.sh"
  ssh_cmd "chmod +x /tmp/production_git_sync.sh"
  ssh_cmd env \
    REMOTE_DIR="$REMOTE_DIR" \
    GIT_REPO_URL="$CLONE_URL" \
    DEPLOY_GIT_REF="$DEPLOY_GIT_REF" \
    DEPLOY_EVENT="$DEPLOY_EVENT" \
    DEPLOY_GIT_BRANCH="$GIT_BRANCH" \
    DEPLOY_PR_NUMBER="$DEPLOY_PR_NUMBER" \
    GITHUB_SHA="${GITHUB_SHA:-}" \
    GITHUB_RUN_ID="${GITHUB_RUN_ID:-}" \
    GITHUB_ACTOR="${GITHUB_ACTOR:-}" \
    /tmp/production_git_sync.sh
else
  echo "Unknown DEPLOY_SYNC_MODE=$DEPLOY_SYNC_MODE (use rsync or git)" >&2
  exit 1
fi

# Remote: patch env, rebuild only changed services, health check
run_remote_script <<REMOTE
set -euo pipefail
REMOTE_DIR="$REMOTE_DIR"
DEPLOY_SERVICES="$DEPLOY_SERVICES"
GITHUB_SHA="${GITHUB_SHA:-unknown}"
PRODUCTION_PREFLIGHT_MYSQL_USER="$PRODUCTION_PREFLIGHT_MYSQL_USER"
PRODUCTION_PREFLIGHT_MYSQL_PASSWORD="$PRODUCTION_PREFLIGHT_MYSQL_PASSWORD"

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
MIHOMO_ENABLED=true
NO_PROXY=localhost,127.0.0.1,mysql,redis,rabbitmq,backend,agent-service,admin-frontend,user-web,nginx,host.docker.internal,wlcloudai.com,8.134.93.203,.aliyuncs.com,.aliyun.com,.cn,.klingai.com,api.deepseek.com,.deepseek.com,ark.cn-beijing.volces.com,.volces.com,api.minimaxi.com,.minimaxi.com,api.minimax.chat,.minimax.chat
CONTAINER_NO_PROXY=localhost,127.0.0.1,mysql,redis,rabbitmq,backend,agent-service,admin-frontend,user-web,nginx,host.docker.internal,wlcloudai.com,8.134.93.203,.aliyuncs.com,.aliyun.com,.cn,.klingai.com,api.deepseek.com,.deepseek.com,ark.cn-beijing.volces.com,.volces.com,api.minimaxi.com,.minimaxi.com,api.minimax.chat,.minimax.chat
OSS_ENDPOINT=oss-cn-guangzhou.aliyuncs.com
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
COMPOSE_ARGS=(--env-file ../.env -f docker-compose.yml -f docker-compose.nginx.yml)
if grep -Eqi '^MIHOMO_ENABLED=true$' "\$REMOTE_DIR/.env"; then
  COMPOSE_ARGS+=(-f docker-compose.proxy.yml)
  echo "Mihomo overlay enabled"
fi
if [ -f docker-compose.monitoring.yml ]; then
  COMPOSE_ARGS+=(-f docker-compose.monitoring.yml)
fi
if echo "\$DEPLOY_SERVICES" | grep -qw banana-slides; then
  COMPOSE_ARGS+=(--profile banana-slides)
fi

rollback_on_failure() {
  status=\$?
  trap - ERR
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

preflight_user_created=false
cleanup_preflight_user() {
  if [ "\$preflight_user_created" = true ]; then
    bash "\$REMOTE_DIR/deploy/scripts/manage_preflight_mysql_user.sh" drop || true
    preflight_user_created=false
  fi
}
trap cleanup_preflight_user EXIT

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
docker compose "\${COMPOSE_ARGS[@]}" up -d mihomo

echo "DEPLOY_SERVICES=\$DEPLOY_SERVICES" | tee -a "\$REMOTE_DIR/deploy/logs/deploy-history.log"

export APP_PRODUCTION_MODE="\$(read_env_value APP_PRODUCTION_MODE)"
export APP_ENV="\$(read_env_value APP_ENV)"
export PRODUCTION_PREFLIGHT_MYSQL_USER
export PRODUCTION_PREFLIGHT_MYSQL_PASSWORD
echo "Preparing persistent production credentials ..."
bash "\$REMOTE_DIR/deploy/scripts/prepare_production_credentials.sh"
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
docker compose "\${COMPOSE_ARGS[@]}" up -d mysql
export MYSQL_PASS="\$(read_env_value MYSQL_ROOT_PASSWORD)"
export MYSQL_DB="\$(read_env_value MYSQL_DATABASE)"
export BACKUP_ENCRYPTION_PASSWORD="\$(read_env_value BACKUP_ENCRYPTION_PASSWORD)"
export BACKUP_OSS_URI="\$(read_env_value BACKUP_OSS_URI)"
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
if [ "\$APP_PRODUCTION_MODE" = "true" ] || [ "\$APP_ENV" = "production" ]; then
  echo "Running post-migration read-only preflight ..."
bash "\$REMOTE_DIR/deploy/scripts/production_readonly_preflight.sh" post-migration
cleanup_preflight_user
fi

# Parallel build: launch all builds concurrently, then wait.
echo "Building services in parallel: \$DEPLOY_SERVICES"
pids=()
for svc in \$DEPLOY_SERVICES; do
  case "\$svc" in
    backend|worker|agent-service|admin-frontend|user-web|banana-slides) ;;
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

if [ -n "\$APP_SERVICES" ]; then
  echo "Force-recreating application containers:\$APP_SERVICES"
  docker compose "\${COMPOSE_ARGS[@]}" up -d --force-recreate --no-deps \$APP_SERVICES
fi

if [ -n "\$MONITORING_SERVICES" ]; then
  echo "Starting/updating monitoring containers:\$MONITORING_SERVICES"
  docker compose "\${COMPOSE_ARGS[@]}" up -d --force-recreate \$MONITORING_SERVICES
fi

echo "Ensuring complete monitoring stack: \$MONITORING_STACK"
docker compose "\${COMPOSE_ARGS[@]}" up -d \$MONITORING_STACK

# nginx 反代静态资源；任意前端/配置变更后都 reload，避免 user_web_dist 已更新但 nginx 仍握旧连接。
docker compose "\${COMPOSE_ARGS[@]}" restart nginx

echo "Verifying release health..."
bash "\$REMOTE_DIR/deploy/scripts/verify_release_health.sh"
if echo "\$DEPLOY_SERVICES" | grep -qw agent-service; then
  echo "Checking agent-service outbound model connectivity ..."
  python3 "\$REMOTE_DIR/deploy/scripts/check_outbound_proxy.py"
fi
echo "Writing production release build-info.json ..."
docker exec ai-supermarket-user-web sh -c "printf '%s\\n' '{\"gitSha\":\"'\$GITHUB_SHA'\",\"builtAt\":\"'\"\$(date -Iseconds)\"'\"}' > /dist-out/build-info.json"
if echo "\$DEPLOY_SERVICES" | grep -qw user-web; then
  echo "build-info:" && curl -sf http://127.0.0.1/build-info.json || echo "(build-info pending)"
  js_bundle="\$(docker exec ai-supermarket-nginx sh -c 'ls /usr/share/nginx/user-web/assets/index-*.js 2>/dev/null | head -1' || true)"
  echo "user-web bundle: \${js_bundle:-unknown}"
fi
docker compose "\${COMPOSE_ARGS[@]}" ps
trap - ERR
trap - EXIT

if [ -n "\${SECRET_SNAPSHOT_AFTER:-}" ]; then
  mkdir -p "\$REMOTE_DIR/deploy/logs"
  printf '%s' "\$SECRET_SNAPSHOT_AFTER" > "\$LAST_SECRET_FILE"
  chmod 600 "\$LAST_SECRET_FILE"
fi
REMOTE

echo "Light deploy finished (mode=$DEPLOY_SYNC_MODE)."
