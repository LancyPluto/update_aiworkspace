#!/usr/bin/env bash
# Record the exact application images running before a production release.
set -euo pipefail

REMOTE_DIR="${REMOTE_DIR:-/root/ai_tool_market}"
DEPLOY_SERVICES="${DEPLOY_SERVICES:-${*:-}}"
SNAPSHOT="$REMOTE_DIR/deploy/logs/last-deploy.images.tsv"
PYTHON_BIN="${PYTHON_BIN:-python3}"

mkdir -p "$(dirname "$SNAPSHOT")"
snapshot_tmp="$(mktemp "$SNAPSHOT.XXXXXX")"
declare -a staged_services=()
declare -A candidate_refs=()
declare -A candidate_ids=()
declare -A backup_refs=()
declare -A backup_ids=()
publish_started=false
snapshot_published=false

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

parse_environment_metadata() {
  "$PYTHON_BIN" -c '
import json
import re
import sys

mode = sys.argv[1]
try:
    entries = json.load(sys.stdin)
except (json.JSONDecodeError, TypeError):
    raise SystemExit(1)
if entries is None:
    entries = []
if not isinstance(entries, list):
    raise SystemExit(1)

parsed = []
for entry in entries:
    if not isinstance(entry, str):
        raise SystemExit(1)
    key, separator, value = entry.partition("=")
    if not separator or re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]*", key) is None:
        raise SystemExit(1)
    parsed.append((key, value))

if mode == "path":
    values = [value for key, value in parsed if key == "PATH"]
    if len(values) != 1 or not values[0] or any(char in values[0] for char in "\x00\r\n"):
        raise SystemExit(1)
    sys.stdout.buffer.write(values[0].encode("utf-8"))
elif mode == "verify":
    paths = 0
    for key, value in parsed:
        if key == "PATH":
            paths += 1
            if not value or any(char in value for char in "\x00\r\n"):
                raise SystemExit(1)
        elif value:
            raise SystemExit(1)
    if paths != 1:
        raise SystemExit(1)
else:
    raise SystemExit(1)
' "$1"
}

discard_candidate_image() {
  docker image rm "$1" >/dev/null 2>&1 || true
}

restore_stable_tags() {
  local service stable_ref backup_ref restored_id
  local restore_failed=false
  for service in "${staged_services[@]}"; do
    stable_ref="ai-tool-market-rollback-${service}:previous"
    backup_ref="${backup_refs[$service]:-}"
    if [ -n "$backup_ref" ]; then
      if ! docker image tag "$backup_ref" "$stable_ref" >/dev/null 2>&1; then
        echo "ERROR: unable to restore rollback image for $service from $backup_ref" >&2
        restore_failed=true
        continue
      fi
      if ! restored_id="$(docker image inspect --format '{{.Id}}' "$stable_ref" 2>/dev/null)" \
          || [ "$restored_id" != "${backup_ids[$service]:-}" ]; then
        echo "ERROR: restored rollback image for $service failed ID verification" >&2
        restore_failed=true
      fi
    else
      if docker image inspect "$stable_ref" >/dev/null 2>&1 \
          && ! docker image rm "$stable_ref" >/dev/null 2>&1; then
        echo "ERROR: unable to remove newly published rollback image for $service" >&2
        restore_failed=true
        continue
      fi
      if docker image inspect "$stable_ref" >/dev/null 2>&1; then
        echo "ERROR: newly published rollback image for $service still exists" >&2
        restore_failed=true
      fi
    fi
  done
  [ "$restore_failed" = false ]
}

cleanup_capture() {
  local status="$?"
  local service candidate_ref backup_ref
  local cleanup_backups=true
  trap - EXIT

  if [ "$publish_started" = true ] && [ "$snapshot_published" != true ]; then
    if [ ! -e "$snapshot_tmp" ]; then
      snapshot_published=true
    elif ! restore_stable_tags; then
      cleanup_backups=false
      status=1
    fi
  fi
  for service in "${staged_services[@]}"; do
    candidate_ref="${candidate_refs[$service]:-}"
    backup_ref="${backup_refs[$service]:-}"
    [ -z "$candidate_ref" ] || docker image rm "$candidate_ref" >/dev/null 2>&1 || true
    if [ -n "$backup_ref" ]; then
      if [ "$cleanup_backups" = true ]; then
        docker image rm "$backup_ref" >/dev/null 2>&1 || true
      else
        echo "ERROR: preserving rollback backup after failed restore: $backup_ref" >&2
      fi
    fi
  done
  rm -f "$snapshot_tmp"
  exit "$status"
}
trap cleanup_capture EXIT

