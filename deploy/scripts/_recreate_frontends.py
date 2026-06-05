#!/usr/bin/env python3
import os
import sys
import time

import paramiko

HOST = os.environ.get("DEPLOY_HOST", "8.134.93.203")
PASSWORD = os.environ.get("DEPLOY_PASSWORD", "")


def main() -> int:
    if not PASSWORD:
        print("DEPLOY_PASSWORD required", file=sys.stderr)
        return 1

    ssh = paramiko.SSHClient()
    ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    ssh.connect(HOST, username="root", password=PASSWORD, timeout=30)

    cmd = (
        "cd /root/ai_tool_market/deploy && "
        "export DOCKER_REGISTRY= && "
        "docker compose -f docker-compose.yml -f docker-compose.nginx.yml "
        "up -d --force-recreate user-web admin-frontend nginx"
    )
    print("Recreating user-web, admin-frontend, nginx...")
    _, stdout, stderr = ssh.exec_command(cmd, timeout=1800)
    out = stdout.read().decode("utf-8", errors="replace")
    err = stderr.read().decode("utf-8", errors="replace")
    if out:
        print(out[-4000:])
    if err:
        print(err[-2000:], file=sys.stderr)

    for i in range(30):
        time.sleep(20)
        _, o, _ = ssh.exec_command(
            "curl -s -o /dev/null -w 'user:%{http_code} admin:%{http_code} nginx:%{http_code}\\n' "
            "--max-time 12 http://127.0.0.1:5173/ http://127.0.0.1:5174/ http://127.0.0.1/admin/ 2>/dev/null",
            timeout=30,
        )
        line = o.read().decode(errors="replace").strip()
        print(f"[{i+1}] {line}")
        if "admin:200" in line or "admin:307" in line or "admin:308" in line:
            if "user:200" in line or "user:307" in line:
                ssh.close()
                return 0

    ssh.close()
    return 1


if __name__ == "__main__":
    raise SystemExit(main())
