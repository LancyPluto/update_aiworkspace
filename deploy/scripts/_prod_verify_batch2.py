#!/usr/bin/env python3
import os
import paramiko

password = os.environ.get("DEPLOY_PASSWORD")
if not password:
    raise RuntimeError("DEPLOY_PASSWORD is required")
ssh = paramiko.SSHClient()
ssh.load_system_host_keys()
ssh.set_missing_host_key_policy(paramiko.RejectPolicy())
ssh.connect("8.134.93.203", username="root", password=password, timeout=30)

cmds = [
    "docker exec ai-supermarket-worker printenv HTTP_PROXY",
    "docker exec ai-supermarket-worker python -c \"import os; from client.openai_images_client import OpenAIImagesClient; c=OpenAIImagesClient(base_url='https://api.ofox.ai/v1', api_key='x'); print('trust_env', c.session.trust_env)\"",
    "ls /root/ai_tool_market/data/generated-media/tool-covers/ | wc -l",
    "curl -s -o /dev/null -w 'image28:%{http_code}\\n' https://wlcloudai.com/generated/images/28/image-1.png",
    "curl -s -o /dev/null -w 'admin:%{http_code}\\n' https://wlcloudai.com/admin",
]

for cmd in cmds:
    print('===', cmd)
    _, stdout, stderr = ssh.exec_command(cmd, timeout=120)
    print(stdout.read().decode())
    err = stderr.read().decode()
    if err.strip():
        print('ERR', err)

ssh.close()
