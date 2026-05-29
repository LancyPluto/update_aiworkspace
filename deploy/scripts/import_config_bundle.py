#!/usr/bin/env python3
"""Import ai-tool-market config bundle JSON via admin API."""
from __future__ import annotations

import argparse
import json
import os
import sys
import urllib.error
import urllib.request
from pathlib import Path


def post_json(url: str, payload: dict, token: str | None = None, timeout: int = 300) -> dict:
    data = json.dumps(payload, ensure_ascii=False).encode("utf-8")
    headers = {"Content-Type": "application/json; charset=utf-8"}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    req = urllib.request.Request(url, data=data, headers=headers, method="POST")
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        return json.loads(resp.read().decode("utf-8"))


def get_json(url: str, token: str | None = None, timeout: int = 120) -> dict:
    headers = {}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    req = urllib.request.Request(url, headers=headers, method="GET")
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        return json.loads(resp.read().decode("utf-8"))


def main() -> int:
    parser = argparse.ArgumentParser(description="Import config bundle to admin API")
    parser.add_argument(
        "bundle",
        nargs="?",
        default=str(Path(__file__).resolve().parents[2] / "ai-tool-market-config-2026-05-29.json"),
        help="Path to config bundle JSON",
    )
    parser.add_argument("--base-url", default=os.environ.get("IMPORT_BASE_URL", "http://8.134.93.203"))
    parser.add_argument("--account", default=os.environ.get("IMPORT_ADMIN_ACCOUNT", "admin"))
    parser.add_argument("--password", default=os.environ.get("IMPORT_ADMIN_PASSWORD", "123456"))
    args = parser.parse_args()

    bundle_path = Path(args.bundle)
    if not bundle_path.is_file():
        print(f"Bundle not found: {bundle_path}", file=sys.stderr)
        return 1

    base = args.base_url.rstrip("/")
    print(f"Loading {bundle_path} ({bundle_path.stat().st_size // 1024} KB)...")
    with bundle_path.open("r", encoding="utf-8") as f:
        bundle = json.load(f)

    print(f"Logging in as {args.account} at {base}...")
    try:
        login_resp = post_json(
            f"{base}/api/admin/v1/auth/login",
            {"account": args.account, "password": args.password},
        )
    except urllib.error.HTTPError as e:
        body = e.read().decode("utf-8", errors="replace")
        print(f"Login failed HTTP {e.code}: {body}", file=sys.stderr)
        return 1

    if login_resp.get("code") != "SUCCESS":
        print(f"Login failed: {login_resp}", file=sys.stderr)
        return 1

    token = login_resp["data"]["accessToken"]
    print("Importing config bundle (this may take a minute)...")
    try:
        import_resp = post_json(
            f"{base}/api/admin/v1/config-bundles/import",
            bundle,
            token=token,
            timeout=600,
        )
    except urllib.error.HTTPError as e:
        body = e.read().decode("utf-8", errors="replace")
        print(f"Import failed HTTP {e.code}: {body[:4000]}", file=sys.stderr)
        return 1

    if import_resp.get("code") != "SUCCESS":
        print(f"Import failed: {json.dumps(import_resp, ensure_ascii=False, indent=2)}", file=sys.stderr)
        return 1

    data = import_resp.get("data") or {}
    print("Import succeeded.")
    for key in (
        "settings",
        "categories",
        "tools",
        "modelConfigs",
        "workflows",
        "prompts",
    ):
        if key in data:
            print(f"  {key}: {data[key]}")
    warnings = data.get("warnings") or []
    if warnings:
        print(f"  warnings ({len(warnings)}):")
        for w in warnings[:20]:
            print(f"    - {w}")
        if len(warnings) > 20:
            print(f"    ... and {len(warnings) - 20} more")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
