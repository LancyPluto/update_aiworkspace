#!/usr/bin/env python3
"""Persist repaired workflow JSON back to DB via admin save API."""
import json
import urllib.request

BASE = "http://localhost:8080"


def post(path: str, payload: dict, token: str | None = None) -> dict:
    headers = {"Content-Type": "application/json; charset=utf-8"}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    req = urllib.request.Request(
        f"{BASE}{path}",
        data=json.dumps(payload, ensure_ascii=False).encode("utf-8"),
        headers=headers,
        method="POST",
    )
    with urllib.request.urlopen(req) as resp:
        return json.load(resp)


def get(path: str, token: str) -> dict:
    req = urllib.request.Request(f"{BASE}{path}", headers={"Authorization": f"Bearer {token}"})
    with urllib.request.urlopen(req) as resp:
        return json.load(resp)


def main() -> None:
    login = post("/api/admin/v1/auth/login", {"account": "admin", "password": "123456"})
    token = login["data"]["accessToken"]
    wf = get("/api/admin/v1/tools/83/workflow", token)["data"]
    nodes = json.loads(wf["nodesJson"])
    title = next(n["data"]["title"] for n in nodes if n["id"] == "user-input-script")
    print("before save API title:", title)
    saved = post(
        "/api/admin/v1/tools/83/workflow",
        {
            "workflowName": wf["workflowName"],
            "nodesJson": wf["nodesJson"],
            "edgesJson": wf["edgesJson"],
            "groupsJson": wf.get("groupsJson"),
            "configJson": wf.get("configJson"),
            "status": wf.get("status"),
        },
        token,
    )
    print("save ok, version:", saved["data"]["version"])
    wf2 = get("/api/admin/v1/tools/83/workflow", token)["data"]
    nodes2 = json.loads(wf2["nodesJson"])
    title2 = next(n["data"]["title"] for n in nodes2 if n["id"] == "user-input-script")
    print("after save API title:", title2)


if __name__ == "__main__":
    main()
