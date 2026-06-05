#!/usr/bin/env python3
"""Fix production GPT-Image billing + tool visibility, then restart backend."""
from __future__ import annotations

import os
import sys
import time

import paramiko

HOST = os.environ.get("DEPLOY_HOST", "8.134.93.203")
PASSWORD = os.environ.get("DEPLOY_PASSWORD", "")

SQL = """
UPDATE agent_model_configs
SET billing_unit = 'IMAGE_TOKEN',
    input_token_price_per_1m = CASE WHEN input_token_price_per_1m > 0 THEN input_token_price_per_1m ELSE 8 END,
    output_token_price_per_1m = CASE WHEN output_token_price_per_1m > 0 THEN output_token_price_per_1m ELSE 30 END
WHERE is_deleted = 0
  AND (
    LOWER(model_name) LIKE '%gpt-image%'
    OR LOWER(display_name) LIKE '%image2%'
    OR LOWER(display_name) LIKE '%gpt-image%'
  );

UPDATE ai_tools
SET status = 'ONLINE'
WHERE tool_code = 'ofox_gpt_image2' AND status <> 'ONLINE';
"""


def run(ssh: paramiko.SSHClient, cmd: str, timeout: int = 600) -> str:
    _, stdout, stderr = ssh.exec_command(cmd, timeout=timeout)
    out = stdout.read().decode("utf-8", errors="replace")
    err = stderr.read().decode("utf-8", errors="replace")
    if err.strip():
        print(err.strip(), file=sys.stderr)
    return out.strip()


def main() -> int:
    if not PASSWORD:
        print("DEPLOY_PASSWORD required", file=sys.stderr)
        return 1

    ssh = paramiko.SSHClient()
    ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    ssh.connect(HOST, username="root", password=PASSWORD, timeout=30)

    sftp = ssh.open_sftp()
    with sftp.file("/tmp/fix_gpt_image.sql", "w") as f:
        f.write(SQL)
    sftp.close()

    print("Applying GPT-Image SQL fixes...")
    run(
        ssh,
        "docker compose -f /root/ai_tool_market/deploy/docker-compose.yml exec -T mysql "
        "mysql -uroot -proot123456 ai_supermarket_v1 < /tmp/fix_gpt_image.sql",
    )

    print("Restarting backend...")
    run(
        ssh,
        "cd /root/ai_tool_market/deploy && docker compose -f docker-compose.yml restart backend",
        timeout=120,
    )

    for i in range(20):
        time.sleep(5)
        code = run(ssh, "curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:8080/actuator/health")
        print(f"backend health: {code}")
        if code == "200":
            break

    raw = run(ssh, "curl -s 'http://127.0.0.1:8080/api/v1/tools?pageNo=1&pageSize=50'")
    print(raw[:1200])

    ssh.close()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
