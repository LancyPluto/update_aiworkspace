#!/usr/bin/env python3
from __future__ import annotations

import os
import sys
from pathlib import Path

import paramiko

ROOT = Path(__file__).resolve().parents[2]
REMOTE = "/root/ai_tool_market"
FILES = [
    "backend/pom.xml",
    "backend/src/main/java/com/aiminilab/aitoolmarket/config/AppProperties.java",
    "backend/src/main/java/com/aiminilab/aitoolmarket/config/CorsConfig.java",
    "backend/src/main/java/com/aiminilab/aitoolmarket/storage/StoredAsset.java",
    "backend/src/main/java/com/aiminilab/aitoolmarket/storage/AssetStorageService.java",
    "backend/src/main/java/com/aiminilab/aitoolmarket/support/GeneratedMediaPathSupport.java",
    "backend/src/main/java/com/aiminilab/aitoolmarket/tool/controller/UserUploadController.java",
    "backend/src/main/java/com/aiminilab/aitoolmarket/tool/service/impl/ToolServiceImpl.java",
    "backend/src/main/java/com/aiminilab/aitoolmarket/user/service/impl/UserProfileServiceImpl.java",
    "backend/src/main/java/com/aiminilab/aitoolmarket/admin/service/impl/SystemSettingServiceImpl.java",
    "backend/src/main/java/com/aiminilab/aitoolmarket/market/service/impl/AdminAiMarketToolServiceImpl.java",
    "backend/src/main/java/com/aiminilab/aitoolmarket/market/service/impl/AiMarketFileServiceImpl.java",
    "backend/src/main/java/com/aiminilab/aitoolmarket/agent/service/AgentAttachmentUrlResolver.java",
    "backend/src/main/resources/application.yml",
    "backend/src/test/java/com/aiminilab/aitoolmarket/storage/AssetStorageServiceTest.java",
    "worker/requirements.txt",
    "worker/storage/__init__.py",
    "worker/storage/asset_storage.py",
    "worker/handlers/generated_image_persister.py",
    "worker/handlers/generated_video_persister.py",
    "worker/handlers/generated_audio_persister.py",
    "worker/handlers/digital_human_postprocessor.py",
    "worker/tests/test_asset_storage.py",
    "deploy/docker-compose.yml",
    ".env.example",
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
        remote_dir = os.path.dirname(remote_path)
        ssh.exec_command(f"mkdir -p {remote_dir}")
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
        "docker compose -f docker-compose.yml -f docker-compose.nginx.yml build backend worker && "
        "docker compose -f docker-compose.yml -f docker-compose.nginx.yml up -d --force-recreate backend worker nginx && "
        "sleep 25 && "
        "python3 /root/ai_tool_market/deploy/scripts/import_config_bundle.py "
        "/root/ai_tool_market/ai-tool-market-config-2026-06-09.json --base-url http://127.0.0.1:8080"
    )
    _, stdout, stderr = ssh.exec_command(cmd, timeout=1200)
    print((stdout.read().decode("utf-8", errors="replace") + stderr.read().decode("utf-8", errors="replace"))[-10000:])
    ssh.close()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
