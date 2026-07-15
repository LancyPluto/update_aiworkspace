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
from pathlib import Path

path = Path("/root/ai_tool_market/.env")
patch_lines = """
APP_PRODUCTION_MODE=true
VITE_API_BASE_URL=
VITE_DEV_PROXY_TARGET=http://backend:8080
ADMIN_NEXT_PUBLIC_API_BASE_URL=
ADMIN_NEXT_PUBLIC_API_PROXY_TARGET=http://backend:8080
CORS_ALLOWED_ORIGINS=http://wlcloudai.com,http://www.wlcloudai.com,http://8.134.93.203
HTTP_PROXY=http://host.docker.internal:7890
HTTPS_PROXY=http://host.docker.internal:7890
CONTAINER_HTTP_PROXY=http://host.docker.internal:7890
CONTAINER_HTTPS_PROXY=http://host.docker.internal:7890
NO_PROXY=localhost,127.0.0.1,mysql,redis,rabbitmq,backend,agent-service,admin-frontend,user-web,nginx,host.docker.internal,wlcloudai.com,8.134.93.203,.aliyuncs.com,.aliyun.com,.cn,api.deepseek.com,.deepseek.com,ark.cn-beijing.volces.com,.volces.com,api.minimaxi.com,.minimaxi.com,api.minimax.chat,.minimax.chat
CONTAINER_NO_PROXY=localhost,127.0.0.1,mysql,redis,rabbitmq,backend,agent-service,admin-frontend,user-web,nginx,host.docker.internal,wlcloudai.com,8.134.93.203,.aliyuncs.com,.aliyun.com,.cn,api.deepseek.com,.deepseek.com,ark.cn-beijing.volces.com,.volces.com,api.minimaxi.com,.minimaxi.com,api.minimax.chat,.minimax.chat
""".strip().splitlines()

patch = {}
for line in patch_lines:
    if "=" not in line:
        continue
    key, value = line.split("=", 1)
    patch[key] = value

lines = path.read_text(encoding="utf-8", errors="replace").splitlines() if path.exists() else []
keys = set(patch)
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

docker compose "${COMPOSE_ARGS[@]}" up -d --build --force-recreate \
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
