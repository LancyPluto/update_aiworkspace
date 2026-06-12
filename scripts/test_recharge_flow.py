#!/usr/bin/env python3
"""End-to-end API test for user-web recharge flow (local)."""
import json
import sys
import urllib.error
import urllib.request

BASE = "http://127.0.0.1:8080"


def ok(res: dict) -> bool:
    return res.get("code") in (0, "0", "SUCCESS", "success")


def req(method: str, path: str, body=None, token=None):
    url = BASE + path
    data = None
    headers = {"Content-Type": "application/json", "Accept": "application/json"}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    if body is not None:
        data = json.dumps(body).encode()
    request = urllib.request.Request(url, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(request, timeout=30) as resp:
            raw = resp.read().decode()
            return resp.status, json.loads(raw) if raw else {}
    except urllib.error.HTTPError as e:
        raw = e.read().decode()
        try:
            payload = json.loads(raw)
        except json.JSONDecodeError:
            payload = {"raw": raw}
        return e.code, payload


def main() -> int:
    print("1) Login as user1...")
    code, res = req("POST", "/api/v1/auth/login", {"account": "user1", "password": "123456"})
    if code != 200 or not ok(res):
        print("LOGIN FAIL", code, res)
        return 1
    token = res["data"].get("accessToken") or res["data"].get("token")
    user_id = res["data"].get("user", {}).get("id") or res["data"].get("userId")
    print(f"   ok userId={user_id}")

    print("2) Credit account before...")
    code, res = req("GET", "/api/v1/credits/account", token=token)
    if code != 200 or not ok(res):
        print("ACCOUNT FAIL", code, res)
        return 1
    before = res["data"]
    print(f"   available={before.get('available')} balance={before.get('balance')}")

    print("3) Recharge packages...")
    code, res = req("GET", "/api/v1/credits/recharge-packages", token=token)
    if code != 200 or not ok(res):
        print("PACKAGES FAIL", code, res)
        return 1
    packages = res["data"]
    if not packages:
        print("   no packages in DB")
        return 1
    pkg = next((p for p in packages if p.get("recommended")), packages[0])
    print(f"   using package id={pkg['id']} credits={pkg['credits']} price={pkg['priceAmount']}")

    print("4) Reject MOCK recharge channel...")
    code, res = req(
        "POST",
        "/api/v1/credits/recharge-orders",
        {
            "packageId": pkg["id"],
            "paymentChannel": "MOCK",
            "clientRequestId": f"test-mock-rejected-{pkg['id']}",
        },
        token=token,
    )
    if code == 200 and ok(res):
        print("FAIL: MOCK channel should be rejected", res)
        return 1
    message = str(res.get("message") or res)
    if "mock payment is not supported" not in message.lower():
        print("FAIL: unexpected rejection message", code, res)
        return 1
    print("   ok MOCK channel rejected")

    print("5) Create WECHAT_NATIVE recharge order...")
    code, res = req(
        "POST",
        "/api/v1/credits/recharge-orders",
        {
            "packageId": pkg["id"],
            "paymentChannel": "WECHAT_NATIVE",
            "clientRequestId": f"test-wechat-{pkg['id']}",
        },
        token=token,
    )
    if code != 200 or not ok(res):
        print("CREATE WECHAT ORDER FAIL", code, res)
        return 1
    order = res["data"]
    print(
        f"   order id={order['id']} status={order.get('status')} "
        f"channel={order.get('paymentChannel')} payUrl={'yes' if order.get('payUrl') else 'no'}"
    )

    print("6) Custom recharge rejects MOCK...")
    code, res = req(
        "POST",
        "/api/v1/credits/recharge-orders/custom",
        {"amount": 1, "paymentChannel": "MOCK", "clientRequestId": "test-custom-mock-rejected"},
        token=token,
    )
    if code == 200 and ok(res):
        print("FAIL: custom MOCK channel should be rejected", res)
        return 1
    print("   ok custom MOCK rejected")

    print("\nAll recharge API steps passed.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
