#!/usr/bin/env bash
# Promote an internally healthy release after the runner's public smoke gate.
set -euo pipefail

REMOTE_DIR="${REMOTE_DIR:-/root/ai_tool_market}"
EXPECTED_SHA="${EXPECTED_SHA:?EXPECTED_SHA is required}"
PENDING_REVISION="$REMOTE_DIR/.deploy_revision.pending"
PENDING_META="$REMOTE_DIR/.deploy_meta.pending"

if [[ ! "$EXPECTED_SHA" =~ ^[0-9a-fA-F]{40}$ ]]; then
  echo "ERROR: expected release SHA must be a full commit hash" >&2
  exit 1
fi
if [[ ! -s "$PENDING_REVISION" || ! -s "$PENDING_META" ]]; then
  echo "ERROR: pending production release metadata is missing" >&2
  exit 1
fi

IFS= read -r pending_sha < "$PENDING_REVISION"
pending_sha="${pending_sha//$'\r'/}"
head_sha="$(git -C "$REMOTE_DIR" rev-parse HEAD)"
if [[ "$pending_sha" != "$EXPECTED_SHA" || "$head_sha" != "$EXPECTED_SHA" ]]; then
  echo "ERROR: pending/head SHA mismatch (expected=$EXPECTED_SHA pending=$pending_sha head=$head_sha)" >&2
  exit 1
fi

chmod 600 "$PENDING_META" "$PENDING_REVISION"
mv -f "$PENDING_META" "$REMOTE_DIR/.deploy_meta"
mv -f "$PENDING_REVISION" "$REMOTE_DIR/.deploy_revision"
echo "Finalized production release: $EXPECTED_SHA"
