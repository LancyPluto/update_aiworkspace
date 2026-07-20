#!/usr/bin/env bash
# Merge the runner's service guess with the authoritative production diff.
# Usage: resolve_deploy_services.sh "backend" path/to/file [...]
set -euo pipefail

SCRIPT_PATH="${BASH_SOURCE[0]:-$0}"
SCRIPT_DIR="${SCRIPT_PATH%/*}"
if [ "$SCRIPT_DIR" = "$SCRIPT_PATH" ]; then
  SCRIPT_DIR="."
fi

initial_services="${1:-}"
if [ "$#" -gt 0 ]; then
  shift
fi

if [ "$#" -eq 0 ]; then
  echo "$initial_services"
  exit 0
fi

actual_services="$(
  DEPLOY_SERVICES= "$BASH" "$SCRIPT_DIR/detect_deploy_services.sh" "$@"
)"

"$BASH" "$SCRIPT_DIR/merge_deploy_services.sh" "$initial_services" "$actual_services"
