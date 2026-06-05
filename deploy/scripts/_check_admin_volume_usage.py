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
        return ((out.read() or b"") + (err.read() or b"")).decode("utf-8", errors="replace")

    print(run("docker ps -a --format 'table {{.Names}}\t{{.Status}}' | grep -E 'admin-frontend|deploy_admin_frontend_next' || true"))
    # Who uses this volume
    print("== containers using deploy_admin_frontend_next ==")
    print(run("docker ps -a --format '{{.ID}} {{.Names}}' | while read -r id name; do docker inspect $id --format '{{range .Mounts}}{{if eq .Name \"deploy_admin_frontend_next\"}}{{println .Destination}}{{end}}{{end}}' 2>/dev/null; done | head -20 || true"))

    print("== docker volume inspect ==")
    print(run("docker volume inspect deploy_admin_frontend_next 2>/dev/null || true"))

    ssh.close()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