worker_config_matches_contract() {
  local object_type="$1"
  local object="$2"
  local cmd entrypoint workdir user healthcheck

  cmd="$(docker "$object_type" inspect --format '{{json .Config.Cmd}}' "$object")" \
    || return 1
  entrypoint="$(docker "$object_type" inspect --format '{{json .Config.Entrypoint}}' "$object")" \
    || return 1
  workdir="$(docker "$object_type" inspect --format '{{.Config.WorkingDir}}' "$object")" \
    || return 1
  user="$(docker "$object_type" inspect --format '{{.Config.User}}' "$object")" \
    || return 1
  healthcheck="$(docker "$object_type" inspect --format '{{json .Config.Healthcheck}}' "$object")" \
    || return 1

  [ "$cmd" = '["python","main.py"]' ] \
    && { [ "$entrypoint" = "null" ] || [ "$entrypoint" = "[]" ]; } \
    && [ "$workdir" = "/app" ] \
    && { [ -z "$user" ] || [ "$user" = "0" ] || [ "$user" = "root" ]; } \
    && [ "$healthcheck" = "null" ]
}

import_worker_rootfs() {
  local container="$1"
  local candidate_ref="$2"
  local path_value imported_image_id
  local -a import_args=(--message "ai-tool-market local-only worker rollback")

  if ! path_value="$(
    docker container inspect --format '{{json .Config.Env}}' "$container" \
      | parse_environment_metadata path
  )"; then
    echo "ERROR: unable to read worker PATH for rootfs recovery" >&2
    return 1
  fi

  import_args+=(--change "ENV PATH=$path_value")
  import_args+=(--change "WORKDIR /app")
  import_args+=(--change 'CMD ["python","main.py"]')
  import_args+=(--change "LABEL com.aiminilab.rollback.local-only=true")

  if imported_image_id="$(
    docker export "$container" \
      | docker import "${import_args[@]}" - "$candidate_ref"
  )"; then
    :
  else
    if valid_image_id "${imported_image_id:-}"; then
      discard_candidate_image "$imported_image_id"
    fi
    echo "ERROR: unable to import the running worker rootfs" >&2
    return 1
  fi
  if ! valid_image_id "$imported_image_id"; then
    echo "ERROR: imported worker rootfs returned an invalid image ID" >&2
    return 1
  fi

  printf '%s' "$imported_image_id"
}

smoke_worker_candidate() {
  local image_id="$1"
  local smoke_name="ai-tool-market-rollback-worker-smoke-$$"
  local status=0

  docker rm -f "$smoke_name" >/dev/null 2>&1 || true
  timeout --kill-after=5s 30s docker run --rm --name "$smoke_name" \
    --network none --read-only \
    --cpus 1 --memory 512m --pids-limit 64 \
    --env PYTHONDONTWRITEBYTECODE=1 \
    --entrypoint python "$image_id" -c \
    'from pathlib import Path; import dotenv, httpx, openai, oss2, pika, prometheus_client, redis, requests; assert Path("/app/main.py").is_file()' \
    || status="$?"
  docker rm -f "$smoke_name" >/dev/null 2>&1 || true
  return "$status"
}

