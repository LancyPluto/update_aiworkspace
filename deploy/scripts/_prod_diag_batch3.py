#!/usr/bin/env python3
import os
import paramiko

PASSWORD = os.environ.get("DEPLOY_PASSWORD", "KeChuangDianAi17728033019")
ssh = paramiko.SSHClient()
ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
ssh.connect("8.134.93.203", username="root", password=PASSWORD, timeout=30)

cmds = [
    "docker logs ai-supermarket-worker --tail 120 2>&1 | grep -iE 'MODEL_CALL|ofox|gpt.image|error|failed|taskId|trust_env|proxy' | tail -40",
    "docker exec ai-supermarket-worker printenv HTTP_PROXY HTTPS_PROXY NO_PROXY 2>/dev/null || true",
    "docker exec ai-supermarket-worker python -c 'from client.openai_images_client import OpenAIImagesClient; c=OpenAIImagesClient(base_url=\"https://api.ofox.ai/v1\", api_key=\"x\"); print(\"trust_env\", c.session.trust_env, \"proxies\", c.session.proxies)'",
    "docker exec ai-supermarket-backend printenv HTTP_PROXY HTTPS_PROXY 2>/dev/null || true",
    "ls -la /root/ai_tool_market/data/generated-media/tool-covers/ 2>/dev/null | head -20",
    "ls -la /root/ai_tool_market/data/generated-media/video/ 2>/dev/null | head -10",
    "find /root/ai_tool_market/data/generated-media -name '*GPT-image2*' -o -name '*Suno*' -o -name '*kling-v3-text*' 2>/dev/null | head -20",
    "docker exec ai-supermarket-mysql mysql -uroot -proot123456 ai_supermarket_v1 -e \"SELECT id,tool_code,status,error_code,LEFT(error_message,200) AS err FROM task ORDER BY id DESC LIMIT 8;\" 2>/dev/null",
    "docker exec ai-supermarket-mysql mysql -uroot -proot123456 ai_supermarket_v1 -e \"SELECT id,tool_code,cover_url FROM tool WHERE cover_url IS NOT NULL AND cover_url <> '' LIMIT 15;\" 2>/dev/null",
]

for cmd in cmds:
    print("\n===", cmd[:120], "===")
    _, stdout, stderr = ssh.exec_command(cmd, timeout=120)
    out = stdout.read().decode(errors="replace")
    err = stderr.read().decode(errors="replace")
    if out.strip():
        print(out.rstrip())
    if err.strip():
        print("STDERR:", err.rstrip())

ssh.close()
