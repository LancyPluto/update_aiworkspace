#!/usr/bin/env python3
"""Upload migration script to production, run it, restart services."""
from __future__ import annotations

import os
import sys
from pathlib import Path

import paramiko

ROOT = Path(__file__).resolve().parents[2]
REMOTE = "/root/ai_tool_market"
SCRIPT = ROOT / "deploy" / "scripts" / "migrate_assets_to_prod_bucket.py"


def main() -> int:
    password = os.environ.get("DEPLOY_PASSWORD", "KeChuangDianAi17728033019")
    host = os.environ.get("DEPLOY_HOST", "8.134.93.203")
    dry_run = "--dry-run" in sys.argv

    ssh = paramiko.SSHClient()
    ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    ssh.connect(host, username="root", password=password, timeout=30)
    sftp = ssh.open_sftp()

    remote_script = f"{REMOTE}/deploy/scripts/migrate_assets_to_prod_bucket.py"
    ssh.exec_command(f"mkdir -p {REMOTE}/deploy/scripts")
    with sftp.open(remote_script, "wb") as f:
        f.write(SCRIPT.read_bytes().replace(b"\r\n", b"\n"))
    sftp.close()
    print("uploaded migration script")

    flags = "--dry-run" if dry_run else ""
    cmd = (
        f"cd {REMOTE} && "
        f"pip3 install oss2 python-dotenv -q 2>/dev/null; "
        f"GENERATED_MEDIA_DIR={REMOTE}/data/generated-media "
        f"python3 {remote_script} {flags} --env-path {REMOTE}/.env"
    )
    _, stdout, stderr = ssh.exec_command(cmd, timeout=1800)
    out = stdout.read().decode("utf-8", errors="replace")
    err = stderr.read().decode("utf-8", errors="replace")
    print(out)
    if err:
        print(err, file=sys.stderr)

    if dry_run:
        ssh.close()
        return 0

    restart = (
        f"cd {REMOTE}/deploy && "
        "docker compose -f docker-compose.yml -f docker-compose.nginx.yml up -d --force-recreate backend worker && "
        "sleep 35 && "
        "docker exec ai-supermarket-backend printenv OSS_BUCKET ASSET_STORAGE_PUBLIC_BASE_URL && "
        "docker logs ai-supermarket-backend 2>&1 | grep 'Asset storage' | tail -1"
    )
    _, stdout, stderr = ssh.exec_command(restart, timeout=300)
    print(stdout.read().decode())
    print(stderr.read().decode())
    ssh.close()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
