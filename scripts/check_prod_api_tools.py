#!/usr/bin/env python3
import os
import sys

import paramiko

password = os.environ.get("DEPLOY_PASSWORD")
if not password:
    print("DEPLOY_PASSWORD required", file=sys.stderr)
    sys.exit(1)

ssh = paramiko.SSHClient()
ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
ssh.connect("8.134.93.203", username="root", password=password, timeout=30)

cmd = r"""python3 - <<'PY'
import json, urllib.request
base = "http://127.0.0.1:8080"
data = json.loads(urllib.request.urlopen(base + "/api/v1/tools?pageNo=1&pageSize=200").read())["data"]
print("user_api_total", data["total"])
for code in ["ai_comic_drama_agent","enterprise_diagnosis_agent","digital_human_agent","banana_ppt_generator"]:
    hit = next((t for t in data["list"] if t["toolCode"] == code), None)
    if hit:
        print(code, hit.get("status"), hit.get("toolType"), hit.get("categoryCode"))
    else:
        print(code, "NOT_IN_LIST")
PY"""

_, stdout, stderr = ssh.exec_command(cmd, timeout=60)
print(stdout.read().decode("utf-8", errors="replace"))
err = stderr.read().decode("utf-8", errors="replace")
if err.strip():
    print(err, file=sys.stderr)
ssh.close()
