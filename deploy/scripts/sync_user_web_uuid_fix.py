#!/usr/bin/env python3
"""Upload selected user-web files to remote (volume-mounted Vite dev server)."""
from __future__ import annotations

import os
import re
import sys
from pathlib import Path

import paramiko

ROOT = Path(__file__).resolve().parents[2]
REMOTE = "/root/ai_tool_market"
FILES = [
    "user-web/src/components/AppShell.vue",
    "user-web/src/pages/ToolList/Page.vue",
    "user-web/src/utils/toolCoverMedia.ts",
    "user-web/src/api/aiToolApi.ts",
    "user-web/src/utils/taskResultBlocks.ts",
    "user-web/asset/3D动画生成.mp4",
    "user-web/asset/猴子视频.mp4",
    "user-web/src/utils/randomUUID.ts",
    "user-web/src/utils/index.ts",
    "user-web/src/pages/AgentHome/AgentChatPane.vue",
    "user-web/src/pages/Chat/Page.vue",
    "user-web/src/pages/Dashboard/Page.vue",
    "user-web/src/pages/ToolUse/Page.vue",
]


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


def main() -> int:
    password = resolve_password()
    host = os.environ.get("DEPLOY_HOST", "8.134.93.203")
    if not password:
        print("Set DEPLOY_PASSWORD or provide _remote.py", file=sys.stderr)
        return 1

    ssh = paramiko.SSHClient()
    ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    ssh.connect(host, username="root", password=password, timeout=30)
    sftp = ssh.open_sftp()

    for rel in FILES:
        local = ROOT / rel
        if not local.is_file():
            print(f"missing: {rel}", file=sys.stderr)
            return 1
        remote = f"{REMOTE}/{rel.replace(chr(92), '/')}"
        remote_dir = str(Path(remote).parent).replace("\\", "/")
        cur = ""
        for part in remote_dir.split("/"):
            if not part:
                continue
            cur = f"{cur}/{part}" if cur else f"/{part}"
            try:
                sftp.mkdir(cur)
            except OSError:
                pass
        with sftp.open(remote, "wb") as remote_file:
            remote_file.write(local.read_bytes().replace(b"\r\n", b"\n"))
        print(f"ok {rel}")

    sftp.close()
    ssh.close()
    print("Uploaded. Hard-refresh browser (Ctrl+F5) on http://8.134.93.203")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
