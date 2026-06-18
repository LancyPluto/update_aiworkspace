#!/usr/bin/env python3
"""Activate OSS on production and restart stack."""
from __future__ import annotations

import os
import re
import sys
from pathlib import Path

import paramiko

ROOT = Path(__file__).resolve().parents[2]
REMOTE = "/root/ai_tool_market"
REMOTE_ENV = f"{REMOTE}/.env"
BUNDLE = ROOT / "ai-tool-market-config-2026-06-09.json"

OSS_BLOCK = """
# 统一资产存储 OSS（双桶：public + private，签名代理访问私有资源）
ASSET_STORAGE_PROVIDER=oss
ASSET_STORAGE_PUBLIC_BASE_URL=https://wlcloudai-assets-public.oss-cn-guangzhou.aliyuncs.com
ASSET_STORAGE_PRIVATE_BASE_URL=/api/v1/assets/private
OSS_ENDPOINT=oss-cn-guangzhou.aliyuncs.com
OSS_PUBLIC_BUCKET=wlcloudai-assets-public
OSS_PRIVATE_BUCKET=wlcloudai-assets-private
OSS_LEGACY_BUCKET=wlcloudai-assets-prod
OSS_KEY_PREFIX=
""".strip()

OSS_KEYS = [
    "ASSET_STORAGE_PROVIDER",
    "ASSET_STORAGE_PUBLIC_BASE_URL",
    "ASSET_STORAGE_PRIVATE_BASE_URL",
    "OSS_ENDPOINT",
    "OSS_BUCKET",
    "OSS_PUBLIC_BUCKET",
    "OSS_PRIVATE_BUCKET",
    "OSS_LEGACY_BUCKET",
    "OSS_KEY_PREFIX",
]


def patch_env(content: str) -> str:
    for key in OSS_KEYS:
        content = re.sub(rf"^{re.escape(key)}=.*\n?", "", content, flags=re.MULTILINE)
    if not content.endswith("\n"):
        content += "\n"
    return content + OSS_BLOCK + "\n"


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

    try:
        with sftp.open(REMOTE_ENV, "rb") as remote_file:
            current = remote_file.read().decode("utf-8", errors="replace")
    except FileNotFoundError:
        current = ""

    patched = patch_env(current)
    with sftp.open(REMOTE_ENV, "wb") as remote_file:
        remote_file.write(patched.encode("utf-8"))
    print("patched production .env OSS settings")

    remote_bundle = f"{REMOTE}/ai-tool-market-config-2026-06-09.json"
    with sftp.open(remote_bundle, "wb") as remote_file:
        remote_file.write(BUNDLE.read_bytes().replace(b"\r\n", b"\n"))
    print("uploaded ai-tool-market-config-2026-06-09.json")

    files = [
        "backend/src/main/resources/application.yml",
        "worker/storage/asset_storage.py",
        "deploy/docker-compose.yml",
    ]
    for rel in files:
        local = ROOT / rel
        remote_path = f"{REMOTE}/{rel}"
        remote_dir = os.path.dirname(remote_path)
        ssh.exec_command(f"mkdir -p {remote_dir}")
        with sftp.open(remote_path, "wb") as remote_file:
            remote_file.write(local.read_bytes().replace(b"\r\n", b"\n"))
        print(f"uploaded {rel}")

    sftp.close()

    cmd = (
        "cd /root/ai_tool_market/deploy && "
        "docker compose -f docker-compose.yml -f docker-compose.nginx.yml build backend worker && "
        "docker compose -f docker-compose.yml -f docker-compose.nginx.yml up -d --force-recreate backend worker nginx && "
        "sleep 30 && "
        "docker logs ai-supermarket-backend --tail 20 2>&1 | grep -E 'Asset storage|Started AiToolMarket' || true && "
        "python3 /root/ai_tool_market/deploy/scripts/import_config_bundle.py "
        "/root/ai_tool_market/ai-tool-market-config-2026-06-09.json --base-url http://127.0.0.1:8080"
    )
    _, stdout, stderr = ssh.exec_command(cmd, timeout=1200)
    print((stdout.read().decode("utf-8", errors="replace") + stderr.read().decode("utf-8", errors="replace"))[-12000:])
    ssh.close()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
