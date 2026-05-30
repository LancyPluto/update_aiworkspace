#!/usr/bin/env python3
"""Upload local data/generated-media/tool-covers to server backend volume."""
from __future__ import annotations

import os
import re
import sys
from pathlib import Path

import paramiko

ROOT = Path(__file__).resolve().parents[2]
LOCAL_COVERS = ROOT / "data" / "generated-media" / "tool-covers"
REMOTE_STAGING = "/root/ai_tool_market/data/generated-media/tool-covers"
REMOTE_DIR = "/root/ai_tool_market"
ALLOWED_SUFFIXES = {".mp4", ".webm", ".mov", ".m4v", ".png", ".jpg", ".jpeg", ".webp", ".gif"}


def resolve_password() -> str | None:
    password = os.environ.get("DEPLOY_PASSWORD")
    if password:
        return password
    remote_py = ROOT / "_remote.py"
    if not remote_py.is_file():
        return None
    text = remote_py.read_text(encoding="utf-8")
    match = re.search(r"connect\([^,]+,\s*\d+,\s*[^,]+,\s*'([^']+)'", text)
    return match.group(1) if match else None


def list_local_files() -> list[Path]:
    if not LOCAL_COVERS.is_dir():
        return []
    files = []
    for path in LOCAL_COVERS.iterdir():
        if path.is_file() and path.suffix.lower() in ALLOWED_SUFFIXES:
            files.append(path)
    return sorted(files, key=lambda p: p.name)


def ensure_remote_dir(sftp: paramiko.SFTPClient, remote_dir: str) -> None:
    cur = ""
    for part in remote_dir.replace("\\", "/").split("/"):
        if not part:
            continue
        cur = f"{cur}/{part}" if cur else f"/{part}"
        try:
            sftp.mkdir(cur)
        except OSError:
            pass


def main() -> int:
    local_files = list_local_files()
    print(f"Local folder: {LOCAL_COVERS}")
    print(f"Files found: {len(local_files)}")

    if not local_files:
        print("\n本地目录为空或不存在。请把封面视频/图片放进上述文件夹后重新运行：")
        print(f"  python deploy/scripts/upload_tool_covers.py")
        print("\n文件名需与数据库 cover_url 一致（可从管理端导出配置查看），例如：")
        print("  可灵-V3-Omni-kling-v3-omni-可灵-V3-Omni-20260528094612.mp4")
        return 0

    password = resolve_password()
    host = os.environ.get("DEPLOY_HOST", "8.134.93.203")
    if not password:
        print("Set DEPLOY_PASSWORD or provide _remote.py", file=sys.stderr)
        return 1

    ssh = paramiko.SSHClient()
    ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    print(f"Connecting to {host}...")
    ssh.connect(host, username="root", password=password, timeout=30)
    sftp = ssh.open_sftp()
    ensure_remote_dir(sftp, REMOTE_STAGING)

    for path in local_files:
        remote = f"{REMOTE_STAGING}/{path.name}"
        print(f"  upload {path.name} ({path.stat().st_size // 1024} KB)")
        with sftp.open(remote, "wb") as rf:
            rf.write(path.read_bytes())
    sftp.close()

    copy_cmd = (
        f"docker exec ai-supermarket-backend mkdir -p /data/generated-media/tool-covers && "
        f"docker cp {REMOTE_STAGING}/. ai-supermarket-backend:/data/generated-media/tool-covers/ && "
        f"docker exec ai-supermarket-backend sh -c 'ls -la /data/generated-media/tool-covers | head -20' && "
        f"curl -s -o /dev/null -w 'sample:%{{http_code}}\\n' "
        f"http://127.0.0.1/generated/tool-covers/{local_files[0].name} || true"
    )
    print("Copying into backend container volume...")
    _, stdout, stderr = ssh.exec_command(copy_cmd, timeout=600)
    out = stdout.read().decode("utf-8", errors="replace")
    err = stderr.read().decode("utf-8", errors="replace")
    if out.strip():
        print(out.rstrip())
    if err.strip():
        print(err.rstrip(), file=sys.stderr)
    code = stdout.channel.recv_exit_status()
    ssh.close()

    if code != 0:
        print("Upload/copy failed", file=sys.stderr)
        return code

    print(f"\nDone. Uploaded {len(local_files)} file(s).")
    print(f"访问示例: http://{host}/generated/tool-covers/{local_files[0].name}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
