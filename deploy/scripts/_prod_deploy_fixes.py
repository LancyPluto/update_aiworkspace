#!/usr/bin/env python3
"""Deploy oFox proxy fix, nginx static/https, recharge UI fix to production."""
from __future__ import annotations

import os
import subprocess
import sys
import time
from pathlib import Path

import paramiko

ROOT = Path(__file__).resolve().parents[2]
HOST = os.environ.get("DEPLOY_HOST", "8.134.93.203")
PASSWORD = os.environ.get("DEPLOY_PASSWORD", "")


def run_ssh(ssh: paramiko.SSHClient, title: str, cmd: str, timeout: int = 900) -> int:
    print(f"\n=== {title} ===")
    _, stdout, stderr = ssh.exec_command(cmd, timeout=timeout)
    out = stdout.read().decode("utf-8", errors="replace")
    err = stderr.read().decode("utf-8", errors="replace")
    code = stdout.channel.recv_exit_status()
    if out:
        print(out.rstrip())
    if err:
        print(err.rstrip(), file=sys.stderr)
    print(f"[exit={code}]")
    return code


def main() -> int:
    if not PASSWORD:
        print("DEPLOY_PASSWORD required", file=sys.stderr)
        return 1

    env = os.environ.copy()
    env["DEPLOY_PASSWORD"] = PASSWORD
    sync = subprocess.run([sys.executable, str(ROOT / "deploy/scripts/sync_to_server.py")], env=env)
    if sync.returncode != 0:
        return sync.returncode

    ssh = paramiko.SSHClient()
    ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    ssh.connect(HOST, username="root", password=PASSWORD, timeout=30, allow_agent=False, look_for_keys=False)

    run_ssh(
        ssh,
        "build backend",
        "cd /root/ai_tool_market/deploy && docker compose -f docker-compose.yml -f docker-compose.nginx.yml build backend",
        timeout=1800,
    )
    run_ssh(
        ssh,
        "recreate services",
        "cd /root/ai_tool_market/deploy && docker compose -f docker-compose.yml -f docker-compose.nginx.yml "
        "up -d --force-recreate backend user-web nginx admin-frontend",
        timeout=900,
    )

    for i in range(40):
        time.sleep(10)
        _, stdout, _ = ssh.exec_command(
            "docker inspect --format '{{.State.Health.Status}}' ai-supermarket-user-web 2>/dev/null || echo missing",
            timeout=20,
        )
        health = stdout.read().decode().strip()
        print(f"user-web health: {health}")
        if health == "healthy":
            break

    run_ssh(
        ssh,
        "verify",
        "curl -s -o /dev/null -w 'http_root:%{http_code} redirect:%{redirect_url}\\n' http://wlcloudai.com/; "
        "curl -sk -o /dev/null -w 'https_root:%{http_code}\\n' https://wlcloudai.com/; "
        "curl -sk -o /dev/null -w 'https_admin:%{http_code}\\n' -L https://wlcloudai.com/admin; "
        "ASSET=$(docker exec ai-supermarket-nginx grep -o 'assets/index-[^\\\"]*\\.js' /usr/share/nginx/user-web/index.html | sed -n '1p'); "
        "echo asset=$ASSET; curl -sk -o /dev/null -w 'asset:%{http_code}\\n' https://wlcloudai.com/$ASSET; "
        "curl -s -o /dev/null -w 'ofox_proxy:%{http_code}\\n' -x http://127.0.0.1:7890 --max-time 20 https://api.ofox.ai/v1",
        timeout=120,
    )
    ssh.close()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
