#!/usr/bin/env python3
import os
import paramiko

password = os.environ.get("DEPLOY_PASSWORD", "")
host = os.environ.get("DEPLOY_HOST", "8.134.93.203")
if not password:
    raise SystemExit("DEPLOY_PASSWORD required")

cmds = [
    "curl -sI --max-time 15 http://127.0.0.1/admin/ 2>&1 | head -20",
    "curl -sI --max-time 15 http://127.0.0.1:5174/ 2>&1 | head -15",
    "curl -sI --max-time 15 -H 'Host: wlcloudai.com' http://127.0.0.1/admin/ 2>&1 | head -20",
    "cd /root/ai_tool_market/deploy && docker compose -f docker-compose.yml -f docker-compose.nginx.yml ps admin-frontend nginx 2>&1",
    "docker logs --tail 30 ai-supermarket-admin-frontend 2>&1",
    "docker logs --tail 15 ai-supermarket-nginx 2>&1",
    "ss -tlnp | grep -E ':80|:443|:5174' || netstat -tlnp 2>/dev/null | grep -E ':80|:443|:5174'",
    "grep -r ssl /etc/nginx 2>/dev/null | head -5; ls /etc/nginx/sites-enabled 2>/dev/null; systemctl is-active nginx 2>/dev/null",
]

ssh = paramiko.SSHClient()
ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
ssh.connect(host, username="root", password=password, timeout=30)
for cmd in cmds:
    print(f"\n=== {cmd[:80]} ===")
    _, out, err = ssh.exec_command(cmd, timeout=60)
    text = out.read().decode("utf-8", errors="replace")
    err_text = err.read().decode("utf-8", errors="replace")
    print(text or err_text or "(empty)")
ssh.close()
