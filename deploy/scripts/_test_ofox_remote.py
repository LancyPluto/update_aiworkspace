#!/usr/bin/env python3
import os
import paramiko

HOST = os.environ.get("DEPLOY_HOST", "8.134.93.203")
PASSWORD = os.environ.get("DEPLOY_PASSWORD", "")

REMOTE_PY = r"""
import os, urllib.request
print("HTTP_PROXY", os.environ.get("HTTP_PROXY", ""))
try:
    r = urllib.request.urlopen("https://api.ofox.ai/v1/models", timeout=25)
    print("worker_api_ofox", r.status)
except Exception as e:
    print("worker_api_ofox_error", e)
"""

ssh = paramiko.SSHClient()
ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
ssh.connect(HOST, username="root", password=PASSWORD, timeout=30)
sftp = ssh.open_sftp()
with sftp.open("/tmp/test_ofox.py", "w") as f:
    f.write(REMOTE_PY)
sftp.close()
_, stdout, stderr = ssh.exec_command(
    "docker cp /tmp/test_ofox.py ai-supermarket-worker:/tmp/test_ofox.py && "
    "docker exec ai-supermarket-worker python /tmp/test_ofox.py",
    timeout=60,
)
print(stdout.read().decode())
print(stderr.read().decode())
ssh.close()
