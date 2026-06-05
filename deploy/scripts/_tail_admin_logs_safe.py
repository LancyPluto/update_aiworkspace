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

    # Avoid PowerShell default GBK output issues
    sys.stdout.reconfigure(encoding="utf-8")

    ssh = paramiko.SSHClient()
    ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    ssh.connect(host, username="root", password=password, timeout=30)

    def run(cmd: str, timeout: int = 60) -> str:
        _, out, err = ssh.exec_command(cmd, timeout=timeout)
        b1 = out.read() or b""
        b2 = err.read() or b""
        return b1.decode("utf-8", errors="replace") + b2.decode("utf-8", errors="replace")

    print("== server .env APP_PRODUCTION_MODE ==")
    print(run("grep -n '^APP_PRODUCTION_MODE' /root/ai_tool_market/.env 2>/dev/null || true"))

    print("== admin-frontend container env (APP_PRODUCTION_MODE) ==")
    print(
        run(
            "docker inspect -f '{{range .Config.Env}}{{println .}}{{end}}' ai-supermarket-admin-frontend 2>/dev/null | grep APP_PRODUCTION_MODE || true"
        )
    )

    print("== admin-frontend ps (next dev vs start) ==")
    print(
        run(
            "docker exec ai-supermarket-admin-frontend sh -lc 'ps aux | grep -E \"next dev|next start|node .*next\" | grep -v grep || true'"
        )
    )

    print("== admin-frontend logs tail ==")
    print(run("docker logs --tail 120 ai-supermarket-admin-frontend 2>&1"))

    ssh.close()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

