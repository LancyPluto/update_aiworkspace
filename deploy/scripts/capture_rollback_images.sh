#!/usr/bin/env bash
# Record the exact application images running before a production release.
set -euo pipefail

REMOTE_DIR="${REMOTE_DIR:-/root/ai_tool_market}"
DEPLOY_SERVICES="${DEPLOY_SERVICES:-${*:-}}"
SNAPSHOT="$REMOTE_DIR/deploy/logs/last-deploy.images.tsv"

mkdir -p "$(dirname "$SNAPSHOT")"
snapshot_tmp="$(mktemp "$SNAPSHOT.XXXXXX")"
trap 'rm -f "$snapshot_tmp"' EXIT

container_for_service() {
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

for service in $DEPLOY_SERVICES; do
  container="$(container_for_service "$service" || true)"
  [ -n "$container" ] || continue

  if ! docker container inspect "$container" >/dev/null 2>&1; then
    printf '%s\t-\t-\n' "$service" >> "$snapshot_tmp"
    echo "No previous container for $service; rollback will remove a newly created container"
    continue
  fi

  image_id="$(docker container inspect --format '{{.Image}}' "$container")"
  image_ref="$(docker container inspect --format '{{.Config.Image}}' "$container")"
  if [[ ! "$image_id" =~ ^sha256:[0-9a-f]{64}$ ]] \
      || [[ -z "$image_ref" || "$image_ref" =~ [[:space:]] ]]; then
    echo "ERROR: invalid rollback image metadata for $service" >&2
    exit 1
  fi
  docker image inspect "$image_id" >/dev/null
  rollback_ref="ai-tool-market-rollback-${service}:previous"
  docker image tag "$image_id" "$rollback_ref"
  printf '%s\t%s\t%s\n' "$service" "$image_id" "$image_ref" >> "$snapshot_tmp"
  echo "Preserved rollback image for $service: $rollback_ref -> $image_id"
done

chmod 600 "$snapshot_tmp"
mv -f "$snapshot_tmp" "$SNAPSHOT"
trap - EXIT
echo "Captured rollback images: $SNAPSHOT"
