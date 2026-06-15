#!/usr/bin/env bash
# Run after merging/pulling dev: apply pending SQL seeds, restart app containers if needed.
#
# Invoked by .husky/post-merge (git pull / git merge). Idempotent via _sql_migration_log.
#
# Opt-out: AI_TOOL_MARKET_SKIP_POST_MERGE=1 git pull
# Manual:  bash deploy/scripts/post_merge_dev_sync.sh
set -uo pipefail

if [ "${AI_TOOL_MARKET_SKIP_POST_MERGE:-}" = "1" ]; then
  echo "[post-merge] skipped (AI_TOOL_MARKET_SKIP_POST_MERGE=1)"
  exit 0
fi

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
COMPOSE_DIR="$ROOT/deploy"

if ! command -v docker >/dev/null 2>&1; then
  echo "[post-merge] docker not found; skip SQL sync" >&2
  exit 0
fi

if ! docker info >/dev/null 2>&1; then
  echo "[post-merge] docker daemon not running; skip SQL sync" >&2
  exit 0
fi

if [ ! -d "$COMPOSE_DIR" ]; then
  echo "[post-merge] deploy directory missing: $COMPOSE_DIR" >&2
  exit 0
fi

echo "[post-merge] applying pending SQL migrations ..."
set +e
migration_output="$(bash "$SCRIPT_DIR/apply_sql_migrations.sh" 2>&1)"
migration_code=$?
set -e
echo "$migration_output"

if [ "$migration_code" -ne 0 ]; then
  echo "[post-merge] SQL migration failed (exit $migration_code); not restarting containers" >&2
  exit "$migration_code"
fi

applied="$(echo "$migration_output" | sed -n 's/^SQL migration summary: applied=\([0-9]*\).*/\1/p' | tail -1)"
applied="${applied:-0}"

if [ "$applied" -eq 0 ]; then
  echo "[post-merge] no new SQL files; docker restart skipped"
  exit 0
fi

echo "[post-merge] applied $applied SQL file(s); restarting app containers ..."
cd "$COMPOSE_DIR"
docker compose restart backend agent-service worker admin-frontend user-web nginx 2>/dev/null \
  || docker compose -f docker-compose.yml -f docker-compose.nginx.yml restart backend agent-service worker admin-frontend user-web nginx

echo "[post-merge] done"
