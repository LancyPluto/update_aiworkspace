#!/usr/bin/env python3
"""Dump Alipay page pay params from production wlcloudai.com for diagnostic tool."""
from __future__ import annotations

import json
import re
import urllib.error
import urllib.parse
import urllib.request

BASE = "http://wlcloudai.com"
GATEWAY = "https://openapi.alipay.com/gateway.do"


def api(method: str, path: str, body=None, token=None):
    headers = {"Accept": "application/json"}
    if body is not None:
        headers["Content-Type"] = "application/json"
    if token:
        headers["Authorization"] = f"Bearer {token}"
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(BASE + path, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=60) as resp:
            return resp.status, json.loads(resp.read().decode())
    except urllib.error.HTTPError as exc:
        return exc.code, json.loads(exc.read().decode())


def main() -> int:
    code, login = api("POST", "/api/v1/auth/login", {"account": "user1", "password": "123456"})
    print("login", code, login.get("code"), login.get("message"))
    if code != 200 or login.get("code") != "SUCCESS":
        return 1
    token = login["data"]["accessToken"]
    _, packages = api("GET", "/api/v1/credits/recharge-packages", token=token)
    pkg = next((p for p in packages["data"] if p.get("recommended")), packages["data"][0])
    _, created = api(
        "POST",
        "/api/v1/credits/recharge-orders",
        {
            "packageId": pkg["id"],
            "paymentChannel": "ALIPAY_PAGE",
            "clientRequestId": "alipay-prod-dump-001",
        },
        token=token,
    )
    order = created["data"]
    pay_url = order["payUrl"]
    full_launch = BASE + pay_url if pay_url.startswith("/") else pay_url
    print("orderNo:", order["orderNo"])
    print("launchUrl:", full_launch)
    with urllib.request.urlopen(urllib.request.Request(full_launch, headers={"Accept": "text/html"}), timeout=60) as resp:
        html = resp.read().decode("utf-8", errors="replace")
    fields = dict(re.findall(r'<input type="hidden" name="([^"]+)" value="([^"]*)"/>', html))
    biz = json.loads(fields.get("biz_content", "{}"))
    print("\n=== Production params for Alipay diagnostic ===")
    print("app_id:", fields.get("app_id"))
    print("method:", fields.get("method"))
    print("out_trade_no:", biz.get("out_trade_no"))
    print("total_amount:", biz.get("total_amount"))
    print("product_code:", biz.get("product_code"))
    print("notify_url:", fields.get("notify_url"))
    print("return_url:", fields.get("return_url"))
    form_body = urllib.parse.urlencode(fields).encode()
    with urllib.request.urlopen(
        urllib.request.Request(GATEWAY, data=form_body, headers={"Content-Type": "application/x-www-form-urlencoded"}, method="POST"),
        timeout=60,
    ) as resp:
        body = resp.read().decode("utf-8", errors="replace")
    trace = re.search(r"trace_id[=:\"'\\s]+([A-Za-z0-9]+)", body, re.I)
    print("gateway_status:", resp.status)
    if trace:
        print("trace_id:", trace.group(1))
    if "insufficient-isv-permissions" in body.lower():
        print("RESULT: insufficient-isv-permissions")
        m = re.search(r"traceId['\"]?[:=]['\"]?([A-Za-z0-9]+)", body, re.I)
        if m:
            print("traceId alt:", m.group(1))
    else:
        print("RESULT: cashier html ok")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
