#!/usr/bin/env bash
# One-time bootstrap: turn an existing /root/ai_tool_market tree into a git checkout of origin/dev.
# Usage (from dev machine with repo checkout):
#   export DEPLOY_HOST=8.134.93.203 DEPLOY_USER=root DEPLOY_PASSWORD='...'
#   bash deploy/scripts/bootstrap_production_git.sh
set -euo pipefail

: "${DEPLOY_HOST:?DEPLOY_HOST is required}"
: "${DEPLOY_USER:?DEPLOY_USER is required}"
: "${DEPLOY_PASSWORD:?DEPLOY_PASSWORD is required}"

REMOTE_DIR="${REMOTE_DIR:-/root/ai_tool_market}"
GIT_BRANCH="${DEPLOY_GIT_BRANCH:-dev}"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
BUNDLE="/tmp/ai-tool-market-bootstrap-$$.bundle"
SSH_OPTS=(-o StrictHostKeyChecking=accept-new -o UserKnownHostsFile=/dev/null)

ssh_cmd() {
  sshpass -p "$DEPLOY_PASSWORD" ssh "${SSH_OPTS[@]}" "${DEPLOY_USER}@${DEPLOY_HOST}" "$@"
}

scp_cmd() {
  sshpass -p "$DEPLOY_PASSWORD" scp "${SSH_OPTS[@]}" "$@"
}

echo "Creating git bundle for origin/$GIT_BRANCH ..."
cd "$ROOT"
git fetch origin "$GIT_BRANCH" --quiet 2>/dev/null || true
git bundle create "$BUNDLE" "origin/$GIT_BRANCH"

echo "Uploading bundle to production ..."
scp_cmd "$BUNDLE" "${DEPLOY_USER}@${DEPLOY_HOST}:/tmp/ai-tool-market-bootstrap.bundle"
rm -f "$BUNDLE"

echo "Bootstrapping git on production ..."
ssh_cmd "bash -s" <<'REMOTE'
set -euo pipefail
REMOTE_DIR="/root/ai_tool_market"
GIT_BRANCH="dev"
BUNDLE="/tmp/ai-tool-market-bootstrap.bundle"

if [[ -d "$REMOTE_DIR/.git" ]]; then
  echo "Git already initialized at $REMOTE_DIR"
  exit 0
fi

mkdir -p "$REMOTE_DIR/deploy/logs"
git config --global --add safe.directory "$REMOTE_DIR" 2>/dev/null || true
chown -R root:root "$REMOTE_DIR" 2>/dev/null || true
for rel in .env engines/banana-slides/.env; do
  if [[ -f "$REMOTE_DIR/$rel" ]]; then
    cp "$REMOTE_DIR/$rel" "/tmp/preserve_${rel//\//_}"
    echo "preserved $rel"
  fi
done

cd "$REMOTE_DIR"
git init
git config user.email "deploy@wlcloudai.com"
git config user.name "Production Deploy"
git fetch "$BUNDLE" "refs/remotes/origin/$GIT_BRANCH:refs/heads/$GIT_BRANCH"
git checkout -B "$GIT_BRANCH" "$GIT_BRANCH" -f
git reset --hard "$GIT_BRANCH"
git remote add origin "https://github.com/AI-miniLab/ai-tool-market.git" 2>/dev/null || git remote set-url origin "https://github.com/AI-miniLab/ai-tool-market.git"

for rel in .env engines/banana-slides/.env; do
  bak="/tmp/preserve_${rel//\//_}"
  if [[ -f "$bak" ]]; then
    mkdir -p "$(dirname "$REMOTE_DIR/$rel")"
    cp "$bak" "$REMOTE_DIR/$rel"
    echo "restored $rel"
  fi
done

NEW_SHA="$(git rev-parse HEAD)"
echo "$NEW_SHA" > .deploy_revision
printf 'bootstrap\n%s\n\n' "$GIT_BRANCH" > .deploy_meta
date -Iseconds > deploy/logs/last-bootstrap.txt 2>/dev/null || date '+%Y-%m-%dT%H:%M:%S%z' > deploy/logs/last-bootstrap.txt

echo "Bootstrap complete: branch=$GIT_BRANCH sha=$NEW_SHA"
git log -1 --oneline
rm -f "$BUNDLE"
REMOTE

echo "Production git bootstrap finished."
