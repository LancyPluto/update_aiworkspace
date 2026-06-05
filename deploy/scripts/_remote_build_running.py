#!/usr/bin/env python3
import os
import paramiko

password = os.environ.get("DEPLOY_PASSWORD", "")
host = os.environ.get("DEPLOY_HOST", "8.134.93.203")
ssh = paramiko.SSHClient()
ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
ssh.connect(host, username="root", password=password, timeout=30)
_, out, _ = ssh.exec_command(
    "pgrep -a docker 2>/dev/null | head -3; "
    "pgrep -a 'compose|buildx' 2>/dev/null | head -5; "
    "test -f /tmp/deploy_build.done && echo BUILD_DONE || echo BUILD_NOT_DONE; "
    "wc -l /tmp/deploy_build.log 2>/dev/null || echo no_log",
    timeout=15,
)
print(out.read().decode(errors="replace"))
ssh.close()
