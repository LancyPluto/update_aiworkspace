#!/usr/bin/env python3
import json
import sys
import time
import urllib.request

BASE = sys.argv[1] if len(sys.argv) > 1 else "http://127.0.0.1:8080"


def api(method, path, body=None, token=None):
    headers = {"Accept": "application/json"}
    if body is not None:
        headers["Content-Type"] = "application/json"
    if token:
        headers["Authorization"] = f"Bearer {token}"
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(BASE + path, data=data, headers=headers, method=method)
    with urllib.request.urlopen(req, timeout=60) as resp:
        payload = json.loads(resp.read().decode())
    if payload.get("code") != "SUCCESS":
        raise RuntimeError(payload)
    return payload["data"]


def main() -> None:
    token = api("POST", "/api/v1/auth/login", {"account": "user1", "password": "123456"})["accessToken"]
    packages = api("GET", "/api/v1/credits/recharge-packages", token=token)
    pkg = next((p for p in packages if p.get("recommended")), packages[0])
    order = api(
        "POST",
        "/api/v1/credits/recharge-orders",
        {
            "packageId": pkg["id"],
            "paymentChannel": "ALIPAY_PAGE",
            "clientRequestId": f"diag-{int(time.time() * 1000)}",
        },
        token=token,
    )
    diag = api("GET", f"/api/v1/credits/recharge-orders/{order['id']}/alipay-diagnostic", token=token)
    print(json.dumps(diag, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
