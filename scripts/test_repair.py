import json

import requests
import subprocess

token = requests.post(
    "http://127.0.0.1/api/v1/auth/login",
    json={"account": "admin", "password": "123456"},
).json()["data"]["accessToken"]
tool_id = subprocess.check_output(
    [
        "docker", "exec", "ai-supermarket-mysql",
        "mysql", "-uroot", "-proot123456", "ai_supermarket_v1", "-N",
        "-e", "SELECT id FROM ai_tools WHERE tool_code='ai_comic_drama_agent'",
    ]
).decode().strip()
workflow = requests.get(
    f"http://127.0.0.1/api/admin/v1/tools/{tool_id}/workflow",
    headers={"Authorization": f"Bearer {token}"},
).json()
nodes = json.loads(workflow["data"]["nodesJson"])
open("scripts/_api_wf3.txt", "w", encoding="utf-8").write(
    "\n".join(f"{n['id']}: {n['data'].get('title')}" for n in nodes)
)
title = nodes[1]["data"]["title"]
lines = [repr(title), str([hex(ord(c)) for c in title])]
try:
    repaired = title.encode("latin-1").decode("utf-8")
    lines.append("latin1 repair: " + repaired)
except Exception as exc:
    lines.append("latin1 repair failed: " + str(exc))

try:
    repaired2 = bytes(ord(c) & 0xFF for c in title).decode("utf-8")
    lines.append("mask repair: " + repaired2)
except Exception as exc:
    lines.append("mask repair failed: " + str(exc))

open("scripts/_repair_test.txt", "w", encoding="utf-8").write("\n".join(lines))
