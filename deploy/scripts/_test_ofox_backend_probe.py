#!/usr/bin/env python3
"""Simulate backend media gateway probe with HTTP_PROXY on production."""
import os
import paramiko
import urllib.request

HOST = os.environ.get("DEPLOY_HOST", "8.134.93.203")
PASSWORD = os.environ.get("DEPLOY_PASSWORD", "")

ssh = paramiko.SSHClient()
ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
ssh.connect(HOST, username="root", password=PASSWORD, timeout=30)
_, stdout, _ = ssh.exec_command("docker exec ai-supermarket-backend printenv HTTP_PROXY HTTPS_PROXY", timeout=30)
print(stdout.read().decode())

REMOTE = r"""
import os, urllib.request
proxy = os.environ.get("HTTP_PROXY") or os.environ.get("HTTPS_PROXY")
print("proxy", proxy)
handlers = []
if proxy:
    handlers.append(urllib.request.ProxyHandler({"http": proxy, "https": proxy}))
opener = urllib.request.build_opener(*handlers)
req = urllib.request.Request("https://api.ofox.ai/v1/models", headers={"Accept": "application/json", "Authorization": "Bearer probe"})
resp = opener.open(req, timeout=20)
print("status", resp.status)
print(resp.read(120).decode("utf-8", errors="replace"))
"""
sftp = ssh.open_sftp()
with sftp.open("/tmp/probe_ofox.py", "w") as f:
    f.write(REMOTE)
sftp.close()
_, stdout, stderr = ssh.exec_command(
    "docker cp /tmp/probe_ofox.py ai-supermarket-backend:/tmp/probe_ofox.py && "
    "docker exec ai-supermarket-backend java -version >/dev/null 2>&1; "
    "docker exec -e HTTP_PROXY=http://host.docker.internal:7890 -e HTTPS_PROXY=http://host.docker.internal:7890 "
    "ai-supermarket-backend sh -c 'command -v python3 >/dev/null && python3 /tmp/probe_ofox.py' || "
    "echo no_python_in_backend",
    timeout=60,
)
print(stdout.read().decode())
print(stderr.read().decode())
ssh.close()
