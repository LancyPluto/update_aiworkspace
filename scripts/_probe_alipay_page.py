#!/usr/bin/env python3
import json
import re
import urllib.error
import urllib.request

BASE = "http://127.0.0.1:8080"


def req(method, path, body=None, token=None, accept=None):
    headers = {"Accept": accept or "application/json"}
    if body is not None:
        headers["Content-Type"] = "application/json"
    if token:
        headers["Authorization"] = f"Bearer {token}"
    data = json.dumps(body).encode() if body is not None else None
    request = urllib.request.Request(BASE + path, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(request, timeout=30) as resp:
            return resp.status, resp.read().decode()
    except urllib.error.HTTPError as exc:
        return exc.code, exc.read().decode()


def main() -> None:
    _, res = req("POST", "/api/v1/auth/login", {"account": "user1", "password": "123456"})
    token = json.loads(res)["data"]["accessToken"]
    _, res = req("GET", "/api/v1/credits/recharge-payment-options", token=token)
    print("options", json.loads(res)["data"])
    _, res = req("GET", "/api/v1/credits/recharge-packages", token=token)
    pkg = json.loads(res)["data"][0]
    code, res = req(
        "POST",
        "/api/v1/credits/recharge-orders",
        {
            "packageId": pkg["id"],
            "paymentChannel": "ALIPAY_PAGE",
            "clientRequestId": "alipay-page-test-001",
        },
        token=token,
    )
    data = json.loads(res)
    print("create order:", data.get("code"))
    if not data.get("data"):
        print("message:", (data.get("message") or "")[:300])
        return
    pay_url = data["data"].get("payUrl", "")
    print("payUrl:", pay_url)
    if "/page/launch" not in pay_url:
        print("not a page launch url")
        return
    code, html = req("GET", pay_url, accept="text/html")
    print("launch status:", code)
    method_match = re.search(r'name="method" value="([^"]+)"', html)
    print("alipay method:", method_match.group(1) if method_match else "unknown")
    if "insufficient-isv-permissions" in html.lower():
        print("RESULT: still insufficient-isv-permissions in launch response")
    elif "alipaySubmit" in html:
        print("RESULT: launch form built OK; submit goes to alipay gateway")


if __name__ == "__main__":
    main()
