#!/usr/bin/env python3
"""Sync deploy config and recreate admin-frontend in production mode."""
from __future__ import annotations

import os
import sys
import time
from pathlib import Path

import paramiko

ROOT = Path(__file__).resolve().parents[2]
HOST = os.environ.get("DEPLOY_HOST", "8.134.93.203")
PASSWORD = os.environ.get("DEPLOY_PASSWORD", "")
REMOTE = "/root/ai_tool_market"


def main() -> int:
    if not PASSWORD:
        print("DEPLOY_PASSWORD required", file=sys.stderr)
        return 1

    ssh = paramiko.SSHClient()
    ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    print(f"Connecting to {HOST}...")
    ssh.connect(HOST, username="root", password=PASSWORD, timeout=30)

    sftp = ssh.open_sftp()
    for rel in ("deploy/docker-compose.yml", "deploy/nginx/default.conf"):
        local = ROOT / rel
        remote = f"{REMOTE}/{rel}"
        print(f"Upload {rel}")
        sftp.put(str(local), remote)
    sftp.close()

    script = f"""
set -e
cd {REMOTE}
grep -q '^APP_PRODUCTION_MODE=true' .env || echo 'APP_PRODUCTION_MODE=true' >> .env
sed -i 's/^APP_PRODUCTION_MODE=false/APP_PRODUCTION_MODE=true/' .env || true
cd deploy
docker compose -f docker-compose.yml -f docker-compose.nginx.yml stop admin-frontend || true
docker volume rm deploy_admin_frontend_next 2>/dev/null || true
docker compose -f docker-compose.yml -f docker-compose.nginx.yml up -d --force-recreate admin-frontend
docker exec ai-supermarket-nginx nginx -s reload || true
echo "waiting for admin prod build..."
for i in $(seq 1 40); do
  if curl -s -o /dev/null -w '%{{http_code}}' --max-time 5 http://127.0.0.1:5174/admin 2>/dev/null | grep -q 200; then
    echo "admin ready"
    break
  fi
  sleep 15
done
curl -sI --max-time 10 http://127.0.0.1/admin 2>&1 | head -12
docker logs --tail 12 ai-supermarket-admin-frontend 2>&1 | tail -12
"""
    _, stdout, stderr = ssh.exec_command(script, timeout=3600)
    out = stdout.read().decode("utf-8", errors="replace")
    err = stderr.read().decode("utf-8", errors="replace")
    if out:
        print(out)
    if err:
        print(err, file=sys.stderr)
    code = stdout.channel.recv_exit_status()
    ssh.close()
    return code


if __name__ == "__main__":
    raise SystemExit(main())
