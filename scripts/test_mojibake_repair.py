#!/usr/bin/env python3
import json
import subprocess

cmd = [
    "docker", "exec", "ai-supermarket-mysql",
    "mysql", "-uroot", "-proot123456", "--default-character-set=utf8mb4",
    "-N", "-B", "-e",
    "SELECT nodes_json FROM tool_workflows WHERE tool_id=83 LIMIT 1;",
    "ai_supermarket_v1",
]
raw = subprocess.check_output(cmd, stderr=subprocess.DEVNULL).decode("utf-8")
nodes_json = raw.strip().split("\t", 1)[-1]
nodes = json.loads(nodes_json)
for node in nodes:
    if node.get("id") == "user-input-script":
        title = node["data"]["title"]
        print("raw:", repr(title))
        print("has è:", "è" in title)
        try:
            repaired = title.encode("latin1").decode("utf-8")
            print("repaired:", repaired)
        except Exception as e:
            print("repair failed:", e)
