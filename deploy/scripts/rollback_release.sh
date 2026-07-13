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
for service in $DEPLOY_SERVICES; do
  case "$service" in
    backend|worker|agent-service|admin-frontend|user-web|banana-slides)
      app_services+=("$service")
      ;;
  esac
done
if [ "${#app_services[@]}" -eq 0 ]; then
  app_services=(backend worker agent-service admin-frontend user-web)
fi

docker compose "${compose_args[@]}" build "${app_services[@]}"
docker compose "${compose_args[@]}" up -d --force-recreate "${app_services[@]}"
docker compose "${compose_args[@]}" restart nginx
bash "$health_script"
printf '[%s] rollback to %s services=%s\n' "$(date -Iseconds)" "$old_sha" "${app_services[*]}" \
  >> "$REMOTE_DIR/deploy/logs/deploy-history.log"
echo "$old_sha" > "$REMOTE_DIR/.deploy_revision"
echo "Rollback completed: $old_sha"
