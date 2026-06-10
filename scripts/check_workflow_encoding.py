#!/usr/bin/env python3
import json
import subprocess

raw = subprocess.check_output(
    [
        "docker", "exec", "ai-supermarket-mysql",
        "mysql", "-uroot", "-proot123456", "ai_supermarket_v1", "-N",
        "-e",
        "SELECT nodes_json FROM tool_workflows w JOIN ai_tools t ON t.id=w.tool_id "
        "WHERE t.tool_code='ai_comic_drama_agent'",
    ]
)
nodes = json.loads(raw.decode("utf-8"))
lines = []
for node in nodes:
    data = node.get("data", {})
    lines.append(f"{node['id']}: title={data.get('title')}")
    for slot in data.get("inputSlots") or []:
        lines.append(f"  in {slot.get('name')}: {slot.get('label')}")
    for slot in data.get("outputSlots") or []:
        lines.append(f"  out {slot.get('name')}: {slot.get('label')}")
out = __file__.replace("check_workflow_encoding.py", "_wf_check.txt")
open(out, "w", encoding="utf-8").write("\n".join(lines))
print("written", out)
for line in lines[:8]:
    print(line)
