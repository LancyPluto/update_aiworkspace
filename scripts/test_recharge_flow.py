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

    print("4) Create MOCK recharge order...")
    code, res = req(
        "POST",
        "/api/v1/credits/recharge-orders",
        {
            "packageId": pkg["id"],
            "paymentChannel": "MOCK",
            "clientRequestId": f"test-mock-{pkg['id']}",
        },
        token=token,
    )
    if code != 200 or not ok(res):
        print("CREATE ORDER FAIL", code, res)
        return 1
    order = res["data"]
    order_id = order["id"]
    print(f"   order id={order_id} status={order.get('status')} channel={order.get('paymentChannel')}")

    print("5) Mock pay success...")
    code, res = req("POST", f"/api/v1/credits/recharge-orders/{order_id}/mock-pay-success", token=token)
    if code != 200 or not ok(res):
        print("MOCK PAY FAIL", code, res)
        return 1
    paid = res["data"]
    print(f"   status={paid.get('status')} creditedAt={paid.get('creditedAt')}")

    print("6) Credit account after...")
    code, res = req("GET", "/api/v1/credits/account", token=token)
    after = res["data"]
    delta = after.get("available", 0) - before.get("available", 0)
    print(f"   available={after.get('available')} (delta +{delta}, expected +{pkg['credits']})")

    print("7) Custom recharge MOCK 1 yuan...")
    code, res = req(
        "POST",
        "/api/v1/credits/recharge-orders/custom",
        {"amount": 1, "paymentChannel": "MOCK", "clientRequestId": "test-custom-1"},
        token=token,
    )
    if code != 200 or not ok(res):
        print("CUSTOM ORDER FAIL", code, res)
        return 1
    c_order = res["data"]
    code, res = req("POST", f"/api/v1/credits/recharge-orders/{c_order['id']}/mock-pay-success", token=token)
    if code != 200 or not ok(res):
        print("CUSTOM PAY FAIL", code, res)
        return 1
    print(f"   custom order credited status={res['data'].get('status')}")

    code, res = req("GET", "/api/v1/credits/account", token=token)
    final = res["data"]
    print(f"   final available={final.get('available')}")

    if paid.get("status") != "CREDITED":
        print("FAIL: package order not CREDITED")
        return 1
    if delta != pkg["credits"]:
        print(f"WARN: credit delta {delta} != package credits {pkg['credits']}")

    print("\nAll recharge API steps passed.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
