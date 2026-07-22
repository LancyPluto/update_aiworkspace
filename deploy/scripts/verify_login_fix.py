#!/usr/bin/env python3
import json
import os
import ssl
import urllib.error
import urllib.request

import paramiko

password = os.environ.get("DEPLOY_PASSWORD")
if not password:
    raise RuntimeError("DEPLOY_PASSWORD is required")


def post(url: str) -> tuple[int, str]:
    data = json.dumps({"account": "test", "password": "test"}).encode()
    req = urllib.request.Request(url, data=data, headers={"Content-Type": "application/json"}, method="POST")
    ctx = ssl.create_default_context()
    ctx.check_hostname = False
    ctx.verify_mode = ssl.CERT_NONE
    try:
        with urllib.request.urlopen(req, timeout=30, context=ctx) as r:
            return r.status, r.read().decode(errors="replace")[:200]
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode(errors="replace")[:200]


def head(url: str) -> int:
    req = urllib.request.Request(url, method="HEAD")
    ctx = ssl.create_default_context()
    ctx.check_hostname = False
    ctx.verify_mode = ssl.CERT_NONE
    try:
        with urllib.request.urlopen(req, timeout=20, context=ctx) as r:
            return r.status
    except urllib.error.HTTPError as e:
        return e.code


print("=== External checks ===")
for label, url in [
    ("http_home", "http://wlcloudai.com/"),
    ("http_login", "http://wlcloudai.com/api/v1/auth/login"),
    ("https_login", "https://wlcloudai.com/api/v1/auth/login"),
    ("ip_login", "http://8.134.93.203/api/v1/auth/login"),
]:
    if "login" in label:
        code, body = post(url)
        print(f"{label}: {code} {body[:120]}")
    else:
        req = urllib.request.Request(url)
        try:
            with urllib.request.urlopen(req, timeout=20) as r:
                print(f"{label}: {r.status} final={r.url}")
        except urllib.error.HTTPError as e:
            print(f"{label}: {e.code} Location={e.headers.get('Location')}")

ssh = paramiko.SSHClient()
ssh.load_system_host_keys()
ssh.set_missing_host_key_policy(paramiko.RejectPolicy())
ssh.connect("8.134.93.203", username="root", password=password, timeout=30, allow_agent=False, look_for_keys=False)
_, o, _ = ssh.exec_command(
    "grep CORS_ALLOWED /root/ai_tool_market/.env; "
    "docker exec ai-supermarket-backend printenv CORS_ALLOWED_ORIGINS; "
    "curl -sk -o /dev/null -w 'cors_opt:%{http_code}\\n' -X OPTIONS https://127.0.0.1/api/v1/auth/login "
    "-H 'Origin: https://wlcloudai.com' -H 'Access-Control-Request-Method: POST' -H 'Access-Control-Request-Headers: content-type'",
    timeout=90,
)
print("\n=== Production env ===")
print(o.read().decode(errors="replace"))
ssh.close()
