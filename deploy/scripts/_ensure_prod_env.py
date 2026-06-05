#!/usr/bin/env python3
import os
import paramiko

password = os.environ.get("DEPLOY_PASSWORD", "")
host = os.environ.get("DEPLOY_HOST", "8.134.93.203")
if not password:
    raise SystemExit("DEPLOY_PASSWORD required")

ssh = paramiko.SSHClient()
ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
ssh.connect(host, username="root", password=password, timeout=30)
_, out, _ = ssh.exec_command(
    "grep -q '^APP_PRODUCTION_MODE=true' /root/ai_tool_market/.env "
    "|| echo 'APP_PRODUCTION_MODE=true' >> /root/ai_tool_market/.env; "
    "grep APP_PRODUCTION_MODE /root/ai_tool_market/.env",
    timeout=30,
)
print(out.read().decode())
ssh.close()
