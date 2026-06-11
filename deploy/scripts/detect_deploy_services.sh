#!/usr/bin/env bash
# Map changed paths to docker compose services (space-separated).
# Usage: detect_deploy_services.sh [file paths...]
#   or:  git diff --name-only HEAD~1 HEAD | bash detect_deploy_services.sh
set -euo pipefail

if [ -n "${DEPLOY_SERVICES:-}" ]; then
  echo "$DEPLOY_SERVICES"
  exit 0
fi

declare -A seen=()
add() {
  local svc="$1"
  if [ -z "${seen[$svc]:-}" ]; then
    seen[$svc]=1
    services+=("$svc")
  fi
}

services=()

if [ "$#" -gt 0 ]; then
  files=("$@")
else
  SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
  mapfile -t files < <(bash "$SCRIPT_DIR/detect_deploy_changes.sh" 2>/dev/null || true)
fi

if [ "${#files[@]}" -eq 0 ] || [ -z "${files[0]:-}" ]; then
  echo "backend worker agent-service admin-frontend user-web nginx"
  exit 0
fi

for f in "${files[@]}"; do
  case "$f" in
    backend/*) add backend ;;
    worker/*) add worker ;;
    agent-service/*) add agent-service ;;
    admin-frontend/*) add admin-frontend; add nginx ;;
    user-web/*) add user-web; add nginx ;;
    engines/banana-slides/*) add banana-slides ;;
    deploy/nginx/*|deploy/docker-compose*|deploy/nginx/*) add nginx ;;
    deploy/*|.github/*|sql/*)
      add backend
      add worker
      add agent-service
      add admin-frontend
      add user-web
      add nginx
      ;;
  esac
done

if [ "${#services[@]}" -eq 0 ]; then
  echo "backend worker agent-service admin-frontend user-web nginx"
else
  echo "${services[*]}"
fi
