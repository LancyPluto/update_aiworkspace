#!/usr/bin/env python3
from __future__ import annotations

import os
import sys

import paramiko


def main() -> int:
    password = os.environ.get("DEPLOY_PASSWORD", "")
    host = os.environ.get("DEPLOY_HOST", "8.134.93.203")
    if not password:
        print("DEPLOY_PASSWORD required", file=sys.stderr)
        return 1

    ssh = paramiko.SSHClient()
    ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    ssh.connect(host, username="root", password=password, timeout=30)

    def run(cmd: str) -> str:
        _, out, err = ssh.exec_command(cmd, timeout=60)
        b = (out.read() or b"") + (err.read() or b"")
        return b.decode("utf-8", errors="replace")

    print("== volumes containing admin_frontend_next ==")
    vol_text = run("docker volume ls --format '{{.Name}}' | grep -i admin_frontend_next || true")
    print(vol_text)
    vols = [ln.strip() for ln in vol_text.splitlines() if ln.strip()]
    if not vols:
        print("No admin_frontend_next volume found. Skipping.")
        ssh.close()
        return 0

    print("== stop & remove admin-frontend first ==")
    print(run("cd /root/ai_tool_market/deploy && docker compose -f docker-compose.yml -f docker-compose.nginx.yml stop admin-frontend || true"))
    print(run("docker rm -f ai-supermarket-admin-frontend 2>/dev/null || true"))

    for v in vols:
        print(f"== removing volume: {v} ==")
        print(run(f"docker volume rm -f {v} || true"))

    print("== recreate admin-frontend ==")
    print(run("cd /root/ai_tool_market/deploy && docker compose -f docker-compose.yml -f docker-compose.nginx.yml up -d --force-recreate admin-frontend"))

    ssh.close()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

