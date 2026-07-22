#!/usr/bin/env bash
set -euo pipefail

REMOTE_DIR="${REMOTE_DIR:-/root/ai_tool_market}"
DEPLOY_SERVICES="${DEPLOY_SERVICES:-${*:-}}"
MANIFEST="$REMOTE_DIR/deploy/logs/last-deploy.json"
IMAGE_SNAPSHOT="$REMOTE_DIR/deploy/logs/last-deploy.images.tsv"
# Keep rollback independent of the checked-out revision's registry default.
# A private ACR mirror can override this through the deployment environment.
CADVISOR_IMAGE="${CADVISOR_IMAGE:-m.daocloud.io/ghcr.io/google/cadvisor:v0.60.5}"
export CADVISOR_IMAGE

if [ ! -f "$MANIFEST" ]; then
  echo "ERROR: deploy manifest is missing; automatic rollback is unavailable" >&2
  exit 1
fi
if [ ! -f "$IMAGE_SNAPSHOT" ]; then
  echo "ERROR: rollback image snapshot is missing; refusing to rebuild old source" >&2
  exit 1
fi

declare -A rollback_image_ids=()
declare -A rollback_image_refs=()
while IFS=$'\t' read -r service image_id image_ref; do
  case "$service" in
    backend|worker|agent-service|admin-frontend|user-web|banana-slides) ;;
    *)
      echo "ERROR: invalid service in rollback image snapshot: $service" >&2
      exit 1
      ;;
  esac
  if [ -n "${rollback_image_ids[$service]+x}" ]; then
    echo "ERROR: duplicate service in rollback image snapshot: $service" >&2
    exit 1
  fi
  if [ "$image_id" != "-" ]; then
    if [[ ! "$image_id" =~ ^sha256:[0-9a-f]{64}$ ]] \
        || [[ -z "$image_ref" || "$image_ref" = "-" || "$image_ref" =~ [[:space:]] ]]; then
      echo "ERROR: invalid image metadata in rollback snapshot for $service" >&2
      exit 1
    fi
  elif [ "$image_ref" != "-" ]; then
    echo "ERROR: invalid absent-image marker in rollback snapshot for $service" >&2
    exit 1
  fi
  rollback_image_ids["$service"]="$image_id"
  rollback_image_refs["$service"]="$image_ref"
done < "$IMAGE_SNAPSHOT"

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

python3 - <<'PY'
import os
from pathlib import Path

root_path = Path("/root/ai_tool_market/.env")
deploy_path = Path("/root/ai_tool_market/deploy/.env")


def read_env(path: Path) -> dict[str, str]:
    values: dict[str, str] = {}
    if not path.exists():
        return values
    for line in path.read_text(encoding="utf-8", errors="replace").splitlines():
        if "=" not in line or line.lstrip().startswith("#"):
            continue
        key, value = line.split("=", 1)
        values[key.strip()] = value.strip()
    return values


root = read_env(root_path)
deploy = read_env(deploy_path)
merged = {**deploy, **root}
merged["CADVISOR_IMAGE"] = os.environ["CADVISOR_IMAGE"]

critical_keys = (
    "JWT_SECRET",
    "INTERNAL_API_TOKEN",
    "GRAFANA_ADMIN_PASSWORD",
    "CADVISOR_IMAGE",
)
for key in critical_keys:
    if key == "CADVISOR_IMAGE":
        continue
    if root.get(key):
        merged[key] = root[key]

invalid = {
    "JWT_SECRET": {"", "local-dev-secret", "replace-with-a-strong-jwt-secret"},
    "INTERNAL_API_TOKEN": {"", "local-internal-token", "replace-with-internal-token"},
}
minimum_length = {"JWT_SECRET": 32, "INTERNAL_API_TOKEN": 32}
for key, defaults in invalid.items():
    value = merged.get(key, "").strip().strip('"').strip("'")
    if value in defaults or len(value) < minimum_length[key]:
        raise SystemExit(f"rollback env restore failed: {key} is missing, default, or too short")

lines = deploy_path.read_text(encoding="utf-8", errors="replace").splitlines() if deploy_path.exists() else []
keys = set(merged)
out = [line for line in lines if line.split("=", 1)[0].strip() not in keys]
out.extend(f"{key}={value}" for key, value in merged.items())
deploy_path.parent.mkdir(parents=True, exist_ok=True)
deploy_path.write_text("\n".join(out) + "\n", encoding="utf-8")
print("restored production env for rollback")
PY

cd "$REMOTE_DIR/deploy"
compose_args=(--env-file ../.env -f docker-compose.yml -f docker-compose.nginx.yml)
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
    mihomo|mihomo-init)
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

application_container_for_service() {
  case "$1" in
    backend) printf '%s' ai-supermarket-backend ;;
    worker) printf '%s' ai-supermarket-worker ;;
    agent-service) printf '%s' ai-supermarket-agent-service ;;
    admin-frontend) printf '%s' ai-supermarket-admin-frontend ;;
    user-web) printf '%s' ai-supermarket-user-web ;;
    banana-slides) printf '%s' ai-supermarket-banana-slides ;;
    *) return 1 ;;
  esac
}