recover_worker_rollback_image() {
  local container="$1"
  local candidate_ref="$2"
  local candidate_image_id inspected_image_id image_comment label_value

  if ! worker_config_matches_contract container "$container"; then
    echo "ERROR: running worker configuration is incompatible with rollback recovery" >&2
    return 1
  fi
  if ! candidate_image_id="$(import_worker_rootfs "$container" "$candidate_ref")"; then
    echo "ERROR: unable to recover rollback image for worker" >&2
    return 1
  fi

  if ! valid_image_id "$candidate_image_id"; then
    echo "ERROR: recovered rollback image for worker has an invalid image ID" >&2
    return 1
  fi
  if ! inspected_image_id="$(docker image inspect --format '{{.Id}}' "$candidate_image_id")" \
      || ! valid_image_id "$inspected_image_id" \
      || [ "$inspected_image_id" != "$candidate_image_id" ]; then
    discard_candidate_image "$candidate_image_id"
    echo "ERROR: recovered rollback image for worker has an invalid image ID" >&2
    return 1
  fi
  if ! worker_config_matches_contract image "$inspected_image_id"; then
    discard_candidate_image "$candidate_image_id"
    echo "ERROR: recovered worker image has incompatible runtime metadata" >&2
    return 1
  fi
  if ! docker image inspect --format '{{json .Config.Env}}' "$inspected_image_id" \
      | parse_environment_metadata verify; then
    discard_candidate_image "$candidate_image_id"
    echo "ERROR: recovered worker image retained invalid runtime environment metadata" >&2
    return 1
  fi
  if ! label_value="$(
    docker image inspect \
      --format '{{index .Config.Labels "com.aiminilab.rollback.local-only"}}' \
      "$inspected_image_id"
  )" || [ "$label_value" != "true" ]; then
    discard_candidate_image "$candidate_image_id"
    echo "ERROR: recovered worker image is missing the local-only label" >&2
    return 1
  fi

  if ! image_comment="$(docker image inspect --format '{{.Comment}}' "$inspected_image_id")" \
      || [ "$image_comment" != "ai-tool-market local-only worker rollback" ]; then
    discard_candidate_image "$candidate_image_id"
    echo "ERROR: recovered worker image is missing the local-only marker" >&2
    return 1
  fi
  if ! smoke_worker_candidate "$inspected_image_id"; then
    discard_candidate_image "$candidate_image_id"
    echo "ERROR: recovered worker image failed the isolated runtime smoke check" >&2
    return 1
  fi

  echo "Recovered worker rollback candidate using flattened-rootfs" >&2
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
  candidate_ref="ai-tool-market-rollback-${service}:candidate-$$"
  staged_services+=("$service")
  candidate_refs["$service"]="$candidate_ref"
  if docker image inspect "$image_id" >/dev/null 2>&1; then
    echo "Validated rollback image for $service: $image_id"
  else
    if [ "$service" != "worker" ]; then
      echo "ERROR: rollback image for $service is missing from the local image store" >&2
      exit 1
    fi
    if ! image_id="$(recover_worker_rollback_image "$container" "$candidate_ref")"; then
      exit 1
    fi
    echo "Recovered rollback image for worker: $image_id"
  fi

  docker image tag "$image_id" "$candidate_ref"
  candidate_ids["$service"]="$image_id"
  printf '%s\t%s\t%s\n' "$service" "$image_id" "$image_ref" >> "$snapshot_tmp"
done

chmod 600 "$snapshot_tmp"
for service in "${staged_services[@]}"; do
  stable_ref="ai-tool-market-rollback-${service}:previous"
  backup_ref="ai-tool-market-rollback-${service}:backup-$$"
  if stable_inspect_output="$(docker image inspect --format '{{.Id}}' "$stable_ref" 2>&1)"; then
    stable_id="$stable_inspect_output"
    if ! valid_image_id "$stable_id"; then
      echo "ERROR: existing rollback image for $service has an invalid image ID" >&2
      exit 1
    fi
    backup_refs["$service"]="$backup_ref"
    backup_ids["$service"]="$stable_id"
    docker image tag "$stable_ref" "$backup_ref"
    backup_id="$(docker image inspect --format '{{.Id}}' "$backup_ref")"
    if [ "$backup_id" != "$stable_id" ]; then
      echo "ERROR: rollback backup for $service failed ID verification" >&2
      exit 1
    fi
  elif [[ "$stable_inspect_output" == "Error response from daemon: No such image:"* ]] \
      || [[ "$stable_inspect_output" == "No such image:"* ]]; then
    backup_refs["$service"]=""
    backup_ids["$service"]=""
  elif [[ "$stable_inspect_output" =~ content[[:space:]]digest[[:space:]]sha256:[0-9a-f]{64}.*not[[:space:]]found ]]; then
    echo "WARNING: existing rollback image for $service has missing content and will be replaced" >&2
    backup_refs["$service"]=""
    backup_ids["$service"]=""
  else
    echo "ERROR: unable to inspect existing rollback image for $service" >&2
    [ -z "$stable_inspect_output" ] || printf '%s\n' "$stable_inspect_output" >&2
    exit 1
  fi
done

publish_started=true
for service in "${staged_services[@]}"; do
  stable_ref="ai-tool-market-rollback-${service}:previous"
  if ! docker image tag "${candidate_refs[$service]}" "$stable_ref"; then
    echo "ERROR: unable to publish rollback image for $service" >&2
    exit 1
  fi
  published_id="$(docker image inspect --format '{{.Id}}' "$stable_ref")"
  if [ "$published_id" != "${candidate_ids[$service]}" ]; then
    echo "ERROR: retained rollback image for $service changed unexpectedly" >&2
    exit 1
  fi
done

mv -f "$snapshot_tmp" "$SNAPSHOT"
snapshot_published=true
echo "Captured rollback images: $SNAPSHOT"
