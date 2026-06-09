#!/usr/bin/env python3
import os
import paramiko

PASSWORD = os.environ.get("DEPLOY_PASSWORD", "KeChuangDianAi17728033019")
ssh = paramiko.SSHClient()
ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
ssh.connect("8.134.93.203", username="root", password=PASSWORD, timeout=30)

script = r'''
import requests, os, time
proxies = {
  'http': 'http://host.docker.internal:7890',
  'https': 'http://host.docker.internal:7890',
}
for label, url in [
  ('GET /v1', 'https://api.ofox.ai/v1'),
  ('GET google', 'https://www.google.com'),
]:
  t0 = time.time()
  try:
    r = requests.get(url, proxies=proxies, timeout=30)
    print(label, 'OK', r.status_code, round(time.time()-t0,2), 's')
  except Exception as e:
    print(label, 'FAIL', round(time.time()-t0,2), 's', type(e).__name__, str(e)[:200])
'''

cmd = f"docker exec ai-supermarket-worker python -c {script!r}"
_, stdout, stderr = ssh.exec_command(cmd, timeout=90)
print(stdout.read().decode())
print(stderr.read().decode())

for cmd in [
    "docker ps -a --format '{{.Names}} {{.Status}}' | grep -i mihomo || true",
    "curl -s -o /dev/null -w 'host7890:%{http_code}\\n' --max-time 10 --proxy http://127.0.0.1:7890 https://api.ofox.ai/v1",
    "ss -lntp | grep 7890 || netstat -lntp | grep 7890 || true",
]:
    print('===', cmd)
    _, stdout, stderr = ssh.exec_command(cmd, timeout=30)
    print(stdout.read().decode())
    print(stderr.read().decode())

ssh.close()
