#!/usr/bin/env python3
"""Restore WeChat Pay cert/key files on production host and restart backend."""
from __future__ import annotations

import os
import sys
from pathlib import Path

import paramiko


def main() -> int:
    host = os.environ.get("DEPLOY_HOST", "8.134.93.203")
    user = os.environ.get("DEPLOY_USER", "root")
    password = os.environ.get("DEPLOY_PASSWORD")
    if not password:
        print("DEPLOY_PASSWORD required", file=sys.stderr)
        return 1

    root = Path(__file__).resolve().parents[1]
    local_dir = root / "WXcert"
    files = ["apiclient_key.pem", "apiclient_cert.pem", "pub_key.pem"]
    missing = [name for name in files if not (local_dir / name).is_file()]
    if missing:
        print(f"Missing local WXcert files: {missing}", file=sys.stderr)
        return 1

    ssh = paramiko.SSHClient()
    ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    ssh.connect(host, username=user, password=password, timeout=30, allow_agent=False, look_for_keys=False)
    sftp = ssh.open_sftp()

    remote_root = "/root/ai_tool_market"
    remote_dir = f"{remote_root}/WXcert"

    ssh.exec_command(f"mkdir -p {remote_dir} && chmod 700 {remote_dir}", timeout=30)

    for name in files:
        local_path = str(local_dir / name)
        remote_path = f"{remote_dir}/{name}"
        sftp.put(local_path, remote_path)
        ssh.exec_command(f"chmod 600 {remote_path}", timeout=30)

    sftp.close()

    # Restart backend to pick up bind-mounted files.
    cmd = (
        f"cd {remote_root}/deploy && "
        "docker compose -f docker-compose.yml -f docker-compose.nginx.yml up -d --force-recreate backend && "
        "docker exec ai-supermarket-backend sh -lc 'ls -la /app/WXcert && head -n 2 /app/WXcert/apiclient_key.pem' || true"
    )
    _, stdout, stderr = ssh.exec_command(cmd, timeout=120)
    out = stdout.read().decode("utf-8", "replace")
    err = stderr.read().decode("utf-8", "replace")
    if out:
        sys.stdout.buffer.write(out.encode("utf-8", errors="replace"))
        if not out.endswith("\n"):
            sys.stdout.write("\n")
    if err:
        sys.stderr.buffer.write(err.encode("utf-8", errors="replace"))
        if not err.endswith("\n"):
            sys.stderr.write("\n")

    ssh.close()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

