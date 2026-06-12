#!/usr/bin/env python3
import json
import os
import subprocess
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
    cmd = (
        "docker exec ai-supermarket-mysql mysql -uroot -proot123456 "
        "--default-character-set=utf8mb4 -N -B -e "
        "\"SELECT nodes_json FROM tool_workflows w JOIN ai_tools t ON t.id=w.tool_id "
        "WHERE t.tool_code='ai_comic_drama_agent' LIMIT 1;\" ai_supermarket_v1"
    )
    _, stdout, stderr = ssh.exec_command(cmd, timeout=60)
    out = stdout.read().decode("utf-8", errors="replace").strip()
    err = stderr.read().decode("utf-8", errors="replace").strip()
    if err:
        print(err, file=sys.stderr)
    if not out:
        print("no workflow row")
        return 1
    nodes = json.loads(out)
    for node in nodes:
        if "意见" in node.get("data", {}).get("title", "") or node["id"].startswith("user-input"):
            print(node["id"], "=>", node["data"]["title"])
    ssh.close()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
