#!/usr/bin/env bash
set -euo pipefail

MAX_ATTEMPTS="${MAX_ATTEMPTS:-18}"
RETRY_SECONDS="${RETRY_SECONDS:-5}"
PUBLIC_HOST="${PUBLIC_HOST:-wlcloudai.com}"

container_status() {
  docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' "$1" 2>/dev/null || echo missing
}

http_status() {
  local url="$1"
  local code
  shift
  if ! code="$(curl --silent --show-error --location --max-time 15 --output /dev/null \
      --write-out '%{http_code}' "$@" "$url" 2>/dev/null)"; then
    code=000
  fi
  printf '%s' "$code"
}

is_container_ready() {
  [[ "$1" == "healthy" || "$1" == "running" ]]
}

is_http_ready() {
  [[ "$1" =~ ^(2|3)[0-9][0-9]$ ]]
}

worker_media_runtime_ready() {
  if [[ "${MEDIA_VIDEO_PREVIEW_ENABLED:-true}" =~ ^(0|false|no|off)$ ]]; then
    return 0
  fi
  docker exec ai-supermarket-worker python -c \
    'import subprocess; from imageio_ffmpeg import get_ffmpeg_exe; subprocess.run([get_ffmpeg_exe(), "-version"], check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)' \
    >/dev/null 2>&1
}

verify_optional_media_delivery() {
  if [[ -z "${MEDIA_HEALTHCHECK_URL:-}" ]]; then
    echo "Media delivery check skipped: MEDIA_HEALTHCHECK_URL is not configured"
    return 0
  fi
  if ! python3 "$(dirname "$0")/verify_media_delivery.py" "$MEDIA_HEALTHCHECK_URL"; then
    echo "WARNING: media delivery CDN verification failed; release remains available through original media" >&2
  fi
}

dump_failure_diagnostics() {
  echo "--- container status ---" >&2
  docker ps --format 'table {{.Names}}\t{{.Status}}' >&2 || true
  for container in ai-supermarket-backend ai-supermarket-worker ai-supermarket-agent-service ai-supermarket-admin-frontend ai-supermarket-user-web ai-supermarket-nginx; do
    echo "--- $container logs (last 80 lines) ---" >&2
    docker logs --tail 80 "$container" >&2 2>&1 || true
  done
}

for attempt in $(seq 1 "$MAX_ATTEMPTS"); do
  nginx="$(container_status ai-supermarket-nginx)"
  user_web="$(container_status ai-supermarket-user-web)"
  backend="$(container_status ai-supermarket-backend)"
  worker="$(container_status ai-supermarket-worker)"
  agent="$(container_status ai-supermarket-agent-service)"
  public_code="$(http_status "https://$PUBLIC_HOST/" --resolve "$PUBLIC_HOST:443:127.0.0.1")"
  api_code="$(http_status http://127.0.0.1:8080/api/health)"
  admin_code="$(http_status http://127.0.0.1:5174/admin)"
  agent_code="$(http_status http://127.0.0.1:8090/health)"

  printf 'Release health attempt %s/%s: containers nginx=%s user-web=%s backend=%s worker=%s agent=%s; http public=%s api=%s admin=%s agent=%s\n' \
    "$attempt" "$MAX_ATTEMPTS" "$nginx" "$user_web" "$backend" "$worker" "$agent" \
    "$public_code" "$api_code" "$admin_code" "$agent_code"

  if is_container_ready "$nginx" \
      && is_container_ready "$user_web" \
      && is_container_ready "$backend" \
      && is_container_ready "$worker" \
      && is_container_ready "$agent" \
      && is_http_ready "$public_code" \
      && is_http_ready "$api_code" \
      && is_http_ready "$admin_code" \
      && is_http_ready "$agent_code" \
      && worker_media_runtime_ready; then
    verify_optional_media_delivery
    echo "Release health verification passed"
    exit 0
  fi
  sleep "$RETRY_SECONDS"
done

echo "ERROR: release health verification failed" >&2
dump_failure_diagnostics
exit 1
