#!/usr/bin/env python3
import os
import paramiko

HOST = os.environ.get("DEPLOY_HOST", "8.134.93.203")
PASSWORD = os.environ.get("DEPLOY_PASSWORD", "")

SQL = (
    "SELECT amc.id, amc.display_name, "
    "CHAR_LENGTH(COALESCE(amc.api_key,'')) AS model_key_len, "
    "CHAR_LENGTH(COALESCE(mva.api_key,'')) AS vendor_key_len, "
    "mva.base_url "
    "FROM agent_model_configs amc "
    "LEFT JOIN model_vendor_accounts mva ON amc.vendor_account_id = mva.id "
    "WHERE amc.display_name LIKE '%gpt-image%' "
    "OR amc.display_name LIKE '%GPT-image%' "
    "OR amc.display_name LIKE '%image2%';"
)


def main() -> int:
    if not PASSWORD:
        print("DEPLOY_PASSWORD required")
        return 1
    ssh = paramiko.SSHClient()
    ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    ssh.connect(HOST, username="root", password=PASSWORD, timeout=30)
    sftp = ssh.open_sftp()
    with sftp.file("/tmp/query_gpt_keys.sql", "w") as f:
        f.write(SQL)
    sftp.close()
    cmd = (
        "docker compose -f /root/ai_tool_market/deploy/docker-compose.yml exec -T mysql "
        "mysql -uroot -proot123456 ai_supermarket_v1 < /tmp/query_gpt_keys.sql"
    )
    _, stdout, stderr = ssh.exec_command(cmd, timeout=60)
    print(stdout.read().decode("utf-8", errors="replace"))
    err = stderr.read().decode("utf-8", errors="replace")
    if err.strip():
        print(err, end="")
    ssh.close()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
