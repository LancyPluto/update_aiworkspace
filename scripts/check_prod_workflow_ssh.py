#!/usr/bin/env python3
import json
import os
import sys

import paramiko


def main() -> int:
    password = os.environ.get("DEPLOY_PASSWORD")
    if not password:
        print("DEPLOY_PASSWORD required", file=sys.stderr)
        return 1
    ssh = paramiko.SSHClient()
    ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    ssh.connect("8.134.93.203", username="root", password=password, timeout=30)
    cmd = r"""
python3 - <<'PY'
import json, urllib.request
base = "http://127.0.0.1:8080"
login = json.loads(urllib.request.urlopen(urllib.request.Request(
    base + "/api/admin/v1/auth/login",
    data=json.dumps({"account":"admin","password":"123456"}).encode(),
    headers={"Content-Type":"application/json"}, method="POST")).read())
token = login["data"]["accessToken"]
tools = json.loads(urllib.request.urlopen(urllib.request.Request(
    base + "/api/admin/v1/tools?pageNo=1&pageSize=100",
    headers={"Authorization": "Bearer " + token})).read())["data"]["items"]
tool_id = next(t["id"] for t in tools if t["toolCode"] == "ai_comic_drama_agent")
wf = json.loads(urllib.request.urlopen(urllib.request.Request(
    base + f"/api/admin/v1/tools/{tool_id}/workflow",
    headers={"Authorization": "Bearer " + token})).read())["data"]
for node in json.loads(wf["nodesJson"]):
    title = node.get("data", {}).get("title", "")
    if "意见" in title or "è" in title:
        print(node["id"], "=>", title)
PY
"""
    _, stdout, stderr = ssh.exec_command(cmd, timeout=60)
    print(stdout.read().decode("utf-8", errors="replace"))
    err = stderr.read().decode("utf-8", errors="replace")
    if err.strip():
        print(err, file=sys.stderr)
    ssh.close()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
