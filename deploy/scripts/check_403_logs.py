#!/usr/bin/env python3
import paramiko

ssh = paramiko.SSHClient()
ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
ssh.connect("8.134.93.203", username="root", password="KeChuangDianAi17728033019", timeout=30, allow_agent=False, look_for_keys=False)

remote = r"""
docker exec ai-supermarket-nginx sh -c 'grep " 403 " /var/log/nginx/access.log | tail -n 8'
echo "--- auth ---"
docker exec ai-supermarket-nginx sh -c 'grep /api/v1/auth /var/log/nginx/access.log | tail -n 8'
echo "--- cors ---"
curl -sk -D- -o /dev/null -X OPTIONS https://127.0.0.1/api/v1/auth/login \
  -H 'Origin: https://wlcloudai.com' \
  -H 'Access-Control-Request-Method: POST' \
  -H 'Access-Control-Request-Headers: content-type' 2>&1 | head -n 15
echo "--- http post redirect ---"
curl -s -D- -o /dev/null -X POST http://wlcloudai.com/api/v1/auth/login \
  -H 'Content-Type: application/json' \
  -H 'Origin: http://wlcloudai.com' \
  -d '{"account":"t","password":"t"}' 2>&1 | head -n 12
"""
_, stdout, stderr = ssh.exec_command(remote, timeout=120)
print(stdout.read().decode(errors="replace"))
print(stderr.read().decode(errors="replace"))
ssh.close()
