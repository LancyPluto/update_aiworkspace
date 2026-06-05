#!/usr/bin/env python3
"""Wait for admin-frontend prod build and verify /admin via nginx."""
from __future__ import annotations

import os
import sys
import time

import paramiko

HOST = os.environ.get("DEPLOY_HOST", "8.134.93.203")
PASSWORD = os.environ.get("DEPLOY_PASSWORD", "")


def run(ssh: paramiko.SSHClient, cmd: str, timeout: int = 60) -> str:
    _, stdout, _ = ssh.exec_command(cmd, timeout=timeout)
    return stdout.read().decode("utf-8", errors="replace")


def main() -> int:
    if not PASSWORD:
        print("DEPLOY_PASSWORD required", file=sys.stderr)
        return 1

    ssh = paramiko.SSHClient()
    ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    ssh.connect(HOST, username="root", password=PASSWORD, timeout=30)

    for i in range(40):
        logs = run(
            ssh,
            "docker logs --tail 15 ai-supermarket-admin-frontend 2>&1",
            timeout=30,
        )
        direct = run(
            ssh,
            "curl -s -o /dev/null -w '%{http_code}' --max-time 8 http://127.0.0.1:5174/ 2>/dev/null || echo fail",
            timeout=15,
        )
        via_nginx = run(
            ssh,
            "curl -s -o /dev/null -w '%{http_code}' --max-time 15 http://127.0.0.1/admin/ 2>/dev/null || echo fail",
            timeout=20,
        )
        print(f"[{i+1}/40] admin:5174={direct.strip()} nginx/admin={via_nginx.strip()}")
        if "Ready" in logs or "started server" in logs.lower():
            print("--- admin log tail ---")
            print(logs[-800:])
        if direct.strip() in ("200", "307", "308") and via_nginx.strip() in ("200", "307", "308"):
            ssh.close()
            return 0
        time.sleep(30)

    print(run(ssh, "docker logs --tail 40 ai-supermarket-admin-frontend 2>&1", timeout=30))
    ssh.close()
    return 1


if __name__ == "__main__":
    raise SystemExit(main())
