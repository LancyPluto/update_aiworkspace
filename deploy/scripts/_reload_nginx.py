#!/usr/bin/env python3
import os
import paramiko

password = os.environ.get("DEPLOY_PASSWORD", "")
host = os.environ.get("DEPLOY_HOST", "8.134.93.203")
local_conf = os.path.join(os.path.dirname(__file__), "..", "nginx", "default.conf")
remote_conf = "/root/ai_tool_market/deploy/nginx/default.conf"

ssh = paramiko.SSHClient()
ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
ssh.connect(host, username="root", password=password, timeout=30)
sftp = ssh.open_sftp()
sftp.put(local_conf, remote_conf)
sftp.close()
cmds = [
    "docker exec ai-supermarket-nginx nginx -t",
    "docker exec ai-supermarket-nginx nginx -s reload",
    "curl -sI --max-time 10 -L http://127.0.0.1/admin 2>&1 | head -25",
    "curl -sI --max-time 10 http://127.0.0.1/admin/ 2>&1 | head -15",
]
for cmd in cmds:
    print(f"\n=== {cmd} ===")
    _, out, err = ssh.exec_command(cmd, timeout=30)
    data = out.read().decode("utf-8", errors="replace")
    print(data or err.read().decode("utf-8", errors="replace"))
ssh.close()
