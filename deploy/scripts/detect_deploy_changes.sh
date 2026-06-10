#!/usr/bin/env bash
# List changed file paths for deploy service detection.
# Uses PR base SHA on pull_request; otherwise previous commit or origin/dev.
set -euo pipefail

if [[ "${GITHUB_EVENT_NAME:-}" == "pull_request" && -n "${GITHUB_BASE_SHA:-}" && -n "${GITHUB_SHA:-}" ]]; then
  git diff --name-only "$GITHUB_BASE_SHA" "$GITHUB_SHA"
  exit 0
fi

if [[ -n "${DEPLOY_DIFF_BASE:-}" && -n "${GITHUB_SHA:-}" ]]; then
  git diff --name-only "$DEPLOY_DIFF_BASE" "$GITHUB_SHA"
  exit 0
fi

if git rev-parse HEAD~1 >/dev/null 2>&1; then
  git diff --name-only HEAD~1 HEAD
  exit 0
fi

if git rev-parse origin/dev >/dev/null 2>&1; then
  git diff --name-only origin/dev HEAD 2>/dev/null || true
  exit 0
fi

exit 0
