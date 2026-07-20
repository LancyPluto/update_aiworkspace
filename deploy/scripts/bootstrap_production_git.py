#!/usr/bin/env python3
"""Bootstrap production /root/ai_tool_market as a git checkout of origin/dev (Windows-friendly)."""
from __future__ import annotations

import os
import subprocess
import sys
import tempfile
from pathlib import Path

import paramiko

ROOT = Path(__file__).resolve().parents[2]
REMOTE_DIR = os.environ.get("REMOTE_DIR", "/root/ai_tool_market")
GIT_BRANCH = os.environ.get("DEPLOY_GIT_BRANCH", "dev")


def main() -> int:
    host = os.environ.get("DEPLOY_HOST", "8.134.93.203")
    user = os.environ.get("DEPLOY_USER", "root")
    password = os.environ.get("DEPLOY_PASSWORD")
    if not password:
        print("DEPLOY_PASSWORD required", file=sys.stderr)
        return 1

    with tempfile.NamedTemporaryFile(suffix=".bundle", delete=False) as tmp:
        bundle_path = tmp.name

    try:
        print(f"Creating git bundle for origin/{GIT_BRANCH} ...")
        subprocess.run(
            ["git", "fetch", "origin", GIT_BRANCH],
            cwd=ROOT,
            check=False,
            capture_output=True,
        )
        subprocess.run(
            ["git", "bundle", "create", bundle_path, f"origin/{GIT_BRANCH}"],
            cwd=ROOT,
            check=True,
        )

        ssh = paramiko.SSHClient()
        ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
        ssh.connect(host, username=user, password=password, timeout=30, allow_agent=False, look_for_keys=False)

        _, stdout, _ = ssh.exec_command(f"test -d {REMOTE_DIR}/.git && echo yes || echo no", timeout=30)
        if stdout.read().decode().strip() == "yes":
            print(f"Git already initialized at {REMOTE_DIR}")
            ssh.close()
            return 0

        sftp = ssh.open_sftp()
        remote_bundle = "/tmp/ai-tool-market-bootstrap.bundle"
        print("Uploading bundle ...")
        sftp.put(bundle_path, remote_bundle)
        sftp.close()

        remote_script = f"""set -e
REMOTE_DIR="{REMOTE_DIR}"
GIT_BRANCH="{GIT_BRANCH}"
BUNDLE="{remote_bundle}"
git config --global --add safe.directory "$REMOTE_DIR" 2>/dev/null || true
chown -R root:root "$REMOTE_DIR" 2>/dev/null || true
mkdir -p "$REMOTE_DIR/deploy/logs"
for rel in .env engines/banana-slides/.env; do
  if [ -f "$REMOTE_DIR/$rel" ]; then
    cp "$REMOTE_DIR/$rel" "/tmp/preserve_${{rel//\\//_}}"
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
  bak="/tmp/preserve_${{rel//\\//_}}"
  if [ -f "$bak" ]; then
    mkdir -p "$(dirname "$REMOTE_DIR/$rel")"
    cp "$bak" "$REMOTE_DIR/$rel"
    echo "restored $rel"
  fi
done
NEW_SHA="$(git rev-parse HEAD)"
rm -f .deploy_revision .deploy_meta .deploy_revision.pending .deploy_meta.pending
git log -1 --oneline
rm -f "$BUNDLE"
echo "Bootstrap complete: $NEW_SHA"
echo "No successful release is recorded; the first deployment will rebuild all services."
"""
        _, stdout, stderr = ssh.exec_command(remote_script, timeout=300)
        out = stdout.read().decode()
        err = stderr.read().decode()
        code = stdout.channel.recv_exit_status()
        if out:
            print(out, end="" if out.endswith("\n") else "\n")
        if err:
            print(err, file=sys.stderr, end="" if err.endswith("\n") else "\n")
        ssh.close()
        return code
    finally:
        Path(bundle_path).unlink(missing_ok=True)


if __name__ == "__main__":
    raise SystemExit(main())
