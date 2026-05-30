#!/usr/bin/env python3
"""Re-upload toolCoverMedia.ts and ToolList Page.vue to fix missing exports."""
from __future__ import annotations

import os
import re
import sys
from pathlib import Path

import paramiko

ROOT = Path(__file__).resolve().parents[2]
REMOTE = "/root/ai_tool_market"
FILES = [
    "user-web/src/utils/toolCoverMedia.ts",
    "user-web/src/pages/ToolList/Page.vue",
    "user-web/src/api/aiToolApi.ts",
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
        remote = f"{REMOTE}/{rel.replace(chr(92), '/')}"
        content = local.read_text(encoding="utf-8")
        if "isVideoPreviewUrl" not in content and rel.endswith("toolCoverMedia.ts"):
            print(f"local file missing export: {rel}", file=sys.stderr)
            return 1
        with sftp.open(remote, "w") as rf:
            rf.write(content.replace("\r\n", "\n").encode("utf-8"))
        print(f"ok {rel} ({len(content)} chars)")

    sftp.close()
    _, stdout, _ = ssh.exec_command(
        f"grep -n isVideoPreviewUrl {REMOTE}/user-web/src/utils/toolCoverMedia.ts | head -3; "
        f"cd {REMOTE}/deploy && docker compose -f docker-compose.yml restart user-web",
        timeout=120,
    )
    print(stdout.read().decode("utf-8", errors="replace"))
    ssh.close()
    print("Done. Hard-refresh http://8.134.93.203")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
