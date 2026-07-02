#!/usr/bin/env bash
# Merge docker compose service names (space-separated), preserving order, no duplicates.
# Usage: merge_deploy_services.sh "backend nginx" worker agent-service
set -euo pipefail

declare -A seen=()
services=()

add() {
  local svc="$1"
  [ -z "$svc" ] && return
  if [ -z "${seen[$svc]:-}" ]; then
    seen[$svc]=1
    services+=("$svc")
  fi
}

for arg in "$@"; do
  for svc in $arg; do
    add "$svc"
  done
done

echo "${services[*]}"
