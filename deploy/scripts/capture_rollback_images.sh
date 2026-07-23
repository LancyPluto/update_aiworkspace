#!/usr/bin/env bash
# Record the exact application images running before a production release.
set -euo pipefail

REMOTE_DIR="${REMOTE_DIR:-/root/ai_tool_market}"
DEPLOY_SERVICES="${DEPLOY_SERVICES:-${*:-}}"
SNAPSHOT="$REMOTE_DIR/deploy/logs/last-deploy.images.tsv"
PYTHON_BIN="${PYTHON_BIN:-python3}"

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

valid_image_id() {
  [[ "$1" =~ ^sha256:[0-9a-f]{64}$ ]]
}

extract_environment_keys() {
  "$PYTHON_BIN" -c '
import json
import re
import sys

try:
    entries = json.load(sys.stdin)
except (json.JSONDecodeError, TypeError):
    raise SystemExit(1)
if entries is None:
    entries = []
if not isinstance(entries, list):
    raise SystemExit(1)
for entry in entries:
    if not isinstance(entry, str):
        raise SystemExit(1)
    key, separator, _ = entry.partition("=")
    if not separator or re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]*", key) is None:
        raise SystemExit(1)
    sys.stdout.buffer.write(key.encode("ascii") + b"\n")
'
}

verify_sanitized_environment() {
  "$PYTHON_BIN" -c '
import json
import re
import sys

try:
    entries = json.load(sys.stdin)
except (json.JSONDecodeError, TypeError):
    raise SystemExit(1)
if entries is None:
    entries = []
if not isinstance(entries, list):
    raise SystemExit(1)
for entry in entries:
    if not isinstance(entry, str):
        raise SystemExit(1)
    key, separator, value = entry.partition("=")
    if not separator or re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]*", key) is None:
        raise SystemExit(1)
    if key != "PATH" and value:
        raise SystemExit(1)
'
}

discard_candidate_image() {
  docker image rm "$1" >/dev/null 2>&1 || true
}

recover_worker_rollback_image() {
  local container="$1"
  local rollback_ref="$2"
  local env_keys_output env_key commit_image_id inspected_image_id label_value tagged_image_id
  local -a commit_args=(--pause=true)

  if ! env_keys_output="$(
    docker container inspect \
      --format '{{json .Config.Env}}' \
      "$container" \
      | extract_environment_keys
  )"; then
    echo "ERROR: unable to read worker environment keys for rollback recovery" >&2
    return 1
  fi

  while IFS= read -r env_key; do
    [ -n "$env_key" ] || continue
    if [[ ! "$env_key" =~ ^[A-Za-z_][A-Za-z0-9_]*$ ]]; then
      echo "ERROR: invalid worker environment key during rollback recovery" >&2
      return 1
    fi
    if [ "$env_key" != "PATH" ]; then
      commit_args+=(--change "ENV $env_key=")
    fi
  done <<< "$env_keys_output"
  unset env_keys_output

  # Keep the candidate untagged until its ID and secret-stripping metadata pass validation.
  commit_args+=(--change "LABEL com.aiminilab.rollback.local-only=true")
  if ! commit_image_id="$(docker commit "${commit_args[@]}" "$container")"; then
    echo "ERROR: unable to recover rollback image for worker" >&2
    return 1
  fi
  if ! valid_image_id "$commit_image_id"; then
    echo "ERROR: recovered rollback image for worker has an invalid image ID" >&2
    return 1
  fi
  if ! inspected_image_id="$(docker image inspect --format '{{.Id}}' "$commit_image_id")" \
      || ! valid_image_id "$inspected_image_id" \
      || [ "$inspected_image_id" != "$commit_image_id" ]; then
    discard_candidate_image "$commit_image_id"
    echo "ERROR: recovered rollback image for worker has an invalid image ID" >&2
    return 1
  fi

  if ! docker image inspect --format '{{json .Config.Env}}' "$inspected_image_id" \
      | verify_sanitized_environment; then
    discard_candidate_image "$commit_image_id"
    echo "ERROR: recovered worker image retained invalid runtime environment metadata" >&2
    return 1
  fi
  if ! label_value="$(
    docker image inspect \
      --format '{{index .Config.Labels "com.aiminilab.rollback.local-only"}}' \
      "$inspected_image_id"
  )" || [ "$label_value" != "true" ]; then
    discard_candidate_image "$commit_image_id"
    echo "ERROR: recovered worker image is missing the local-only label" >&2
    return 1
  fi

  if ! docker image tag "$inspected_image_id" "$rollback_ref"; then
    discard_candidate_image "$commit_image_id"
    echo "ERROR: unable to retain recovered rollback image for worker" >&2
    return 1
  fi
  if ! tagged_image_id="$(docker image inspect --format '{{.Id}}' "$rollback_ref")" \
      || [ "$tagged_image_id" != "$inspected_image_id" ]; then
    echo "ERROR: retained rollback image for worker changed unexpectedly" >&2
    return 1
  fi

  printf '%s' "$inspected_image_id"
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
  if ! valid_image_id "$image_id" \
      || [[ -z "$image_ref" || "$image_ref" =~ [[:space:]] ]]; then
    echo "ERROR: invalid rollback image metadata for $service" >&2
    exit 1
  fi
  rollback_ref="ai-tool-market-rollback-${service}:previous"

  if docker image inspect "$image_id" >/dev/null 2>&1; then
    docker image tag "$image_id" "$rollback_ref"
    echo "Preserved rollback image for $service: $rollback_ref -> $image_id"
  else
    if [ "$service" != "worker" ]; then
      echo "ERROR: rollback image for $service is missing from the local image store" >&2
      exit 1
    fi
    if ! image_id="$(recover_worker_rollback_image "$container" "$rollback_ref")"; then
      exit 1
    fi
    echo "Recovered rollback image for worker: $rollback_ref -> $image_id"
  fi

  printf '%s\t%s\t%s\n' "$service" "$image_id" "$image_ref" >> "$snapshot_tmp"
done

chmod 600 "$snapshot_tmp"
mv -f "$snapshot_tmp" "$SNAPSHOT"
trap - EXIT
echo "Captured rollback images: $SNAPSHOT"
