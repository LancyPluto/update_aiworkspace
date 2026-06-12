#!/usr/bin/env python3
import json
import urllib.request

BASE = "http://wlcloudai.com"


def main() -> None:
    login_req = urllib.request.Request(
        f"{BASE}/api/admin/v1/auth/login",
        data=json.dumps({"account": "admin", "password": "123456"}).encode(),
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    with urllib.request.urlopen(login_req, timeout=30) as resp:
        token = json.load(resp)["data"]["accessToken"]

    tools_req = urllib.request.Request(
        f"{BASE}/api/admin/v1/tools?pageNo=1&pageSize=100",
        headers={"Authorization": f"Bearer {token}"},
    )
    with urllib.request.urlopen(tools_req, timeout=30) as resp:
        tools = json.load(resp)["data"]["items"]
    comic = next(t for t in tools if t["toolCode"] == "ai_comic_drama_agent")
    tool_id = comic["id"]
    print("tool_id:", tool_id)

    wf_req = urllib.request.Request(
        f"{BASE}/api/admin/v1/tools/{tool_id}/workflow",
        headers={"Authorization": f"Bearer {token}"},
    )
    with urllib.request.urlopen(wf_req, timeout=30) as resp:
        wf = json.load(resp)["data"]
    nodes = json.loads(wf["nodesJson"])
    for node in nodes:
        title = node.get("data", {}).get("title", "")
        if "意见" in title or "è" in title:
            print(node["id"], "=>", title)


if __name__ == "__main__":
    main()
