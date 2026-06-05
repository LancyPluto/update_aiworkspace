#!/usr/bin/env python3
import os
import paramiko

password = os.environ.get("DEPLOY_PASSWORD", "")
host = os.environ.get("DEPLOY_HOST", "8.134.93.203")
ssh = paramiko.SSHClient()
ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
ssh.connect(host, username="root", password=password, timeout=30)
_, out, _ = ssh.exec_command("tail -25 /tmp/deploy_build.log 2>/dev/null; echo '---'; docker events --since 2m --until 0s 2>/dev/null | tail -8 || true", timeout=20)
print(out.read().decode(errors="replace"))
ssh.close()
