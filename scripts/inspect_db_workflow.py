#!/usr/bin/env python3
import subprocess

cmd = [
    "docker", "exec", "ai-supermarket-mysql",
    "mysql", "-uroot", "-proot123456", "--default-character-set=utf8mb4",
    "-N", "-B", "-e",
    "SELECT LEFT(nodes_json, 200), LENGTH(nodes_json) FROM tool_workflows WHERE tool_id=83;",
    "ai_supermarket_v1",
]
raw = subprocess.check_output(cmd, stderr=subprocess.DEVNULL).decode("utf-8")
print(raw)
