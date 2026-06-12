#!/usr/bin/env python3
import json
import subprocess

cmd = [
    "docker", "exec", "ai-supermarket-mysql",
    "mysql", "-uroot", "-proot123456", "--default-character-set=utf8mb4",
    "-N", "-B", "-e",
    "SELECT HEX(SUBSTRING(nodes_json, LOCATE('user-input-script', nodes_json), 200)) FROM tool_workflows WHERE tool_id=83;",
    "ai_supermarket_v1",
]
raw = subprocess.check_output(cmd, stderr=subprocess.DEVNULL).decode("utf-8").strip()
print("hex snippet:", raw[:300])

cmd2 = [
    "docker", "exec", "ai-supermarket-mysql",
    "mysql", "-uroot", "-proot123456", "--default-character-set=utf8mb4",
    "-N", "-B", "-e",
    "SELECT nodes_json FROM tool_workflows WHERE tool_id=83 LIMIT 1;",
    "ai_supermarket_v1",
]
nodes_json = subprocess.check_output(cmd2, stderr=subprocess.DEVNULL).decode("utf-8").strip()
nodes = json.loads(nodes_json)
title = next(n["data"]["title"] for n in nodes if n["id"] == "user-input-script")
print("title repr:", repr(title))
for i, ch in enumerate(title):
    print(i, repr(ch), hex(ord(ch)))
