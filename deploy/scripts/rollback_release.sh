#!/usr/bin/env bash
set -euo pipefail

REMOTE_DIR="${REMOTE_DIR:-/root/ai_tool_market}"
DEPLOY_SERVICES="${DEPLOY_SERVICES:-${*:-}}"
MANIFEST="$REMOTE_DIR/deploy/logs/last-deploy.json"

if [ ! -f "$MANIFEST" ]; then
  echo "ERROR: deploy manifest is missing; automatic rollback is unavailable" >&2
  exit 1
fi

old_sha="$(python3 - "$MANIFEST" <<'PY'
import json
import sys

with open(sys.argv[1], encoding="utf-8") as source:
    print(json.load(source).get("oldSha", ""))
PY
)"
if [ -z "$old_sha" ]; then
  echo "ERROR: previous revision is missing; automatic rollback is unavailable" >&2
  exit 1
fi

# The repository reset may remove a newly introduced verifier, so keep the running
# release's health script outside the worktree for the duration of rollback.
health_script="$(mktemp)"
cp "$REMOTE_DIR/deploy/scripts/verify_release_health.sh" "$health_script"
trap 'rm -f "$health_script"' EXIT

cd "$REMOTE_DIR"
git cat-file -e "$old_sha^{commit}"
echo "Rolling application code back to $old_sha"
git reset --hard "$old_sha"

cd "$REMOTE_DIR/deploy"
compose_args=(-f docker-compose.yml -f docker-compose.nginx.yml)
if [ -f docker-compose.monitoring.yml ]; then
  compose_args+=(-f docker-compose.monitoring.yml)
fi

app_services=()
monitoring_requested=false
nginx_requested=false
for service in $DEPLOY_SERVICES; do
  case "$service" in
    backend|worker|agent-service|admin-frontend|user-web|banana-slides)
      app_services+=("$service")
      ;;
    prometheus|grafana|loki|alloy|node-exporter|cadvisor|blackbox-exporter)
      monitoring_requested=true
      ;;
    nginx) nginx_requested=true ;;
  esac
done
if [ -z "${DEPLOY_SERVICES//[[:space:]]/}" ]; then
  app_services=(backend worker agent-service admin-frontend user-web)
  nginx_requested=true
fi

available_services_output="$(docker compose "${compose_args[@]}" config --services)"
mapfile -t available_services <<< "$available_services_output"
service_available() {
  local expected="$1"
  local available
  for available in "${available_services[@]}"; do
    if [ "$available" = "$expected" ]; then
      return 0
    fi
  done
  return 1
}

monitoring_stack=(prometheus grafana loki alloy node-exporter cadvisor blackbox-exporter)
available_monitoring_services=()
missing_monitoring_services=()
for service in "${monitoring_stack[@]}"; do
  if service_available "$service"; then
    available_monitoring_services+=("$service")
  else
    missing_monitoring_services+=("$service")
  fi
done

remove_failed_monitoring_container() {
  local service="$1"
  local container
  case "$service" in
    prometheus) container="ai-supermarket-prometheus" ;;
    grafana) container="ai-supermarket-grafana" ;;
    loki) container="ai-supermarket-loki" ;;
    alloy) container="ai-supermarket-alloy" ;;
    node-exporter) container="ai-supermarket-node-exporter" ;;
    cadvisor) container="ai-supermarket-cadvisor" ;;
    blackbox-exporter) container="ai-supermarket-blackbox-exporter" ;;
    *)
      echo "ERROR: unsupported monitoring service cleanup: $service" >&2
      return 1
      ;;
  esac
  if docker container inspect "$container" >/dev/null 2>&1; then
    echo "Removing failed-version monitoring container absent from old revision: $container"
    docker rm -f "$container"
  fi
}

require_monitoring=1
if [ "${#missing_monitoring_services[@]}" -gt 0 ]; then
  require_monitoring=0
  echo "Monitoring compatibility mode: old revision lacks a complete monitoring stack; missing=${missing_monitoring_services[*]}" >&2
  for service in "${missing_monitoring_services[@]}"; do
    remove_failed_monitoring_container "$service"
  done
fi

if [ "${#app_services[@]}" -gt 0 ]; then
  docker compose "${compose_args[@]}" build "${app_services[@]}"
  docker compose "${compose_args[@]}" up -d --force-recreate "${app_services[@]}"
  if [ "$nginx_requested" != true ]; then
    docker compose "${compose_args[@]}" restart nginx
  fi
fi

if [ "$nginx_requested" = true ]; then
  if ! service_available nginx; then
    echo "ERROR: nginx was requested but is unavailable in the old revision compose" >&2
    exit 1
  fi
  docker compose "${compose_args[@]}" up -d --force-recreate nginx
fi

if [ "$monitoring_requested" = true ] && [ "${#available_monitoring_services[@]}" -gt 0 ]; then
  echo "Restoring old revision monitoring stack: ${available_monitoring_services[*]}"
  docker compose "${compose_args[@]}" up -d --force-recreate "${available_monitoring_services[@]}"
fi

if [ "$require_monitoring" -eq 1 ]; then
  REQUIRE_MONITORING=1 bash "$health_script"
else
  REQUIRE_MONITORING=0 bash "$health_script"
fi
rolled_back_services=("${app_services[@]}")
if [ "$nginx_requested" = true ]; then
  rolled_back_services+=(nginx)
fi
if [ "$monitoring_requested" = true ]; then
  rolled_back_services+=("${available_monitoring_services[@]}")
fi
printf '[%s] rollback to %s services=%s\n' "$(date -Iseconds)" "$old_sha" "${rolled_back_services[*]}" \
  >> "$REMOTE_DIR/deploy/logs/deploy-history.log"
echo "$old_sha" > "$REMOTE_DIR/.deploy_revision"
echo "Rollback completed: $old_sha"
