#!/usr/bin/env python3
import os
import paramiko

password = os.environ.get("DEPLOY_PASSWORD", "")
host = os.environ.get("DEPLOY_HOST", "8.134.93.203")
ssh = paramiko.SSHClient()
ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
ssh.connect(host, username="root", password=password, timeout=30, allow_agent=False, look_for_keys=False)
for cmd in [
    "cd /root/ai_tool_market/deploy && docker compose -f docker-compose.yml -f docker-compose.nginx.yml ps",
    "curl -s -o /dev/null -w 'nginx:%{http_code}\\n' http://127.0.0.1/",
    "curl -s -o /dev/null -w 'admin:%{http_code}\\n' http://127.0.0.1/admin/",
    "curl -s -o /dev/null -w 'backend:%{http_code}\\n' http://127.0.0.1:8080/actuator/health",
    "tail -5 /tmp/deploy_build.log 2>/dev/null || echo 'no build log'",
]:
    _, out, _ = ssh.exec_command(cmd, timeout=60)
    print(out.read().decode(errors="replace"))
ssh.close()
