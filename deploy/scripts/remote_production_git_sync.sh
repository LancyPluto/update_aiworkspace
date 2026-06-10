#!/usr/bin/env bash
# Run on production host: incremental git sync from origin/dev (or a PR head SHA).
# Preserves local secrets/data; writes deploy/logs/* for change tracking.
set -euo pipefail

REMOTE_DIR="${REMOTE_DIR:-/root/ai_tool_market}"
GIT_REPO_URL="${GIT_REPO_URL:?GIT_REPO_URL is required}"
DEPLOY_GIT_REF="${DEPLOY_GIT_REF:?DEPLOY_GIT_REF is required}"
DEPLOY_EVENT="${DEPLOY_EVENT:-push}"
DEPLOY_GIT_BRANCH="${DEPLOY_GIT_BRANCH:-dev}"
DEPLOY_PR_NUMBER="${DEPLOY_PR_NUMBER:-}"
GITHUB_SHA="${GITHUB_SHA:-$DEPLOY_GIT_REF}"
GITHUB_RUN_ID="${GITHUB_RUN_ID:-}"
GITHUB_ACTOR="${GITHUB_ACTOR:-}"
GIT_DEPTH="${GIT_DEPTH:-100}"

PRESERVE_PATHS=(
  ".env"
  "engines/banana-slides/.env"
)

preserve_file() {
  local rel="$1"
  local src="${REMOTE_DIR}/${rel}"
  local bak="/tmp/ai_tool_market_preserve_${rel//\//_}"
  if [[ -f "$src" ]]; then
    cp "$src" "$bak"
    echo "preserved $rel"
  fi
}

restore_file() {
  local rel="$1"
  local src="${REMOTE_DIR}/${rel}"
  local bak="/tmp/ai_tool_market_preserve_${rel//\//_}"
  if [[ -f "$bak" ]]; then
    mkdir -p "$(dirname "$src")"
    cp "$bak" "$src"
    echo "restored $rel"
  fi
}

mkdir -p "$REMOTE_DIR/deploy/logs"

OLD_SHA=""
if [[ -d "$REMOTE_DIR/.git" ]]; then
  OLD_SHA="$(git -C "$REMOTE_DIR" rev-parse HEAD 2>/dev/null || true)"
fi

if [[ ! -d "$REMOTE_DIR/.git" ]]; then
  echo "Bootstrapping git repository at $REMOTE_DIR"
  for rel in "${PRESERVE_PATHS[@]}"; do
    preserve_file "$rel"
  done

  if [[ -d "$REMOTE_DIR" ]] && [[ -n "$(ls -A "$REMOTE_DIR" 2>/dev/null || true)" ]]; then
    STAGING="/tmp/ai_tool_market_git_bootstrap.$$"
    rm -rf "$STAGING"
    git clone --branch "$DEPLOY_GIT_BRANCH" --depth "$GIT_DEPTH" "$GIT_REPO_URL" "$STAGING"
    rsync -a --delete \
      --exclude '.env' \
      --exclude 'data/' \
      --exclude 'engines/banana-slides/.env' \
      --exclude 'engines/banana-slides/backend/instance/' \
      --exclude 'engines/banana-slides/uploads/' \
      --exclude 'deploy/logs/' \
      "$STAGING"/ "$REMOTE_DIR"/
    rm -rf "$STAGING"
  else
    mkdir -p "$REMOTE_DIR"
    git clone --branch "$DEPLOY_GIT_BRANCH" --depth "$GIT_DEPTH" "$GIT_REPO_URL" "$REMOTE_DIR"
  fi

  for rel in "${PRESERVE_PATHS[@]}"; do
    restore_file "$rel"
  done
fi

cd "$REMOTE_DIR"
git config user.email "deploy@wlcloudai.com"
git config user.name "Production Deploy"
git remote set-url origin "$GIT_REPO_URL"

echo "Fetching $DEPLOY_GIT_REF (event=$DEPLOY_EVENT branch=$DEPLOY_GIT_BRANCH)"
git fetch origin "$DEPLOY_GIT_REF" --depth "$GIT_DEPTH"
git fetch origin "$DEPLOY_GIT_BRANCH" --depth "$GIT_DEPTH" 2>/dev/null || true

if [[ "$DEPLOY_EVENT" == "pull_request" && -n "$DEPLOY_PR_NUMBER" ]]; then
  TRACK_BRANCH="deploy/pr-${DEPLOY_PR_NUMBER}"
  git checkout -B "$TRACK_BRANCH" "$DEPLOY_GIT_REF"
else
  git checkout -B "$DEPLOY_GIT_BRANCH" "$DEPLOY_GIT_REF"
fi

NEW_SHA="$(git rev-parse HEAD)"
echo "Synced $OLD_SHA -> $NEW_SHA"

if [[ -n "$OLD_SHA" && "$OLD_SHA" != "$NEW_SHA" ]]; then
  git diff --stat "$OLD_SHA" "$NEW_SHA" > deploy/logs/last-deploy.diffstat || true
  git log --oneline "$OLD_SHA".."$NEW_SHA" > deploy/logs/last-deploy.commits.txt || true
  git diff --name-only "$OLD_SHA" "$NEW_SHA" > deploy/logs/last-deploy.files.txt || true
else
  : > deploy/logs/last-deploy.diffstat
  : > deploy/logs/last-deploy.commits.txt
  : > deploy/logs/last-deploy.files.txt
fi

DEPLOYED_AT="$(date -Iseconds 2>/dev/null || date '+%Y-%m-%dT%H:%M:%S%z')"
cat > deploy/logs/last-deploy.json <<EOF
{
  "deployedAt": "$DEPLOYED_AT",
  "oldSha": "$OLD_SHA",
  "newSha": "$NEW_SHA",
  "event": "$DEPLOY_EVENT",
  "branch": "$DEPLOY_GIT_BRANCH",
  "prNumber": "$DEPLOY_PR_NUMBER",
  "githubSha": "$GITHUB_SHA",
  "runId": "$GITHUB_RUN_ID",
  "actor": "$GITHUB_ACTOR"
}
EOF

{
  echo "[$DEPLOYED_AT] event=$DEPLOY_EVENT ref=$DEPLOY_GIT_REF $OLD_SHA -> $NEW_SHA actor=$GITHUB_ACTOR run=$GITHUB_RUN_ID"
  if [[ -s deploy/logs/last-deploy.files.txt ]]; then
    echo "changed files:"
    sed 's/^/  /' deploy/logs/last-deploy.files.txt
  else
    echo "changed files: (none or first deploy)"
  fi
} >> deploy/logs/deploy-history.log

echo "$NEW_SHA" > .deploy_revision
printf '%s\n' "$DEPLOY_EVENT" "$DEPLOY_GIT_BRANCH" "$DEPLOY_PR_NUMBER" > .deploy_meta

echo "Git sync complete: $NEW_SHA"
echo "--- changed files (stat) ---"
head -50 deploy/logs/last-deploy.diffstat || true
