#!/usr/bin/env python3
from __future__ import annotations

import os
import sys
from pathlib import Path

import paramiko


def main() -> int:
    password = os.environ.get("DEPLOY_PASSWORD", "")
    host = os.environ.get("DEPLOY_HOST", "8.134.93.203")
    if not password:
        print("DEPLOY_PASSWORD required", file=sys.stderr)
        return 1

    sys.stdout.reconfigure(encoding="utf-8")

    root = Path(__file__).resolve().parents[2]
    remote_dir = "/root/ai_tool_market"
    remote_compose = f"{remote_dir}/deploy/docker-compose.yml"
    remote_nginx = f"{remote_dir}/deploy/nginx/default.conf"

    ssh = paramiko.SSHClient()
    ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    ssh.connect(host, username="root", password=password, timeout=30)

    sftp = ssh.open_sftp()
    sftp.put(str(root / "deploy/docker-compose.yml"), remote_compose)
    sftp.put(str(root / "deploy/nginx/default.conf"), remote_nginx)
    sftp.close()

    # Ensure prod env
    ssh.exec_command(
        "grep -q '^APP_PRODUCTION_MODE=true' /root/ai_tool_market/.env || "
        "echo 'APP_PRODUCTION_MODE=true' >> /root/ai_tool_market/.env",
        timeout=60,
    )

    cmd = (
        "cd /root/ai_tool_market/deploy && "
        "docker compose -f docker-compose.yml -f docker-compose.nginx.yml up -d --force-recreate admin-frontend"
    )
    ssh.exec_command(cmd, timeout=3600)

    # Wait for admin to be reachable
    for i in range(60):
        _, out, _ = ssh.exec_command(
            "curl -s -o /dev/null -w '%{http_code}' --max-time 5 http://127.0.0.1/admin/ 2>/dev/null || echo 000",
            timeout=10,
        )
        code = (out.read() or b"").decode("utf-8", errors="replace").strip()
        if code in ("200", "301", "302", "307", "308"):
            break
        # Also consider temporary 502/503 while build is running
        if code != "000":
            pass
    # Tail last build lines
    _, out2, _ = ssh.exec_command("docker logs --tail 30 ai-supermarket-admin-frontend 2>&1", timeout=60)
    print(out2.read().decode("utf-8", errors="replace"))
    ssh.close()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

