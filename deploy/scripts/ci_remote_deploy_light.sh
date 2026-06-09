#!/usr/bin/env bash
# Lightweight production deploy: incremental rsync or server-side git pull.
# Password from env only — never commit credentials.
set -euo pipefail

: "${DEPLOY_HOST:?DEPLOY_HOST is required}"
: "${DEPLOY_USER:?DEPLOY_USER is required}"
: "${DEPLOY_PASSWORD:?DEPLOY_PASSWORD is required}"

DEPLOY_SYNC_MODE="${DEPLOY_SYNC_MODE:-rsync}"
REMOTE_DIR="/root/ai_tool_market"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
SSH_OPTS=(-o StrictHostKeyChecking=accept-new -o UserKnownHostsFile=/dev/null)
GIT_REPO="${DEPLOY_GIT_REPO:-https://github.com/AI-miniLab/ai-tool-market.git}"
GIT_BRANCH="${DEPLOY_GIT_BRANCH:-dev}"

ssh_cmd() {
  sshpass -p "$DEPLOY_PASSWORD" ssh "${SSH_OPTS[@]}" "${DEPLOY_USER}@${DEPLOY_HOST}" "$@"
}

scp_cmd() {
  sshpass -p "$DEPLOY_PASSWORD" scp "${SSH_OPTS[@]}" "$@"
}

if [ -z "${DEPLOY_SERVICES:-}" ]; then
  DEPLOY_SERVICES="$(bash "$SCRIPT_DIR/detect_deploy_services.sh")"
fi

echo "Deploy mode=$DEPLOY_SYNC_MODE services=$DEPLOY_SERVICES"

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
  ssh_cmd "bash -s" <<REMOTE_GIT
set -euo pipefail
REMOTE_DIR="$REMOTE_DIR"
CLONE_URL="$CLONE_URL"
GIT_BRANCH="$GIT_BRANCH"
if [ ! -d "\$REMOTE_DIR/.git" ]; then
  rm -rf "\$REMOTE_DIR"
  git clone --branch "\$GIT_BRANCH" --depth 1 "\$CLONE_URL" "\$REMOTE_DIR"
else
  cd "\$REMOTE_DIR"
  git remote set-url origin "\$CLONE_URL"
  git fetch origin "\$GIT_BRANCH" --depth 1
  git checkout "\$GIT_BRANCH"
  git reset --hard "origin/\$GIT_BRANCH"
fi
REMOTE_GIT
else
  echo "Unknown DEPLOY_SYNC_MODE=$DEPLOY_SYNC_MODE (use rsync or git)" >&2
  exit 1
fi

# Remote: patch env, rebuild only changed services, health check
ssh_cmd "bash -s" <<REMOTE
set -euo pipefail
REMOTE_DIR="$REMOTE_DIR"
DEPLOY_SERVICES="$DEPLOY_SERVICES"
GITHUB_SHA="${GITHUB_SHA:-unknown}"

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
NO_PROXY=localhost,127.0.0.1,mysql,redis,rabbitmq,backend,agent-service,admin-frontend,user-web,nginx,wlcloudai.com,8.134.93.203,.aliyuncs.com,.aliyun.com,.cn
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

echo "\$GITHUB_SHA" > "\$REMOTE_DIR/.deploy_revision"
cd "\$REMOTE_DIR/deploy"

for svc in \$DEPLOY_SERVICES; do
  echo "Building \$svc ..."
  docker compose -f docker-compose.yml -f docker-compose.nginx.yml build "\$svc"
done

docker compose -f docker-compose.yml -f docker-compose.nginx.yml up -d --force-recreate \$DEPLOY_SERVICES

echo "Waiting for user-web health..."
for i in \$(seq 1 36); do
  health="\$(docker inspect --format '{{.State.Health.Status}}' ai-supermarket-user-web 2>/dev/null || echo missing)"
  echo "  attempt \$i: user-web=\$health"
  if [[ "\$health" == "healthy" ]]; then
    break
  fi
  sleep 10
done

curl -sf -o /dev/null -w "root:%{http_code}\n" http://127.0.0.1/ || true
curl -sf -o /dev/null -w "api:%{http_code}\n" http://127.0.0.1/api/health || true
curl -sf -o /dev/null -w "admin:%{http_code}\n" -L http://127.0.0.1/admin || true
docker compose -f docker-compose.yml -f docker-compose.nginx.yml ps
REMOTE

echo "Light deploy finished (mode=$DEPLOY_SYNC_MODE)."
