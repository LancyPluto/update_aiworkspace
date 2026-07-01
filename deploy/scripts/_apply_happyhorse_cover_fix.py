#!/usr/bin/env python3
from __future__ import annotations

import os
import sys
from pathlib import Path

import paramiko

SQL = Path(__file__).resolve().parents[2] / "sql" / "079_fix_happyhorse_video_edit_cover.sql"
REMOTE = "/root/ai_tool_market/sql/079_fix_happyhorse_video_edit_cover.sql"


def main() -> int:
    password = os.environ.get("DEPLOY_PASSWORD")
    if not password:
        print("DEPLOY_PASSWORD required", file=sys.stderr)
        return 1
    ssh = paramiko.SSHClient()
    ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    ssh.connect(os.environ.get("DEPLOY_HOST", "8.134.93.203"), username="root", password=password, timeout=30)
    sftp = ssh.open_sftp()
    sftp.open(REMOTE, "w").write(SQL.read_text(encoding="utf-8").encode("utf-8"))
    sftp.close()
    cmd = (
        f"docker exec -i ai-supermarket-mysql mysql -uroot -proot123456 ai_supermarket_v1 < {REMOTE} && "
        "docker exec ai-supermarket-mysql mysql -uroot -proot123456 ai_supermarket_v1 -N -e "
        "\"SELECT tool_code, cover_url, RIGHT(config_note, 120) FROM ai_tools WHERE tool_code='happyhorse_video_edit'\""
    )
    _, stdout, stderr = ssh.exec_command(cmd, timeout=60)
    print(stdout.read().decode("utf-8", errors="replace"))
    err = stderr.read().decode("utf-8", errors="replace")
    if err.strip():
        print(err, file=sys.stderr)
    ssh.close()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
