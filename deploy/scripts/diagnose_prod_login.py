#!/usr/bin/env python3
"""Diagnose production login 403 and deploy/CD state."""
import json
import os
import urllib.request

import paramiko

password = os.environ.get("DEPLOY_PASSWORD")
if not password:
    raise RuntimeError("DEPLOY_PASSWORD is required")
host = "8.134.93.203"

print("=== External login test ===")
for url in [
    "http://wlcloudai.com/api/v1/auth/login",
    "https://wlcloudai.com/api/v1/auth/login",
    "http://8.134.93.203/api/v1/auth/login",
    "http://www.wlcloudai.com/api/v1/auth/login",
]:
    body = json.dumps({"account": "test", "password": "test"}).encode()
    req = urllib.request.Request(
        url,
        data=body,
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    try:
        with urllib.request.urlopen(req, timeout=30) as resp:
            data = resp.read().decode(errors="replace")
            print(f"{url} -> {resp.status}")
            print(data[:400])
    except urllib.error.HTTPError as e:
        data = e.read().decode(errors="replace")
        print(f"{url} -> HTTP {e.code}")
        print(data[:400])
    except Exception as e:
        print(f"{url} -> ERROR: {e}")

ssh = paramiko.SSHClient()
ssh.load_system_host_keys()
ssh.set_missing_host_key_policy(paramiko.RejectPolicy())
ssh.connect(host, username="root", password=password, timeout=30, allow_agent=False, look_for_keys=False)

cmds = [
    "git -C /root/ai_tool_market log -1 --oneline",
    "cat /root/ai_tool_market/.deploy_revision 2>/dev/null || echo none",
    "grep '^APP_PRODUCTION_MODE' /root/ai_tool_market/.env",
    "docker exec ai-supermarket-user-web printenv APP_PRODUCTION_MODE",
    "docker exec ai-supermarket-user-web sh -c 'test -f /dist-out/index.html && echo dist_ok || echo dist_missing'",
    "docker exec ai-supermarket-user-web sh -c 'wget -qO- http://127.0.0.1:5173/ 2>&1 | head -c 60 || echo port5173_closed'",
    "grep -A2 'user-web:' /root/ai_tool_market/deploy/docker-compose.yml | grep -i APP_PRODUCTION || grep 'APP_PRODUCTION' /root/ai_tool_market/deploy/docker-compose.yml | head -3",
    """curl -s -o /tmp/login.json -w 'local_nginx:%{http_code}\\n' -X POST http://127.0.0.1/api/v1/auth/login -H 'Content-Type: application/json' -d '{"account":"test","password":"test"}'""",
    "head -c 350 /tmp/login.json; echo",
    """docker exec ai-supermarket-nginx wget -qSO- http://user-web:5173/api/v1/auth/login --post-data='{"account":"x","password":"x"}' --header='Content-Type: application/json' --header='Host: wlcloudai.com' 2>&1 | head -6""",
    "docker compose -f /root/ai_tool_market/deploy/docker-compose.yml -f /root/ai_tool_market/deploy/docker-compose.nginx.yml ps --format '{{.Name}} {{.Status}}' | grep -E 'user-web|nginx|backend'",
]
for cmd in cmds:
    print(f"\n=== {cmd[:100]} ===")
    _, stdout, stderr = ssh.exec_command(cmd, timeout=90)
    out = stdout.read().decode(errors="replace")
    err = stderr.read().decode(errors="replace")
    print(out or err)
ssh.close()
