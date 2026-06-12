#!/usr/bin/env python3
"""Submit launch form to Alipay gateway and print whether permission error persists."""
import json
import re
import urllib.error
import urllib.parse
import urllib.request

BASE = "http://127.0.0.1:8080"
GATEWAY = "https://openapi.alipay.com/gateway.do"


def req(method, path, body=None, token=None, accept=None, form=None):
    headers = {"Accept": accept or "application/json"}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    data = None
    if form is not None:
        data = urllib.parse.urlencode(form).encode()
        headers["Content-Type"] = "application/x-www-form-urlencoded"
    elif body is not None:
        headers["Content-Type"] = "application/json"
        data = json.dumps(body).encode()
    request = urllib.request.Request(BASE + path, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(request, timeout=30) as resp:
            return resp.status, resp.read().decode()
    except urllib.error.HTTPError as exc:
        return exc.code, exc.read().decode()


def main() -> None:
    _, res = req("POST", "/api/v1/auth/login", {"account": "user1", "password": "123456"})
    token = json.loads(res)["data"]["accessToken"]
    _, res = req("GET", "/api/v1/credits/recharge-packages", token=token)
    pkg = json.loads(res)["data"][0]
    _, res = req(
        "POST",
        "/api/v1/credits/recharge-orders",
        {
            "packageId": pkg["id"],
            "paymentChannel": "ALIPAY_PAGE",
            "clientRequestId": "alipay-gateway-probe-001",
        },
        token=token,
    )
    pay_url = json.loads(res)["data"]["payUrl"]
    _, html = req("GET", pay_url, accept="text/html")
    action_match = re.search(r'<form[^>]+action="([^"]+)"', html)
    fields = dict(re.findall(r'<input type="hidden" name="([^"]+)" value="([^"]*)"/>', html))
    if not action_match or not fields:
        print("failed to parse launch form")
        return
    action = action_match.group(1)
    print("gateway:", action)
    print("method:", fields.get("method"))
    gateway_req = urllib.request.Request(
        action,
        data=urllib.parse.urlencode(fields).encode(),
        headers={"Content-Type": "application/x-www-form-urlencoded"},
        method="POST",
    )
    try:
        with urllib.request.urlopen(gateway_req, timeout=30) as resp:
            body = resp.read().decode("utf-8", errors="replace")
            print("gateway status:", resp.status)
    except urllib.error.HTTPError as exc:
        body = exc.read().decode("utf-8", errors="replace")
        print("gateway status:", exc.code)
    snippet = body[:800].replace("\n", " ")
    print("gateway body snippet:", snippet)
    lower = body.lower()
    if "insufficient-isv-permissions" in lower or "isv权限不足" in body:
        print("RESULT: Alipay gateway still reports ISV permission insufficient")
    elif "alipay.trade.page.pay" in body and '"code":"10000"' in body.replace(" ", ""):
        print("RESULT: Alipay gateway accepted page pay request")
    elif "<form" in lower or "cashier" in lower or "支付宝" in body:
        print("RESULT: Alipay returned cashier/redirect page (likely OK)")


if __name__ == "__main__":
    main()
