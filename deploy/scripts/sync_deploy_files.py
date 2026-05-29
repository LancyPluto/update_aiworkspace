#!/usr/bin/env python3
"""Sync critical deploy files to remote server."""
from __future__ import annotations

import os
import sys
from pathlib import Path

import paramiko

ROOT = Path(__file__).resolve().parents[2]
REMOTE = "/root/ai_tool_market"

FILES = [
    "backend/Dockerfile",
    "backend/.dockerignore",
    "backend/src/main/java/com/aiminilab/aitoolmarket/task/dto/TaskDetailResponse.java",
    "engines/banana-slides/backend/Dockerfile",
    "engines/banana-slides/docker/debian-apt-mirror.sh",
    "engines/banana-slides/Dockerfile.allinone",
    "deploy/docker-compose.yml",
    "deploy/docker/debian-apt-mirror.sh",
    "worker/Dockerfile",
    "agent-service/Dockerfile",
    ".env.example",
]


def main() -> int:
    password = os.environ.get("DEPLOY_PASSWORD")
    host = os.environ.get("DEPLOY_HOST", "8.134.93.203")
    if not password:
        print("DEPLOY_PASSWORD required", file=sys.stderr)
        return 1

    ssh = paramiko.SSHClient()
    ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    ssh.connect(host, username="root", password=password, timeout=30)
    sftp = ssh.open_sftp()

    for rel in FILES:
        local = ROOT / rel
        if not local.is_file():
            print(f"skip missing: {rel}")
            continue
        remote = f"{REMOTE}/{rel.replace(chr(92), '/')}"
        remote_dir = str(Path(remote).parent).replace("\\", "/")
        parts = remote_dir.split("/")
        cur = ""
        for p in parts:
            if not p:
                continue
            cur = f"{cur}/{p}" if cur else f"/{p}"
            try:
                sftp.mkdir(cur)
            except OSError:
                pass
        data = local.read_bytes().replace(b"\r\n", b"\n")
        with sftp.open(remote, "wb") as rf:
            rf.write(data)
        print(f"ok {rel}")

    sftp.close()
    _, stdout, stderr = ssh.exec_command(
        f"head -25 {REMOTE}/engines/banana-slides/backend/Dockerfile && echo '---' && "
        f"grep -A6 'banana-slides:' {REMOTE}/deploy/docker-compose.yml | head -12",
        timeout=60,
    )
    print(stdout.read().decode())
    err = stderr.read().decode()
    if err:
        print(err, file=sys.stderr)
    ssh.close()
    print("Sync done. On server run:")
    print(f"  cd {REMOTE}/deploy && docker compose build --no-cache banana-slides worker agent-service")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
