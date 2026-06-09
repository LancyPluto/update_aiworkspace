#!/usr/bin/env python3
import os
import sys

import paramiko

HOST = os.environ.get("DEPLOY_HOST", "8.134.93.203")
USER = os.environ.get("DEPLOY_USER", "root")
PASSWORD = os.environ.get("DEPLOY_PASSWORD", "")

SQL = r"""
SELECT id, username, phone, nickname, user_type, status, created_at
FROM users
ORDER BY id
LIMIT 50;
SELECT user_type, status, COUNT(*) AS c
FROM users
GROUP BY user_type, status;
"""


def main() -> int:
    if not PASSWORD:
        print("DEPLOY_PASSWORD required", file=sys.stderr)
        return 1
    ssh = paramiko.SSHClient()
    ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    ssh.connect(HOST, username=USER, password=PASSWORD, timeout=30, allow_agent=False, look_for_keys=False)
    cmd = (
        "docker compose -f /root/ai_tool_market/deploy/docker-compose.yml exec -T mysql "
        "mysql -uroot -proot123456 ai_supermarket_v1"
    )
    _, stdout, stderr = ssh.exec_command(cmd, timeout=60)
    stdin = stdout.channel.makefile_stdin("w")
    stdin.write(SQL)
    stdin.close()
    out = stdout.read().decode("utf-8", errors="replace")
    err = stderr.read().decode("utf-8", errors="replace")
    if out:
        print(out.rstrip())
    if err:
        print(err.rstrip(), file=sys.stderr)
    code = stdout.channel.recv_exit_status()
    ssh.close()
    return code


if __name__ == "__main__":
    raise SystemExit(main())
