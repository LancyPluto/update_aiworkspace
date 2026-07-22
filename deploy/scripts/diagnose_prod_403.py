#!/usr/bin/env python3
"""Find 403 sources on production login flow."""
import json
import os
import ssl
import urllib.error
import urllib.request

import paramiko

password = os.environ.get("DEPLOY_PASSWORD")
if not password:
    raise RuntimeError("DEPLOY_PASSWORD is required")


def post(url: str, body: dict) -> tuple[int, str]:
    data = json.dumps(body).encode()
    req = urllib.request.Request(url, data=data, headers={"Content-Type": "application/json"}, method="POST")
    ctx = ssl.create_default_context()
    ctx.check_hostname = False
    ctx.verify_mode = ssl.CERT_NONE
    try:
        with urllib.request.urlopen(req, timeout=30, context=ctx) as resp:
            return resp.status, resp.read().decode(errors="replace")[:500]
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode(errors="replace")[:500]


def get(url: str) -> tuple[int, str]:
    ctx = ssl.create_default_context()
    ctx.check_hostname = False
    ctx.verify_mode = ssl.CERT_NONE
    try:
        with urllib.request.urlopen(url, timeout=30, context=ctx) as resp:
            return resp.status, resp.read().decode(errors="replace")[:300]
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode(errors="replace")[:300]


print("=== API login probes ===")
for label, url in [
    ("https_domain", "https://wlcloudai.com/api/v1/auth/login"),
    ("http_ip", "http://8.134.93.203/api/v1/auth/login"),
    ("https_www", "https://www.wlcloudai.com/api/v1/auth/login"),
]:
    try:
        code, body = post(url, {"account": "test", "password": "test"})
        print(f"{label}: {code} {body[:180]}")
    except Exception as e:
        print(f"{label}: ERROR {e}")

print("\n=== Page load probes ===")
for label, url in [
    ("https_home", "https://wlcloudai.com/"),
    ("http_ip_home", "http://8.134.93.203/"),
    ("https_www_home", "https://www.wlcloudai.com/"),
]:
    try:
        code, body = get(url)
        print(f"{label}: {code} len={len(body)}")
    except Exception as e:
        print(f"{label}: ERROR {e}")

ssh = paramiko.SSHClient()
ssh.load_system_host_keys()
ssh.set_missing_host_key_policy(paramiko.RejectPolicy())
ssh.connect("8.134.93.203", username="root", password=password, timeout=30, allow_agent=False, look_for_keys=False)

cmds = [
    "grep -E 'VITE_API|CORS' /root/ai_tool_market/.env",
    "docker exec ai-supermarket-nginx cat /usr/share/nginx/user-web/index.html",
    """ASSET=$(docker exec ai-supermarket-nginx grep -oE 'assets/index-[^"]+\\.js' /usr/share/nginx/user-web/index.html | head -1); echo asset=$ASSET; curl -s -o /dev/null -w 'asset:%{http_code}\\n' http://127.0.0.1/$ASSET; curl -sk -o /dev/null -w 'asset_https:%{http_code}\\n' https://127.0.0.1/$ASSET""",
    """docker exec ai-supermarket-nginx sh -c 'tail -2000 /var/log/nginx/access.log | grep \" 403 \" | tail -15'""",
    """curl -s -o /dev/null -w 'opt:%{http_code}\\n' -X OPTIONS http://127.0.0.1/api/v1/auth/login -H 'Origin: https://wlcloudai.com' -H 'Access-Control-Request-Method: POST' -H 'Access-Control-Request-Headers: content-type'""",
    """curl -sk -o /dev/null -w 'opt_https:%{http_code}\\n' -X OPTIONS https://127.0.0.1/api/v1/auth/login -H 'Origin: https://wlcloudai.com' -H 'Access-Control-Request-Method: POST' -H 'Access-Control-Request-Headers: content-type'""",
    """JS=$(docker exec ai-supermarket-nginx grep -oE 'assets/index-[^"]+\\.js' /usr/share/nginx/user-web/index.html | head -1); docker exec ai-supermarket-nginx sh -c "grep -oE 'http[s]?://[^\\\"']+' /usr/share/nginx/user-web/$JS | head -20" """,
]
for cmd in cmds:
    print(f"\n=== {cmd[:100]} ===")
    _, stdout, _ = ssh.exec_command(cmd, timeout=120)
    print(stdout.read().decode(errors="replace")[:2500])
ssh.close()
