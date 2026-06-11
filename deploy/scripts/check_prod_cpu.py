#!/usr/bin/env python3
"""Check production server CPU/memory load."""
import os
import paramiko

host = os.environ.get("DEPLOY_HOST", "8.134.93.203")
user = os.environ.get("DEPLOY_USER", "root")
password = os.environ.get("DEPLOY_PASSWORD")
if not password:
    raise SystemExit("DEPLOY_PASSWORD required")

ssh = paramiko.SSHClient()
ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
ssh.connect(host, username=user, password=password, timeout=30, allow_agent=False, look_for_keys=False)

cmds = [
    "uptime",
    "nproc",
    'grep -m1 "model name" /proc/cpuinfo',
    "free -h",
    "cat /proc/loadavg",
    "top -bn1 | head -20",
    'docker stats --no-stream --format "table {{.Name}}\t{{.CPUPerc}}\t{{.MemUsage}}\t{{.MemPerc}}" 2>/dev/null',
    "ps aux --sort=-%cpu | head -12",
]
for cmd in cmds:
    print(f"\n=== {cmd} ===")
    _, stdout, stderr = ssh.exec_command(cmd, timeout=60)
    out = stdout.read().decode(errors="replace")
    err = stderr.read().decode(errors="replace")
    print(out or err)
ssh.close()
