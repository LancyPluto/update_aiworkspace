#!/usr/bin/env python3
import os
from pathlib import Path
import paramiko

ROOT = Path(__file__).resolve().parents[2]
PASSWORD = os.environ.get("DEPLOY_PASSWORD", "")

ssh = paramiko.SSHClient()
ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
ssh.connect("8.134.93.203", username="root", password=PASSWORD, timeout=30)

sftp = ssh.open_sftp()
with sftp.open("/root/ai_tool_market/deploy/docker-compose.yml", "wb") as f:
    f.write((ROOT / "deploy/docker-compose.yml").read_bytes().replace(b"\r\n", b"\n"))
sftp.close()

cmds = [
    "cd /root/ai_tool_market/deploy && docker compose -f docker-compose.yml up -d --force-recreate worker",
    "docker exec ai-supermarket-worker printenv HTTP_PROXY HTTPS_PROXY",
    "docker exec ai-supermarket-worker python -c 'from client.openai_images_client import OpenAIImagesClient; c=OpenAIImagesClient(base_url=\"https://api.ofox.ai/v1\", api_key=\"x\"); print(c.session.trust_env)'",
    "docker exec ai-supermarket-worker curl -s -o /dev/null -w '%{http_code}' --proxy http://host.docker.internal:7890 https://api.ofox.ai/v1",
]
for cmd in cmds:
    print("===", cmd)
    _, stdout, stderr = ssh.exec_command(cmd, timeout=120)
    print(stdout.read().decode())
    err = stderr.read().decode()
    if err.strip():
        print(err)
ssh.close()
