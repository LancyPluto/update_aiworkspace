#!/usr/bin/env bash
set -euo pipefail

PULL_ATTEMPTS="${COMPOSE_IMAGE_PULL_ATTEMPTS:-3}"
PULL_TIMEOUT_SECONDS="${COMPOSE_IMAGE_PULL_TIMEOUT_SECONDS:-90}"
PULL_BACKOFF_SECONDS="${COMPOSE_IMAGE_PULL_BACKOFF_SECONDS:-5}"

require_positive_integer() {
  local name="$1"
  local value="$2"
  if [[ ! "$value" =~ ^[1-9][0-9]*$ ]]; then
    echo "ERROR: $name must be a positive integer, got: $value" >&2
    exit 2
  fi
}

require_nonnegative_integer() {
  local name="$1"
  local value="$2"
  if [[ ! "$value" =~ ^[0-9]+$ ]]; then
    echo "ERROR: $name must be a non-negative integer, got: $value" >&2
    exit 2
  fi
}

require_positive_integer COMPOSE_IMAGE_PULL_ATTEMPTS "$PULL_ATTEMPTS"
require_positive_integer COMPOSE_IMAGE_PULL_TIMEOUT_SECONDS "$PULL_TIMEOUT_SECONDS"
require_nonnegative_integer COMPOSE_IMAGE_PULL_BACKOFF_SECONDS "$PULL_BACKOFF_SECONDS"

if ! command -v timeout >/dev/null 2>&1; then
  echo "ERROR: timeout is required for bounded Compose image pulls" >&2
  exit 2
fi

attempt=1
last_status=1
while [ "$attempt" -le "$PULL_ATTEMPTS" ]; do
  echo "Compose external image pre-pull attempt $attempt/$PULL_ATTEMPTS (timeout ${PULL_TIMEOUT_SECONDS}s) ..."
  if timeout -k 10s "${PULL_TIMEOUT_SECONDS}s" \
      docker compose "$@" pull --policy missing --ignore-buildable; then
    echo "Compose external image pre-pull attempt $attempt/$PULL_ATTEMPTS succeeded"
    exit 0
  else
    last_status=$?
  fi

  if [ "$attempt" -lt "$PULL_ATTEMPTS" ]; then
    delay=$((PULL_BACKOFF_SECONDS * attempt))
    echo "::warning::Compose external image pre-pull attempt $attempt/$PULL_ATTEMPTS failed with status $last_status; retrying in ${delay}s." >&2
    sleep "$delay"
  fi
  attempt=$((attempt + 1))
done

echo "::error::Compose external image pre-pull failed after $PULL_ATTEMPTS attempts; aborting before database migration or container replacement." >&2
exit "$last_status"
