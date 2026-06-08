#!/usr/bin/env python3
import os
import paramiko

password = os.environ.get("DEPLOY_PASSWORD", "KeChuangDianAi17728033019")
ssh = paramiko.SSHClient()
ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
ssh.connect("8.134.93.203", username="root", password=password, timeout=30)

cmds = [
    "docker exec ai-supermarket-mysql mysql -uroot -proot123456 ai_supermarket_v1 -e \"SELECT id,username,user_type,status FROM user WHERE user_type='ADMIN' LIMIT 5;\"",
    "docker exec ai-supermarket-worker printenv HTTP_PROXY HTTPS_PROXY NO_PROXY 2>/dev/null || true",
    "docker logs ai-supermarket-worker --tail 80 2>&1 | grep -iE 'MODEL_CALL|ofox|gpt.image|error|failed' | tail -25",
    "docker exec ai-supermarket-mysql mysql -uroot -proot123456 ai_supermarket_v1 -e \"SELECT id,tool_code,status,error_code,LEFT(error_message,120) FROM task ORDER BY id DESC LIMIT 5;\"",
    "ls /root/ai_tool_market/data/generated-media/tool-covers/ | wc -l",
    "docker exec ai-supermarket-mysql mysql -uroot -proot123456 ai_supermarket_v1 -e \"SELECT COUNT(*) AS tools_with_cover FROM tool WHERE cover_url LIKE '/generated/tool-covers/%';\"",
]

for cmd in cmds:
    print("===", cmd[:80], "...")
    _, stdout, stderr = ssh.exec_command(cmd, timeout=60)
    out = stdout.read().decode()
    err = stderr.read().decode()
    if out:
        print(out)
    if err:
        print("STDERR:", err)

ssh.close()
