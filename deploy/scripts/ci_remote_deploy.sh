#!/usr/bin/env bash
# Remote production deploy via SSH (password from env; never commit credentials).
set -euo pipefail

: "${DEPLOY_HOST:?DEPLOY_HOST is required}"
: "${DEPLOY_USER:?DEPLOY_USER is required}"
: "${DEPLOY_PASSWORD:?DEPLOY_PASSWORD is required}"

REMOTE_DIR="/root/ai_tool_market"
REMOTE_TAR="/tmp/ai_tool_market_ci.tar.gz"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ARCHIVE="${1:-/tmp/ai_tool_market_ci.tar.gz}"

if [[ ! -f "$ARCHIVE" ]]; then
  echo "Archive not found: $ARCHIVE" >&2
  exit 1
fi

SSH_OPTS=(-o StrictHostKeyChecking=accept-new -o UserKnownHostsFile=/dev/null)

sshpass -p "$DEPLOY_PASSWORD" scp "${SSH_OPTS[@]}" "$ARCHIVE" "${DEPLOY_USER}@${DEPLOY_HOST}:${REMOTE_TAR}"

sshpass -p "$DEPLOY_PASSWORD" ssh "${SSH_OPTS[@]}" "${DEPLOY_USER}@${DEPLOY_HOST}" bash -s <<'REMOTE'
set -euo pipefail
REMOTE_DIR="/root/ai_tool_market"
REMOTE_TAR="/tmp/ai_tool_market_ci.tar.gz"

preserve_file() {
  local rel="$1"
  local src="${REMOTE_DIR}/${rel}"
  local bak="/tmp/ai_tool_market_preserve_${rel//\//_}"
  if [[ -f "$src" ]]; then
    cp "$src" "$bak"
  fi
}

restore_file() {
  local rel="$1"
  local src="${REMOTE_DIR}/${rel}"
  local bak="/tmp/ai_tool_market_preserve_${rel//\//_}"
  if [[ -f "$bak" ]]; then
    mkdir -p "$(dirname "$src")"
    cp "$bak" "$src"
  fi
}

preserve_file ".env"
preserve_file "engines/banana-slides/.env"

rm -rf "$REMOTE_DIR"
mkdir -p "$REMOTE_DIR"
tar -xzf "$REMOTE_TAR" -C "$REMOTE_DIR"
rm -f "$REMOTE_TAR"

restore_file ".env"
restore_file "engines/banana-slides/.env"

python3 - <<'PY'
import secrets
from pathlib import Path

path = Path("/root/ai_tool_market/.env")
patch_lines = """
APP_PRODUCTION_MODE=true
VITE_API_BASE_URL=
VITE_DEV_PROXY_TARGET=http://backend:8080
ADMIN_NEXT_PUBLIC_API_BASE_URL=
ADMIN_NEXT_PUBLIC_API_PROXY_TARGET=http://backend:8080
CORS_ALLOWED_ORIGINS=http://wlcloudai.com,http://www.wlcloudai.com,http://8.134.93.203
MIHOMO_ENABLED=true
NO_PROXY=localhost,127.0.0.1,mysql,redis,rabbitmq,backend,agent-service,admin-frontend,user-web,nginx,host.docker.internal,wlcloudai.com,8.134.93.203,.aliyuncs.com,.aliyun.com,.cn,.klingai.com,api.deepseek.com,.deepseek.com,ark.cn-beijing.volces.com,.volces.com,api.minimaxi.com,.minimaxi.com,api.minimax.chat,.minimax.chat
CONTAINER_NO_PROXY=localhost,127.0.0.1,mysql,redis,rabbitmq,backend,agent-service,admin-frontend,user-web,nginx,host.docker.internal,wlcloudai.com,8.134.93.203,.aliyuncs.com,.aliyun.com,.cn,.klingai.com,api.deepseek.com,.deepseek.com,ark.cn-beijing.volces.com,.volces.com,api.minimaxi.com,.minimaxi.com,api.minimax.chat,.minimax.chat
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

lines = path.read_text(encoding="utf-8", errors="replace").splitlines() if path.exists() else []
existing = {}
for line in lines:
    if "=" not in line or line.lstrip().startswith("#"):
        continue
    key, value = line.split("=", 1)
    existing[key.strip()] = value.strip().strip('"').strip("'")
if not existing.get("MIHOMO_CONTROLLER_SECRET"):
    patch["MIHOMO_CONTROLLER_SECRET"] = secrets.token_urlsafe(32)
keys = set(patch) | LEGACY_APPLICATION_PROXY_KEYS
out = []
for line in lines:
    key = line.split("=", 1)[0].strip()
    if key in keys:
        continue
    out.append(line)
for key, value in patch.items():
    out.append(f"{key}={value}")
path.parent.mkdir(parents=True, exist_ok=True)
path.write_text("\n".join(out) + "\n", encoding="utf-8")
print("patched", path)
PY

cd "$REMOTE_DIR/deploy"
COMPOSE_ARGS=(--env-file ../.env -f docker-compose.yml -f docker-compose.nginx.yml)
if grep -Eqi '^MIHOMO_ENABLED=true$' "$REMOTE_DIR/.env"; then
  COMPOSE_ARGS+=(-f docker-compose.proxy.yml)
  echo "Mihomo overlay enabled"
fi

if docker inspect mihomo >/dev/null 2>&1; then
  echo "ERROR: Found unmanaged Mihomo container named mihomo; run the one-time managed-overlay migration before deployment." >&2
  exit 1
fi
if docker inspect ai-supermarket-mihomo >/dev/null 2>&1; then
  mihomo_config_files="$(docker inspect --format '{{ index .Config.Labels "com.docker.compose.project.config_files" }}' ai-supermarket-mihomo 2>/dev/null || true)"
  if [[ "$mihomo_config_files" != *docker-compose.proxy.yml* ]]; then
    echo "Removing legacy Mihomo container before managed overlay startup"
    docker rm -f ai-supermarket-mihomo
  fi
fi
docker compose "${COMPOSE_ARGS[@]}" up -d mihomo

docker compose "${COMPOSE_ARGS[@]}" up -d --build --force-recreate --no-deps \
  backend worker agent-service admin-frontend user-web nginx

echo "Waiting for user-web health..."
for i in $(seq 1 48); do
  health="$(docker inspect --format '{{.State.Health.Status}}' ai-supermarket-user-web 2>/dev/null || echo missing)"
  echo "  attempt $i: user-web=$health"
  if [[ "$health" == "healthy" ]]; then
    break
  fi
  sleep 10
done

curl -sf -o /dev/null -w "root:%{http_code}\n" http://127.0.0.1/ || true
curl -sf -o /dev/null -w "api:%{http_code}\n" http://127.0.0.1/api/health || true
curl -sf -o /dev/null -w "admin:%{http_code}\n" -L http://127.0.0.1/admin || true

docker compose "${COMPOSE_ARGS[@]}" ps
REMOTE

echo "Production deploy finished."
