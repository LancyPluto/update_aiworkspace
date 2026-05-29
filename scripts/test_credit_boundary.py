#!/usr/bin/env python3
"""Test credit freeze/deduct boundaries for AI tools and Agent (local API)."""
from __future__ import annotations

import json
import sys
import urllib.error
import urllib.request

BASE = "http://127.0.0.1:8080"


def ok(res: dict) -> bool:
    return res.get("code") in (0, "0", "SUCCESS", "success")


def req(method: str, path: str, body=None, token=None) -> tuple[int, dict]:
    url = BASE + path
    headers = {"Content-Type": "application/json", "Accept": "application/json"}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    data = json.dumps(body).encode() if body is not None else None
    request = urllib.request.Request(url, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(request, timeout=30) as resp:
            raw = resp.read().decode()
            return resp.status, json.loads(raw) if raw else {}
    except urllib.error.HTTPError as e:
        raw = e.read().decode()
        try:
            return e.code, json.loads(raw)
        except json.JSONDecodeError:
            return e.code, {"raw": raw}


def login(account: str, admin=False) -> str:
    path = "/api/admin/v1/auth/login" if admin else "/api/v1/auth/login"
    code, res = req("POST", path, {"account": account, "password": "123456"})
    assert code == 200 and ok(res), res
    return res["data"]["accessToken"]


def account(token: str) -> dict:
    code, res = req("GET", "/api/v1/credits/account", token=token)
    assert code == 200 and ok(res), res
    return res["data"]


def set_available_via_admin(admin_token: str, user_id: int, target_available: int, user_token: str) -> dict:
    """Adjust balance so available == target_available (releases not handled; assumes frozen=0)."""
    acc = account(user_token)
    frozen = int(acc.get("frozen") or 0)
    balance = int(acc.get("balance") or 0)
    available = balance - frozen
    delta = target_available - available
    if delta > 0:
        code, res = req(
            "POST",
            f"/api/admin/v1/users/{user_id}/credits/manual-add",
            {"amount": delta, "reason": "credit boundary test setup"},
            admin_token,
        )
    elif delta < 0:
        code, res = req(
            "POST",
            f"/api/admin/v1/users/{user_id}/credits/manual-deduct",
            {"amount": -delta, "reason": "credit boundary test setup"},
            admin_token,
        )
    else:
        return acc
    assert code == 200 and ok(res), res
    return res["data"]


def pick_tool(user_token: str) -> tuple[str, int]:
    code, res = req("GET", "/api/v1/tools?pageNo=1&pageSize=50", token=user_token)
    assert code == 200 and ok(res), res
    tools = res["data"]["list"]
    online = [t for t in tools if t.get("status") == "ONLINE" and (t.get("estimatedCreditCost") or 0) > 0]
    online.sort(key=lambda t: t.get("estimatedCreditCost") or 0)
    if not online:
        raise RuntimeError("no online tools with estimatedCreditCost")
    t = online[0]
    return t["toolCode"], int(t["estimatedCreditCost"])


def create_task(user_token: str, tool_code: str, client_id: str) -> tuple[int, dict]:
    return req(
        "POST",
        "/api/v1/tasks",
        {
            "toolCode": tool_code,
            "params": {"productName": "credit-boundary-test"},
            "clientRequestId": client_id,
        },
        user_token,
    )


def get_tool_detail(user_token: str, tool_code: str) -> dict:
    code, res = req("GET", f"/api/v1/tools/{tool_code}", token=user_token)
    assert code == 200 and ok(res), res
    return res["data"]


def agent_send(user_token: str, session_id: int) -> tuple[int, dict]:
    return req(
        "POST",
        f"/api/v1/agent/sessions/{session_id}/messages",
        {"content": "hello credit test", "clientRequestId": f"credit-test-{session_id}"},
        user_token,
    )


def ensure_agent_session(user_token: str) -> int:
    code, res = req("POST", "/api/v1/agent/sessions", {"title": "credit-test"}, user_token)
    if code == 200 and ok(res):
        return int(res["data"]["id"])
    code, res = req("GET", "/api/v1/agent/sessions?pageNo=1&pageSize=1", token=user_token)
    assert code == 200 and ok(res) and res["data"]["list"], res
    return int(res["data"]["list"][0]["id"])


def main() -> int:
    print("=== Credit boundary API test ===\n")
    user_token = login("user1")
    admin_token = login("admin", admin=True)
    user_id = 3

    orig = account(user_token)
    print(f"Original account: balance={orig['balance']} frozen={orig['frozen']} available={orig['available']}")

    tool_code, tool_cost = pick_tool(user_token)
    detail = get_tool_detail(user_token, tool_code)
    print(f"Tool: {tool_code} estimatedCreditCost={tool_cost} fields={len(detail.get('fields') or [])}")

    # May differ if model PER_CALL billing overrides
    actual_estimate = tool_cost

    failures = []

    def case(name: str, cond: bool, detail_msg: str = ""):
        status = "PASS" if cond else "FAIL"
        print(f"  [{status}] {name}" + (f" — {detail_msg}" if detail_msg else ""))
        if not cond:
            failures.append(name)

    # Case 1: available < cost -> reject
    target = max(0, actual_estimate - 1)
    acc = set_available_via_admin(admin_token, user_id, target, user_token)
    print(f"\n1) Insufficient: set available={acc['available']} need>={actual_estimate}")
    code, res = create_task(user_token, tool_code, "credit-boundary-insufficient")
    case(
        "Task create returns CREDIT_NOT_ENOUGH when available < cost",
        code == 400 and res.get("code") == "CREDIT_NOT_ENOUGH",
        f"http={code} code={res.get('code')} msg={res.get('message')}",
    )

    # Case 2: available == cost -> accept
    acc = set_available_via_admin(admin_token, user_id, actual_estimate, user_token)
    print(f"\n2) Exact boundary: available={acc['available']} cost={actual_estimate}")
    code, res = create_task(user_token, tool_code, "credit-boundary-exact")
    case(
        "Task create succeeds when available == cost",
        code == 200 and ok(res) and res.get("data", {}).get("status") in ("QUEUED", "CREATED", "PROCESSING"),
        f"http={code} code={res.get('code')} status={res.get('data', {}).get('status')}",
    )
    if code == 200 and ok(res):
        acc2 = account(user_token)
        case(
            "Balance frozen after create",
            int(acc2.get("frozen") or 0) >= actual_estimate,
            f"frozen={acc2.get('frozen')}",
        )

    # Case 3: second task when only 0 available left
    acc3 = account(user_token)
    print(f"\n3) After freeze: available={acc3['available']} frozen={acc3['frozen']}")
    code, res = create_task(user_token, tool_code, "credit-boundary-second")
    case(
        "Second task rejected when available exhausted",
        code == 400 and res.get("code") == "CREDIT_NOT_ENOUGH",
        f"http={code} code={res.get('code')}",
    )

    # Case 4: Agent budget (default 20)
    agent_budget = 20
    set_available_via_admin(admin_token, user_id, agent_budget - 1, user_token)
    session_id = ensure_agent_session(user_token)
    print(f"\n4) Agent: available={agent_budget - 1} budget={agent_budget} session={session_id}")
    code, res = agent_send(user_token, session_id)
    case(
        "Agent message returns AGENT_CREDIT_NOT_ENOUGH when available < budget",
        code == 400 and res.get("code") == "AGENT_CREDIT_NOT_ENOUGH",
        f"http={code} code={res.get('code')} msg={res.get('message')}",
    )

    set_available_via_admin(admin_token, user_id, agent_budget, user_token)
    code, res = agent_send(user_token, session_id)
    case(
        "Agent message succeeds when available == budget",
        code == 200 and ok(res),
        f"http={code} code={res.get('code')}",
    )

    # Restore original available (best effort)
    print(f"\nRestoring credits toward original available={orig['available']}...")
    set_available_via_admin(admin_token, user_id, int(orig["available"]), user_token)

    print("\n=== Summary ===")
    if failures:
        print("FAILED cases:", ", ".join(failures))
        return 1
    print("All boundary checks passed.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
