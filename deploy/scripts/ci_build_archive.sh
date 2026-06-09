#!/usr/bin/env bash
# Build deployment tarball (same exclusions as sync_to_server.py).
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
OUTPUT="${1:-/tmp/ai_tool_market_ci.tar.gz}"

cd "$ROOT"

tar -czf "$OUTPUT" \
  --exclude='.git' \
  --exclude='node_modules' \
  --exclude='target' \
  --exclude='__pycache__' \
  --exclude='.next' \
  --exclude='dist' \
  --exclude='.venv' \
  --exclude='venv' \
  --exclude='.pytest_cache' \
  --exclude='.mypy_cache' \
  --exclude='.cursor' \
  --exclude='.claude' \
  --exclude='.idea' \
  --exclude='*.pyc' \
  --exclude='*.pyo' \
  --exclude='*.class' \
  --exclude='*.log' \
  --exclude='*.tar.gz' \
  --exclude='./.env' \
  --exclude='./.env.local' \
  --exclude='./data' \
  .

ls -lh "$OUTPUT"
echo "Archive ready: $OUTPUT"
