#!/usr/bin/env python3
import json
import subprocess

cmd = [
    "docker", "exec", "ai-supermarket-mysql",
    "mysql", "-uroot", "-proot123456", "--default-character-set=utf8mb4",
    "-N", "-B", "-e",
    "SELECT tool_id, nodes_json FROM tool_workflows WHERE nodes_json LIKE '%user-input-script%' LIMIT 3;",
    "ai_supermarket_v1",
]
raw = subprocess.check_output(cmd, stderr=subprocess.DEVNULL).decode("utf-8", errors="replace")
for line in raw.splitlines():
    if not line.strip():
        continue
    tool_id, nodes_json = line.split("\t", 1)
    nodes = json.loads(nodes_json)
    for node in nodes:
        if node.get("id") == "user-input-script":
            title = node.get("data", {}).get("title", "")
            print(f"tool_id={tool_id} title={title!r}")
