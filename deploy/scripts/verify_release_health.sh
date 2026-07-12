#!/usr/bin/env bash
set -euo pipefail

MAX_ATTEMPTS="${MAX_ATTEMPTS:-18}"
RETRY_SECONDS="${RETRY_SECONDS:-5}"

container_health() {
  local container="$1"
  local status
  status="$(docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' "$container" 2>/dev/null || true)"
  [[ "$status" == "healthy" || "$status" == "running" ]]
}

http_ok() {
  local url="$1"
  curl --fail --silent --show-error --location --max-time 15 --output /dev/null "$url"
}

for attempt in $(seq 1 "$MAX_ATTEMPTS"); do
  echo "Release health attempt $attempt/$MAX_ATTEMPTS"
  if container_health ai-supermarket-nginx \
      && container_health ai-supermarket-user-web \
      && container_health ai-supermarket-backend \
      && http_ok http://127.0.0.1/ \
      && http_ok http://127.0.0.1/api/health \
      && http_ok http://127.0.0.1/admin; then
    echo "Release health verification passed"
    exit 0
  fi
  sleep "$RETRY_SECONDS"
done

echo "ERROR: release health verification failed" >&2
docker ps --format 'table {{.Names}}\t{{.Status}}' >&2 || true
exit 1
