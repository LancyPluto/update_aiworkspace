#!/usr/bin/env python3
from __future__ import annotations

import os
import sys
from pathlib import Path

import paramiko

ROOT = Path(__file__).resolve().parents[2]
SQL = ROOT / "sql" / "068_yearly_recharge_packages.sql"
REMOTE_SQL = "/root/ai_tool_market/sql/068_yearly_recharge_packages.sql"


def main() -> int:
    password = os.environ.get("DEPLOY_PASSWORD")
    if not password:
        print("DEPLOY_PASSWORD required", file=sys.stderr)
        return 1
    ssh = paramiko.SSHClient()
    ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    ssh.connect(os.environ.get("DEPLOY_HOST", "8.134.93.203"), username="root", password=password, timeout=30)
    sftp = ssh.open_sftp()
    sftp.open(REMOTE_SQL, "w").write(SQL.read_text(encoding="utf-8").encode("utf-8"))
    sftp.close()
    cmd = (
        f"docker exec -i ai-supermarket-mysql mysql -uroot -proot123456 ai_supermarket_v1 < {REMOTE_SQL} && "
        "docker exec ai-supermarket-mysql mysql -uroot -proot123456 ai_supermarket_v1 -N -e "
        "\"SELECT package_code, price_amount, credits FROM credit_recharge_packages WHERE package_code LIKE 'yearly_%' ORDER BY sort_order\""
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
