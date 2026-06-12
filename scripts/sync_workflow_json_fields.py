#!/usr/bin/env python3
"""Add nodesJson/edgesJson/groupsJson to workflow objects that only have nodes/edges arrays."""
from __future__ import annotations

import json
from pathlib import Path


def sync_workflow(workflow: dict) -> bool:
    changed = False
    if workflow.get("nodes") and not workflow.get("nodesJson"):
        workflow["nodesJson"] = json.dumps(workflow["nodes"], ensure_ascii=False, separators=(",", ":"))
        changed = True
    if workflow.get("edges") and not workflow.get("edgesJson"):
        workflow["edgesJson"] = json.dumps(workflow["edges"], ensure_ascii=False, separators=(",", ":"))
        changed = True
    if workflow.get("groups") and not workflow.get("groupsJson"):
        workflow["groupsJson"] = json.dumps(workflow["groups"], ensure_ascii=False, separators=(",", ":"))
        changed = True
    if workflow.get("modelConfigIds") and not workflow.get("configJson"):
        config = {"modelConfigIds": workflow["modelConfigIds"]}
        if workflow.get("version") is not None:
            config["version"] = workflow["version"]
        workflow["configJson"] = json.dumps(config, ensure_ascii=False, separators=(",", ":"))
        changed = True
    return changed


def main() -> int:
    bundle_path = Path(__file__).resolve().parents[1] / "ai-tool-market-config-2026-06-09.json"
    bundle = json.loads(bundle_path.read_text(encoding="utf-8"))
    changed = 0
    for tool in bundle.get("tools", []):
        workflow = tool.get("workflow")
        if isinstance(workflow, dict) and sync_workflow(workflow):
            changed += 1
    if changed:
        bundle_path.write_text(json.dumps(bundle, ensure_ascii=False, indent=4) + "\n", encoding="utf-8")
    print(f"Updated workflows: {changed}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
