#!/usr/bin/env python3
"""Quick OSS upload/read smoke test using project .env credentials."""
from __future__ import annotations

import os
import sys
import uuid
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "worker"))

try:
    from dotenv import load_dotenv
except ImportError:
    load_dotenv = None

if load_dotenv is not None:
    load_dotenv(ROOT / ".env")

import oss2  # noqa: E402


def main() -> int:
    endpoint = os.getenv("OSS_ENDPOINT", "oss-cn-guangzhou.aliyuncs.com").strip()
    bucket_name = os.getenv("OSS_BUCKET", "").strip()
    access_key = (
        os.getenv("OSS_ACCESS_KEY_ID")
        or os.getenv("ALIYUN_ACCESS_KEY_ID")
        or os.getenv("ALIYUN_CAPTCHA_ACCESS_KEY_ID")
        or ""
    ).strip()
    secret = (
        os.getenv("OSS_ACCESS_KEY_SECRET")
        or os.getenv("ALIYUN_ACCESS_KEY_SECRET")
        or os.getenv("ALIYUN_CAPTCHA_ACCESS_KEY_SECRET")
        or ""
    ).strip()
    public_base = os.getenv("ASSET_STORAGE_PUBLIC_BASE_URL", "").rstrip("/")

    if not all([bucket_name, access_key, secret]):
        print("Missing OSS_BUCKET or access key in .env", file=sys.stderr)
        return 1

    if not endpoint.startswith("http"):
        endpoint = f"https://{endpoint}"

    auth = oss2.Auth(access_key, secret)
    bucket = oss2.Bucket(auth, endpoint, bucket_name)
    key = f"health-check/{uuid.uuid4().hex}.txt"
    payload = b"ai-tool-market oss connectivity ok"

    bucket.put_object(key, payload, headers={"Content-Type": "text/plain"})
    print(f"uploaded: {key}")

    if public_base:
        url = f"{public_base}/{key}"
        import urllib.request

        with urllib.request.urlopen(url, timeout=15) as response:
            body = response.read()
        if body != payload:
            print(f"public read mismatch: {url}", file=sys.stderr)
            return 1
        print(f"public read ok: {url}")

    bucket.delete_object(key)
    print("cleanup ok")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
