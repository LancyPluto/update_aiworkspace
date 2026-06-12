#!/usr/bin/env python3
"""Repair tool_workflows.nodes_json/edges_json from config bundle for ai_comic_drama_agent."""
from __future__ import annotations

import json
import subprocess
from pathlib import Path


def mysql_exec(sql: str) -> None:
    proc = subprocess.run(
        [
            "docker", "exec", "-i", "ai-supermarket-mysql",
            "mysql", "-uroot", "-proot123456", "--default-character-set=utf8mb4",
            "ai_supermarket_v1",
        ],
        input=sql.encode("utf-8"),
        capture_output=True,
        check=True,
    )
    if proc.stdout:
        print(proc.stdout.decode("utf-8", errors="replace"))


def main() -> None:
    bundle_path = Path(__file__).resolve().parents[1] / "ai-tool-market-config-2026-06-09.json"
    bundle = json.loads(bundle_path.read_text(encoding="utf-8"))
    tool = next(t for t in bundle["tools"] if t["toolCode"] == "ai_comic_drama_agent")
    workflow = tool["workflow"]
    nodes_json = workflow["nodesJson"]
    edges_json = workflow["edgesJson"]
    config_json = workflow.get("configJson") or json.dumps(
        {"modelConfigIds": workflow["modelConfigIds"], "version": workflow.get("version")},
        ensure_ascii=False,
        separators=(",", ":"),
    )

    title = next(n["data"]["title"] for n in json.loads(nodes_json) if n["id"] == "user-input-script")
    print("bundle title:", title)

    def esc(value: str) -> str:
        return value.replace("\\", "\\\\").replace("'", "''")

    sql = f"""
SET NAMES utf8mb4;
UPDATE tool_workflows w
JOIN ai_tools t ON t.id = w.tool_id
SET
  w.nodes_json = '{esc(nodes_json)}',
  w.edges_json = '{esc(edges_json)}',
  w.config_json = '{esc(config_json)}',
  w.status = 'PUBLISHED',
  w.version = w.version + 1
WHERE t.tool_code = 'ai_comic_drama_agent';
"""
    mysql_exec(sql)
    print("DB updated")


if __name__ == "__main__":
    main()
