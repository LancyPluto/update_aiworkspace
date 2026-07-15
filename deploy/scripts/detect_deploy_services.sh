#!/usr/bin/env bash
# Map changed paths to docker compose services (space-separated).
# Usage: detect_deploy_services.sh [file paths...]
#   or:  git diff --name-only HEAD~1 HEAD | bash detect_deploy_services.sh
set -euo pipefail

SCRIPT_PATH="${BASH_SOURCE[0]:-$0}"
SCRIPT_DIR="${SCRIPT_PATH%/*}"
if [ "$SCRIPT_DIR" = "$SCRIPT_PATH" ]; then
  SCRIPT_DIR="."
fi

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

add_secret_bearing_services() {
  add backend
  add worker
  add agent-service
}

add_monitoring_services() {
  add prometheus
  add grafana
  add loki
  add alloy
  add node-exporter
  add cadvisor
  add blackbox-exporter
}

services=()

if [ "$#" -gt 0 ]; then
  files=("$@")
else
  mapfile -t files < <(bash "$SCRIPT_DIR/detect_deploy_changes.sh" 2>/dev/null || true)
fi

if [ "${#files[@]}" -eq 0 ] || [ -z "${files[0]:-}" ]; then
  echo "backend worker agent-service admin-frontend user-web nginx prometheus grafana loki alloy node-exporter cadvisor blackbox-exporter"
  exit 0
fi

for f in "${files[@]}"; do
  case "$f" in
    .env|deploy/.env)
      add_secret_bearing_services
      ;;
    backend/*) add backend ;;
    worker/*) add worker ;;
    agent-service/*) add agent-service ;;
    admin-frontend/*) add admin-frontend; add nginx ;;
    user-web/*) add user-web; add nginx ;;
    engines/banana-slides/*) add banana-slides ;;
    deploy/docker-compose.monitoring.yml|deploy/monitoring/*)
      add_monitoring_services
      add nginx
      ;;
    deploy/docker-compose*|deploy/docker-compose.*)
      add_secret_bearing_services
      add_monitoring_services
      add nginx
      ;;
    deploy/nginx/*) add nginx ;;
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
  echo "backend worker agent-service admin-frontend user-web nginx prometheus grafana loki alloy node-exporter cadvisor blackbox-exporter"
else
  echo "${services[*]}"
fi
