#!/usr/bin/env python3
"""Create a recharge order and dump alipay.trade.page.pay form params for Alipay diagnostic tools."""
from __future__ import annotations

import json
import time
from html import unescape as html_unescape
import re
import urllib.error
import urllib.parse
import urllib.request

BASE = "http://127.0.0.1:8080"
GATEWAY = "https://openapi.alipay.com/gateway.do"


def api(method: str, path: str, body=None, token=None):
    headers = {"Accept": "application/json"}
    if body is not None:
        headers["Content-Type"] = "application/json"
    if token:
        headers["Authorization"] = f"Bearer {token}"
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(BASE + path, data=data, headers=headers, method=method)
    with urllib.request.urlopen(req, timeout=60) as resp:
        return json.loads(resp.read().decode())


def main() -> int:
    login = api("POST", "/api/v1/auth/login", {"account": "user1", "password": "123456"})
    token = login["data"]["accessToken"]
    packages = api("GET", "/api/v1/credits/recharge-packages", token=token)
    pkg = next((p for p in packages["data"] if p.get("recommended")), packages["data"][0])
    order = api(
        "POST",
        "/api/v1/credits/recharge-orders",
        {
            "packageId": pkg["id"],
            "paymentChannel": "ALIPAY_PAGE",
            "clientRequestId": f"alipay-dump-{int(time.time() * 1000)}",
        },
        token=token,
    )["data"]
    pay_url = order["payUrl"]
    print("orderNo:", order["orderNo"])
    print("launchUrl:", pay_url)

    launch_req = urllib.request.Request(
        BASE + pay_url if pay_url.startswith("/") else pay_url,
        headers={"Accept": "text/html"},
        method="GET",
    )
    with urllib.request.urlopen(launch_req, timeout=60) as resp:
        html_text = resp.read().decode("utf-8", errors="replace")

    fields: dict[str, str] = {}
    for match in re.finditer(r'<input type="hidden" name="([^"]+)" value="(.*?)"/>', html_text, re.S):
        fields[match.group(1)] = html_unescape(match.group(2))
    if not fields:
        print("ERROR: failed to parse launch form")
        return 1

    biz_raw = fields.get("biz_content", "{}")
    try:
        biz = json.loads(biz_raw)
    except json.JSONDecodeError:
        print("biz_content raw:", biz_raw)
        raise
    print("\n=== For Alipay OpenSupport diagnostic tool ===")
    print("app_id:", fields.get("app_id"))
    print("method:", fields.get("method"))
    print("out_trade_no (biz):", biz.get("out_trade_no"))
    print("product_code:", biz.get("product_code"))
    print("total_amount:", biz.get("total_amount"))
    print("notify_url:", fields.get("notify_url"))
    print("return_url:", fields.get("return_url"))
    print("gateway:", GATEWAY)

    print("\n=== Full signed form params (copy for replay / support) ===")
    for key in sorted(fields):
        val = fields[key]
        if key == "biz_content":
            print(f"{key}=", val)
        elif key == "sign":
            print(f"{key}=", val[:48] + "..." if len(val) > 48 else val)
        else:
            print(f"{key}=", val)

    form_body = urllib.parse.urlencode(fields).encode()
    gw_req = urllib.request.Request(
        GATEWAY,
        data=form_body,
        headers={"Content-Type": "application/x-www-form-urlencoded"},
        method="POST",
    )
    with urllib.request.urlopen(gw_req, timeout=60) as resp:
        body = resp.read().decode("utf-8", errors="replace")

    traces = set(re.findall(r"(?:trace[_-]?id|traceId)['\":=\s]+([0-9A-Za-z]{16,40})", body, re.I))
    sub_code = re.search(r"sub_code['\":=\s]+([A-Za-z0-9_-]+)", body, re.I)
    sub_msg = re.search(r"sub_msg['\":=\s]+([^\"'<&]+)", body, re.I)
    err_code = re.search(r"(?:error_code|errorCode)['\":=\s]+([A-Za-z0-9_-]+)", body, re.I)
    print("\n=== Gateway response probe ===")
    print("http_status:", resp.status)
    for tid in traces:
        print("trace_id:", tid)
    if err_code:
        print("error_code:", err_code.group(1))
    if sub_code:
        print("sub_code:", sub_code.group(1))
    if sub_msg:
        print("sub_msg:", sub_msg.group(1).strip())
    if "insufficient-isv-permissions" in body.lower():
        print("RESULT: insufficient-isv-permissions detected in gateway HTML")
        snippet_start = body.lower().find("insufficient-isv-permissions")
        print("error_snippet:", body[max(0, snippet_start - 120): snippet_start + 200].replace("\n", " "))
    elif "excashier" in body.lower() or "cashier" in body.lower():
        print("RESULT: cashier HTML returned (permission OK from server-side probe)")

    # JSON permission probe via alipay.trade.query (returns trace_id in error_response)
    query_fields = dict(fields)
    query_fields["method"] = "alipay.trade.query"
    query_fields["biz_content"] = json.dumps(
        {"out_trade_no": biz.get("out_trade_no"), "query_options": ["trade_settle_info"]},
        ensure_ascii=False,
        separators=(",", ":"),
    )
    # sign must be recomputed - skip, use unsigned probe message instead
    print("\n=== Suggested Alipay OpenSupport inputs ===")
    print("Diagnostic URL: https://opensupport.alipay.com/support/diagnostic-tools/0c3f29c0-b276-42e2-ba37-7e8ee57fb4be")
    print("ISV FAQ: https://opendocs.alipay.com/support/01rax6")
    print("Fill in:")
    print("  - APPID:", fields.get("app_id"))
    print("  - API method: alipay.trade.page.pay")
    print("  - out_trade_no:", biz.get("out_trade_no"))
    print("  - product_code:", biz.get("product_code"))
    print("  - Contract no (your side): 20260525I1011001095448558005")
    print("Note: page-pay HTML errors often have NO trace_id; use APPID+method diagnosis instead.")

    # Also try JSON gateway response via Accept header
    gw_req2 = urllib.request.Request(
        GATEWAY,
        data=form_body,
        headers={
            "Content-Type": "application/x-www-form-urlencoded",
            "Accept": "application/json",
        },
        method="POST",
    )
    try:
        with urllib.request.urlopen(gw_req2, timeout=60) as resp2:
            json_body = resp2.read().decode("utf-8", errors="replace")
        print("\n=== JSON gateway response ===")
        print(json_body[:2000])
        try:
            parsed = json.loads(json_body)
            page = parsed.get("alipay_trade_page_pay_response") or parsed.get("error_response") or parsed
            print("parsed:", json.dumps(page, ensure_ascii=False, indent=2)[:1500])
        except json.JSONDecodeError:
            pass
    except urllib.error.HTTPError as exc:
        print("json probe http error:", exc.code, exc.read().decode()[:500])

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
