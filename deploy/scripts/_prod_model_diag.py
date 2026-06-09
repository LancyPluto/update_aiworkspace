#!/usr/bin/env python3
from __future__ import annotations

import os
import sys

import paramiko

HOST = os.environ.get("DEPLOY_HOST", "8.134.93.203")
PASSWORD = os.environ.get("DEPLOY_PASSWORD", "")

SQL = r"""
SELECT
  c.id,
  c.display_name,
  c.config_code,
  c.provider,
  c.model_name,
  c.base_url,
  c.vendor_account_id,
  c.enabled,
  c.agent_enabled,
  c.last_test_success,
  LEFT(COALESCE(c.last_test_message, ''), 160) AS last_test_message,
  c.last_test_at,
  a.vendor_code,
  a.account_name,
  a.base_url AS account_base_url,
  a.enabled AS account_enabled,
  a.health_status,
  CHAR_LENGTH(COALESCE(a.api_key, '')) AS account_key_len,
  CHAR_LENGTH(COALESCE(c.api_key, '')) AS config_key_len
FROM agent_model_configs c
LEFT JOIN model_vendor_accounts a ON a.id = c.vendor_account_id
WHERE c.is_deleted = 0
  AND (
    LOWER(c.model_name) LIKE '%gpt-image%'
    OR LOWER(c.display_name) LIKE '%image2%'
    OR LOWER(c.display_name) LIKE '%gpt-image%'
    OR c.provider = 'suno_music'
    OR LOWER(c.display_name) LIKE '%suno%'
    OR LOWER(c.model_name) LIKE '%suno%'
  )
ORDER BY c.id;

SELECT id, tool_code, tool_name, status, model_config_id, execution_handler
FROM ai_tools
WHERE tool_code IN ('suno', 'suno_music', 'ofox_gpt_image2')
   OR LOWER(tool_name) LIKE '%suno%'
ORDER BY id;

SELECT
  id,
  vendor_code,
  account_name,
  base_url,
  enabled,
  health_status,
  CHAR_LENGTH(COALESCE(api_key, '')) AS key_len
FROM model_vendor_accounts
WHERE vendor_code IN ('suno', 'suno_music', 'openai', 'openai_gateway', 'ofox', 'openrouter')
ORDER BY id;
"""


def run(ssh: paramiko.SSHClient, command: str, timeout: int = 120) -> tuple[int, str, str]:
    _, stdout, stderr = ssh.exec_command(command, timeout=timeout)
    out = stdout.read().decode("utf-8", errors="replace")
    err = stderr.read().decode("utf-8", errors="replace")
    return stdout.channel.recv_exit_status(), out, err


def main() -> int:
    if not PASSWORD:
        print("DEPLOY_PASSWORD required", file=sys.stderr)
        return 1
    ssh = paramiko.SSHClient()
    ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    ssh.connect(HOST, username="root", password=PASSWORD, timeout=30, allow_agent=False, look_for_keys=False)
    sftp = ssh.open_sftp()
    with sftp.file("/tmp/prod_model_diag.sql", "w") as remote_file:
        remote_file.write(SQL)
    sftp.close()
    code, out, err = run(
        ssh,
        "docker compose -f /root/ai_tool_market/deploy/docker-compose.yml exec -T mysql "
        "mysql -uroot -proot123456 ai_supermarket_v1 < /tmp/prod_model_diag.sql",
    )
    if out:
        print(out.rstrip())
    if err:
        print(err.rstrip(), file=sys.stderr)
    ssh.close()
    return code


if __name__ == "__main__":
    raise SystemExit(main())
