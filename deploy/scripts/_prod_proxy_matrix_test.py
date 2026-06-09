#!/usr/bin/env python3
import os, paramiko, base64
PASSWORD = os.environ.get("DEPLOY_PASSWORD", "KeChuangDianAi17728033019")
test_py = r'''import requests, time
for proxy in [
    "http://host.docker.internal:7890",
    "http://172.17.0.1:7890",
    "http://8.134.93.203:7890",
]:
    proxies = {"http": proxy, "https": proxy}
    t0 = time.time()
    try:
        r = requests.get("https://api.ofox.ai/v1", proxies=proxies, timeout=30)
        print(proxy, "GET", r.status_code, round(time.time()-t0, 2))
    except Exception as e:
        print(proxy, "GET FAIL", round(time.time()-t0, 2), type(e).__name__, str(e)[:120])
    t0 = time.time()
    try:
        r = requests.post(
            "https://api.ofox.ai/v1/images/generations",
            proxies=proxies,
            headers={"Authorization": "Bearer sk-test", "Content-Type": "application/json"},
            json={"model": "openai/gpt-image-2", "prompt": "test", "n": 1, "size": "1024x1024"},
            timeout=(10, 90),
        )
        print(proxy, "POST", r.status_code, round(time.time()-t0, 2), r.text[:80])
    except Exception as e:
        print(proxy, "POST FAIL", round(time.time()-t0, 2), type(e).__name__, str(e)[:160])
'''
ssh = paramiko.SSHClient()
ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
ssh.connect("8.134.93.203", username="root", password=PASSWORD, timeout=30)
sftp = ssh.open_sftp()
with sftp.open("/tmp/proxy_test.py", "w") as f:
    f.write(test_py)
sftp.close()
for cmd in [
    "docker exec ai-supermarket-worker ip route | head -3",
    "docker cp /tmp/proxy_test.py ai-supermarket-worker:/tmp/proxy_test.py",
    "docker exec ai-supermarket-worker python /tmp/proxy_test.py",
]:
    print("===", cmd)
    _, stdout, stderr = ssh.exec_command(cmd, timeout=180)
    print(stdout.read().decode())
    err = stderr.read().decode()
    if err.strip():
        print("ERR:", err)
ssh.close()
