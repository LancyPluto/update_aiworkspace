#!/usr/bin/env python3
"""Deploy batch-3: explicit worker proxy + cover URL sanitization."""
from __future__ import annotations

import os
import subprocess
import sys
from pathlib import Path

import paramiko

ROOT = Path(__file__).resolve().parents[2]
PASSWORD = os.environ.get("DEPLOY_PASSWORD", "")


def ssh_client() -> paramiko.SSHClient:
    client = paramiko.SSHClient()
    client.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    client.connect("8.134.93.203", username="root", password=PASSWORD, timeout=30)
    return client


def remote(client: paramiko.SSHClient, cmd: str) -> None:
    print("REMOTE:", cmd[:160])
    _, stdout, stderr = client.exec_command(cmd, timeout=1800)
    out = stdout.read().decode()
    err = stderr.read().decode()
    if out.strip():
        print(out.rstrip())
    if err.strip():
        print(err.rstrip(), file=sys.stderr)
    if stdout.channel.recv_exit_status() != 0:
        raise SystemExit("remote failed")


def upload(client: paramiko.SSHClient, rel: str) -> None:
    local = ROOT / rel.replace("/", os.sep)
    remote = f"/root/ai_tool_market/{rel.replace(chr(92), '/')}"
    remote_dir = os.path.dirname(remote).replace("\\", "/")
    client.exec_command(f"mkdir -p '{remote_dir}'")
    sftp = client.open_sftp()
    with sftp.open(remote, "wb") as handle:
        handle.write(local.read_bytes().replace(b"\r\n", b"\n"))
    sftp.close()
    print("uploaded", rel)


def main() -> int:
    subprocess.run(
        ["python", "-m", "pytest", "tests/test_openai_images_client.py", "-q"],
        cwd=ROOT / "worker",
        check=True,
    )
    files = [
        "worker/client/openai_images_client.py",
        "backend/src/main/java/com/aiminilab/aitoolmarket/support/GeneratedMediaPathSupport.java",
        "backend/src/main/java/com/aiminilab/aitoolmarket/tool/dto/ToolSummaryResponse.java",
        "backend/src/main/java/com/aiminilab/aitoolmarket/tool/service/impl/ToolServiceImpl.java",
    ]
    client = ssh_client()
    try:
        for rel in files:
            upload(client, rel)
        remote(
            client,
            "cd /root/ai_tool_market/deploy && docker compose -f docker-compose.yml build backend worker && "
            "docker compose -f docker-compose.yml up -d --force-recreate backend worker",
        )
        remote(
            client,
            "sleep 20 && docker exec ai-supermarket-worker python -c "
            "'from client.openai_images_client import OpenAIImagesClient; "
            "c=OpenAIImagesClient(base_url=\"https://api.ofox.ai/v1\", api_key=\"x\"); "
            "print(c.session.proxies, c.session.trust_env)'",
        )
    finally:
        client.close()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
