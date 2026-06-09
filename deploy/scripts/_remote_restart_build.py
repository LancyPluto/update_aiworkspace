#!/usr/bin/env python3
"""Background build + restart on remote (long timeout)."""
from __future__ import annotations

import os
import sys
import time

import paramiko

HOST = os.environ.get("DEPLOY_HOST", "8.134.93.203")
PASSWORD = os.environ.get("DEPLOY_PASSWORD", "")
LOG = "/tmp/deploy_build.log"
MARKER = "/tmp/deploy_build.done"


def main() -> int:
    if not PASSWORD:
        print("DEPLOY_PASSWORD required", file=sys.stderr)
        return 1

    remote_script = f"""
set -e
cd /root/ai_tool_market/deploy
export DOCKER_REGISTRY=
: > {LOG}
echo "==> build started $(date -Iseconds)" >> {LOG}
docker compose -f docker-compose.yml -f docker-compose.nginx.yml build backend agent-service worker >> {LOG} 2>&1
echo "BUILD_EXIT=$?" >> {LOG}
docker compose -f docker-compose.yml -f docker-compose.nginx.yml up -d --force-recreate backend agent-service worker user-web admin-frontend nginx >> {LOG} 2>&1
echo "UP_EXIT=$?" >> {LOG}
docker compose -f docker-compose.yml -f docker-compose.nginx.yml ps >> {LOG} 2>&1
curl -s -o /dev/null -w "backend:%{{http_code}}\\n" http://127.0.0.1:8080/actuator/health >> {LOG} 2>&1 || true
curl -s -o /dev/null -w "nginx:%{{http_code}}\\n" http://127.0.0.1/ >> {LOG} 2>&1 || true
echo "==> finished $(date -Iseconds)" >> {LOG}
touch {MARKER}
"""

    ssh = paramiko.SSHClient()
    ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    print(f"Connecting to {HOST}...")
    ssh.connect(HOST, username="root", password=PASSWORD, timeout=30, allow_agent=False, look_for_keys=False)

    # Start build in background
    sftp = ssh.open_sftp()
    with sftp.file("/tmp/remote_restart_build.sh", "w") as f:
        f.write(remote_script)
    sftp.close()
    _, stdout, _ = ssh.exec_command(
        "chmod +x /tmp/remote_restart_build.sh && nohup /tmp/remote_restart_build.sh > /tmp/deploy_nohup.out 2>&1 & echo started",
        timeout=30,
    )
    print(stdout.read().decode().strip())

    # Poll log (up to 60 min)
    for i in range(120):
        time.sleep(30)
        _, stdout, _ = ssh.exec_command(
            f"test -f {MARKER} && echo DONE || (echo RUNNING; tail -8 {LOG})",
            timeout=60,
        )
        out = stdout.read().decode("utf-8", errors="replace").strip()
        if out.startswith("DONE"):
            _, stdout, _ = ssh.exec_command(f"cat {LOG}", timeout=120)
            print(stdout.read().decode("utf-8", errors="replace"))
            _, stdout, _ = ssh.exec_command(
                f"grep -E 'BUILD_EXIT|UP_EXIT|backend:|nginx:|finished' {LOG}",
                timeout=30,
            )
            summary = stdout.read().decode("utf-8", errors="replace")
            print("--- summary ---")
            print(summary)
            ssh.close()
            if "BUILD_EXIT=0" in summary and "UP_EXIT=0" in summary:
                return 0
            return 1
        tail = out.splitlines()[-1] if out else ""
        print(f"[{i+1}/120] {tail[:120]}")

    _, stdout, _ = ssh.exec_command(f"tail -80 {LOG}", timeout=60)
    print(stdout.read().decode("utf-8", errors="replace"))
    ssh.close()
    print("Timeout waiting for build", file=sys.stderr)
    return 1


if __name__ == "__main__":
    raise SystemExit(main())
