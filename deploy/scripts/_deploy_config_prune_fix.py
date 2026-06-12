#!/usr/bin/env python3
from __future__ import annotations

import os
import sys
from pathlib import Path

import paramiko

ROOT = Path(__file__).resolve().parents[2]
REMOTE = "/root/ai_tool_market"
FILES = [
    "backend/src/main/java/com/aiminilab/aitoolmarket/admin/service/impl/ConfigBundleServiceImpl.java",
    "backend/src/test/java/com/aiminilab/aitoolmarket/admin/AdminConfigurationApiTest.java",
    "admin-frontend/app/settings/page.tsx",
    "admin-frontend/app/tools/page.tsx",
]
BUNDLE = ROOT / "ai-tool-market-config-2026-06-09.json"


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
        remote_path = f"{REMOTE}/{rel}"
        with sftp.open(remote_path, "wb") as remote_file:
            remote_file.write(local.read_bytes().replace(b"\r\n", b"\n"))
        print(f"uploaded {rel}")

    remote_bundle = f"{REMOTE}/ai-tool-market-config-2026-06-09.json"
    with sftp.open(remote_bundle, "wb") as remote_file:
        remote_file.write(BUNDLE.read_bytes().replace(b"\r\n", b"\n"))
    print("uploaded ai-tool-market-config-2026-06-09.json")
    sftp.close()

    cmd = (
        "cd /root/ai_tool_market/deploy && "
        "docker compose -f docker-compose.yml -f docker-compose.nginx.yml build backend admin-frontend && "
        "docker compose -f docker-compose.yml -f docker-compose.nginx.yml up -d --force-recreate backend admin-frontend nginx && "
        "sleep 25 && "
        "python3 /root/ai_tool_market/deploy/scripts/import_config_bundle.py "
        "/root/ai_tool_market/ai-tool-market-config-2026-06-09.json --base-url http://127.0.0.1:8080"
    )
    _, stdout, stderr = ssh.exec_command(cmd, timeout=1200)
    out = stdout.read().decode("utf-8", errors="replace")
    err = stderr.read().decode("utf-8", errors="replace")
    print((out + err)[-8000:])
    ssh.close()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
