#!/usr/bin/env python3
from __future__ import annotations

import json
import os
import sys
import urllib.error
import urllib.request

BASE_URL = os.environ.get("IMPORT_BASE_URL", "http://8.134.93.203").rstrip("/")
ACCOUNT = os.environ.get("IMPORT_ADMIN_ACCOUNT", "admin")
PASSWORD = os.environ.get("IMPORT_ADMIN_PASSWORD", "123456")


def post_json(url: str, payload: dict | None = None, token: str | None = None, timeout: int = 300) -> dict:
    data = json.dumps(payload or {}, ensure_ascii=False).encode("utf-8")
    headers = {"Content-Type": "application/json; charset=utf-8"}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    request = urllib.request.Request(url, data=data, headers=headers, method="POST")
    with urllib.request.urlopen(request, timeout=timeout) as response:
        return json.loads(response.read().decode("utf-8"))


def main() -> int:
    model_ids = [int(arg) for arg in sys.argv[1:]] or [16, 34]
    try:
        login = post_json(
            f"{BASE_URL}/api/admin/v1/auth/login",
            {"account": ACCOUNT, "password": PASSWORD},
            timeout=120,
        )
    except urllib.error.HTTPError as exc:
        body = exc.read().decode("utf-8", errors="replace")
        print(f"login failed HTTP {exc.code}: {body[:500]}", file=sys.stderr)
        return 1
    if login.get("code") != "SUCCESS":
        print(f"login failed: {login}", file=sys.stderr)
        return 1
    token = login["data"]["accessToken"]
    for model_id in model_ids:
        try:
            result = post_json(f"{BASE_URL}/api/admin/v1/agent/model-config/{model_id}/test", token=token, timeout=300)
        except urllib.error.HTTPError as exc:
            body = exc.read().decode("utf-8", errors="replace")
            print(f"{model_id}: HTTP {exc.code}: {body[:500]}")
            continue
        data = result.get("data") or {}
        print(json.dumps({
            "id": model_id,
            "code": result.get("code"),
            "success": data.get("success"),
            "provider": data.get("provider"),
            "modelName": data.get("modelName"),
            "latencyMs": data.get("latencyMs"),
            "message": data.get("message"),
        }, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
