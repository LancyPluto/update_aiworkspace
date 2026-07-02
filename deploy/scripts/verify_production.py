#!/usr/bin/env python3
"""Quick production verification over SSH."""
import os
import sys

import paramiko

host = os.environ.get("DEPLOY_HOST", "8.134.93.203")
user = os.environ.get("DEPLOY_USER", "root")
password = os.environ.get("DEPLOY_PASSWORD")
if not password:
    print("DEPLOY_PASSWORD required", file=sys.stderr)
    raise SystemExit(1)

ssh = paramiko.SSHClient()
ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
ssh.connect(host, username=user, password=password, timeout=30, allow_agent=False, look_for_keys=False)

cmds = [
    "git -C /root/ai_tool_market log -1 --oneline",
    "cat /root/ai_tool_market/.deploy_revision",
    "docker logs ai-supermarket-admin-frontend --tail 30 2>&1",
    "curl -sf -o /dev/null -w 'admin:%{http_code}\\n' -L --max-time 30 http://127.0.0.1/admin || echo admin:fail",
]
for cmd in cmds:
    print(f"\n$ {cmd}")
    _, stdout, stderr = ssh.exec_command(cmd, timeout=60)
    out = stdout.read().decode()
    err = stderr.read().decode()
    if out:
        sys.stdout.buffer.write(out.encode("utf-8", errors="replace"))
        if not out.endswith("\n"):
            sys.stdout.write("\n")
    if err:
        sys.stderr.buffer.write(err.encode("utf-8", errors="replace"))
        if not err.endswith("\n"):
            sys.stderr.write("\n")

ssh.close()