restorable_app_services=()
for service in "${app_services[@]}"; do
  if [ -z "${rollback_image_ids[$service]+x}" ]; then
    echo "ERROR: rollback image snapshot has no record for $service" >&2
    exit 1
  fi

  image_id="${rollback_image_ids[$service]}"
  image_ref="${rollback_image_refs[$service]}"
  if [ "$image_id" = "-" ]; then
    container="$(application_container_for_service "$service")"
    if docker container inspect "$container" >/dev/null 2>&1; then
      echo "Removing $service container created by the failed release"
      docker rm -f "$container"
    fi
    continue
  fi
  if ! service_available "$service"; then
    echo "ERROR: $service is unavailable in the previous revision compose" >&2
    exit 1
  fi
  rollback_ref="ai-tool-market-rollback-${service}:previous"
  preserved_image_id="$(docker image inspect --format '{{.Id}}' "$rollback_ref")"
  if [ "$preserved_image_id" != "$image_id" ]; then
    echo "ERROR: preserved rollback image changed for $service" >&2
    exit 1
  fi
  docker image tag "$rollback_ref" "$image_ref"
  restorable_app_services+=("$service")
  echo "Restored image tag for $service: $image_ref -> $preserved_image_id"
done

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

monitoring_loopback_endpoint_available() {
  local container="$1"
  local container_port="$2"
  local binding
  while IFS= read -r binding; do
    case "$binding" in
      127.0.0.1:*|\[::1\]:*) return 0 ;;
    esac
  done < <(docker port "$container" "${container_port}/tcp" 2>/dev/null || true)
  return 1
}

monitoring_health_contract_supported() {
  monitoring_loopback_endpoint_available ai-supermarket-grafana 3000 \
    && monitoring_loopback_endpoint_available ai-supermarket-prometheus 9090 \
    && monitoring_loopback_endpoint_available ai-supermarket-loki 3100 \
    && monitoring_loopback_endpoint_available ai-supermarket-alloy 12345
}

require_monitoring=1
if [ "${#missing_monitoring_services[@]}" -gt 0 ]; then
  require_monitoring=0
  echo "Monitoring compatibility mode: old revision lacks a complete monitoring stack; missing=${missing_monitoring_services[*]}" >&2
  for service in "${missing_monitoring_services[@]}"; do
    remove_failed_monitoring_container "$service"
  done
fi

if [ "${#restorable_app_services[@]}" -gt 0 ]; then
  docker compose "${compose_args[@]}" up -d --force-recreate --no-deps --no-build --pull never "${restorable_app_services[@]}"
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

if [ "$require_monitoring" -eq 1 ]; then
  echo "Restoring complete old revision monitoring stack: ${available_monitoring_services[*]}"
  docker compose "${compose_args[@]}" up -d --force-recreate "${available_monitoring_services[@]}"
elif [ "$monitoring_requested" = true ] && [ "${#available_monitoring_services[@]}" -gt 0 ]; then
  echo "Restoring old revision monitoring stack: ${available_monitoring_services[*]}"
  docker compose "${compose_args[@]}" up -d --force-recreate "${available_monitoring_services[@]}"
fi

if [ "$require_monitoring" -eq 1 ] && ! monitoring_health_contract_supported; then
  require_monitoring=0
  echo "Monitoring compatibility mode: old revision lacks loopback monitoring endpoints required by the current verifier" >&2
fi

if [ "$require_monitoring" -eq 1 ]; then
  REQUIRE_MONITORING=1 bash "$health_script"
else
  REQUIRE_MONITORING=0 bash "$health_script"
fi
if [[ " ${restorable_app_services[*]} " == *" user-web "* ]]; then
  docker exec ai-supermarket-user-web sh -c "printf '%s\\n' '{\"gitSha\":\"'$old_sha'\",\"builtAt\":\"'\"$(date -Iseconds)\"'\"}' > /dist-out/build-info.json"
fi
rolled_back_services=("${restorable_app_services[@]}")
if [ "$nginx_requested" = true ]; then
  rolled_back_services+=(nginx)
fi
if [ "$monitoring_requested" = true ] || [ "$require_monitoring" -eq 1 ]; then
  rolled_back_services+=("${available_monitoring_services[@]}")
fi
printf '[%s] rollback to %s services=%s\n' "$(date -Iseconds)" "$old_sha" "${rolled_back_services[*]}" \
  >> "$REMOTE_DIR/deploy/logs/deploy-history.log"
meta_tmp="$(mktemp "$REMOTE_DIR/.deploy_meta.XXXXXX")"
revision_tmp="$(mktemp "$REMOTE_DIR/.deploy_revision.XXXXXX")"
printf 'rollback\n\n\n' > "$meta_tmp"
printf '%s\n' "$old_sha" > "$revision_tmp"
chmod 600 "$meta_tmp" "$revision_tmp"
mv -f "$meta_tmp" "$REMOTE_DIR/.deploy_meta"
mv -f "$revision_tmp" "$REMOTE_DIR/.deploy_revision"
rm -f "$REMOTE_DIR/.deploy_meta.pending" "$REMOTE_DIR/.deploy_revision.pending"
echo "Rollback completed: $old_sha"
