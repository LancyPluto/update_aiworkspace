#!/usr/bin/env python3
"""Verify custom recharge returns qrCodeUrl for WeChat (when configured)."""
import json
import sys
import urllib.error
import urllib.request

BASE = "http://127.0.0.1:8080"


def req(method, path, body=None, token=None):
    headers = {"Content-Type": "application/json", "Accept": "application/json"}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    data = json.dumps(body).encode() if body is not None else None
    r = urllib.request.Request(BASE + path, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(r, timeout=30) as resp:
            return resp.status, json.loads(resp.read().decode())
    except urllib.error.HTTPError as e:
        return e.code, json.loads(e.read().decode())


def main():
    code, res = req("POST", "/api/v1/auth/login", {"account": "user1", "password": "123456"})
    if code != 200 or res.get("code") != "SUCCESS":
        print("login failed", code, res)
        return 1
    token = res["data"]["accessToken"]

    code, res = req(
        "POST",
        "/api/v1/credits/recharge-orders/custom",
        {"amount": 0.01, "paymentChannel": "WECHAT_NATIVE", "clientRequestId": f"wx-custom-{__import__('time').time()}"},
        token,
    )
    print("status", code)
    print("code", res.get("code"))
    print("message", res.get("message"))
    if code == 200 and res.get("code") == "SUCCESS":
        d = res["data"]
        print("payUrl", (d.get("payUrl") or "")[:80])
        print("qrCodeUrl prefix", (d.get("qrCodeUrl") or "")[:60])
        print("has qr", bool(d.get("qrCodeUrl")))
        return 0 if d.get("qrCodeUrl") or d.get("payUrl") else 1
    return 1


if __name__ == "__main__":
    raise SystemExit(main())
