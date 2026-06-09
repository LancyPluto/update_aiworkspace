#!/usr/bin/env bash
# Fast local → production sync for debugging (incremental rsync, optional service restart).
# Does NOT run CI tests. Use when you need to hot-fix production quickly.
#
# Usage:
#   export DEPLOY_HOST=8.134.93.203 DEPLOY_USER=root DEPLOY_PASSWORD='...'
#   bash deploy/scripts/quick_sync_local.sh                    # sync only
#   bash deploy/scripts/quick_sync_local.sh --restart backend  # sync + restart one service
#   bash deploy/scripts/quick_sync_local.sh --restart all      # sync + rebuild all app services
set -euo pipefail

: "${DEPLOY_HOST:?Set DEPLOY_HOST}"
: "${DEPLOY_USER:?Set DEPLOY_USER}"
: "${DEPLOY_PASSWORD:?Set DEPLOY_PASSWORD}"

REMOTE_DIR="/root/ai_tool_market"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
SSH_OPTS=(-o StrictHostKeyChecking=accept-new -o UserKnownHostsFile=/dev/null)

RESTART_TARGET=""
while [ "$#" -gt 0 ]; do
  case "$1" in
    --restart)
      RESTART_TARGET="${2:-}"
      shift 2
      ;;
    *)
      echo "Unknown arg: $1" >&2
      exit 1
      ;;
  esac
done

echo "Quick rsync → ${DEPLOY_USER}@${DEPLOY_HOST}:${REMOTE_DIR}"
sshpass -p "$DEPLOY_PASSWORD" ssh "${SSH_OPTS[@]}" "${DEPLOY_USER}@${DEPLOY_HOST}" "mkdir -p '$REMOTE_DIR'"

rsync -avz --delete \
  --exclude-from="$SCRIPT_DIR/rsync_exclude.txt" \
  -e "sshpass -p '$DEPLOY_PASSWORD' ssh ${SSH_OPTS[*]}" \
  "$ROOT/" "${DEPLOY_USER}@${DEPLOY_HOST}:${REMOTE_DIR}/"

if [ -z "$RESTART_TARGET" ]; then
  echo "Sync done. No restart requested. Use --restart backend|worker|agent-service|admin-frontend|user-web|nginx|all"
  exit 0
fi

if [ "$RESTART_TARGET" = "all" ]; then
  RESTART_TARGET="backend worker agent-service admin-frontend user-web nginx"
fi

sshpass -p "$DEPLOY_PASSWORD" ssh "${SSH_OPTS[@]}" "${DEPLOY_USER}@${DEPLOY_HOST}" bash -s <<REMOTE
set -euo pipefail
cd "$REMOTE_DIR/deploy"
for svc in $RESTART_TARGET; do
  docker compose -f docker-compose.yml -f docker-compose.nginx.yml build "\$svc"
done
docker compose -f docker-compose.yml -f docker-compose.nginx.yml up -d --force-recreate $RESTART_TARGET
docker compose -f docker-compose.yml -f docker-compose.nginx.yml ps
REMOTE

echo "Quick sync + restart ($RESTART_TARGET) finished."
