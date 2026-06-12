#!/usr/bin/env python3
import json
import urllib.request

base = "http://localhost:8080"
login_req = urllib.request.Request(
    f"{base}/api/admin/v1/auth/login",
    data=json.dumps({"account": "admin", "password": "123456"}).encode(),
    headers={"Content-Type": "application/json"},
    method="POST",
)
with urllib.request.urlopen(login_req) as resp:
    token = json.load(resp)["data"]["accessToken"]

wf_req = urllib.request.Request(
    f"{base}/api/admin/v1/tools/83/workflow",
    headers={"Authorization": f"Bearer {token}"},
)
with urllib.request.urlopen(wf_req) as resp:
    data = json.load(resp)["data"]
    nodes = json.loads(data["nodesJson"])
    for n in nodes:
        if n.get("id") == "user-input-script":
            print("API title:", repr(n.get("data", {}).get("title")))
