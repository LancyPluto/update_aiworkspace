#!/usr/bin/env python3
"""Upload credit-power icon to OSS public bucket."""
from __future__ import annotations

import os
import sys
from pathlib import Path

try:
    import oss2
except ImportError:
    print("Installing oss2...")
    os.system("pip install oss2 -q")
    import oss2

ROOT = Path(__file__).resolve().parents[1]
LOCAL_ICON = ROOT / "admin-frontend" / "public" / "assets" / "credit-power-icon.png"
ENDPOINT = os.environ.get("OSS_ENDPOINT", "oss-cn-guangzhou.aliyuncs.com")
BUCKET_NAME = os.environ.get("OSS_PUBLIC_BUCKET", "wlcloudai-assets-public")
OSS_KEY = "icons/credit-power-icon.png"
ACCESS_KEY_ID = os.environ.get("OSS_ACCESS_KEY_ID") or os.environ.get("ALIYUN_ACCESS_KEY_ID")
ACCESS_KEY_SECRET = os.environ.get("OSS_ACCESS_KEY_SECRET") or os.environ.get("ALIYUN_ACCESS_KEY_SECRET")


def main() -> int:
    if not LOCAL_ICON.is_file():
        print(f"Missing icon file: {LOCAL_ICON}", file=sys.stderr)
        return 1
    if not ACCESS_KEY_ID or not ACCESS_KEY_SECRET:
        print("Set OSS_ACCESS_KEY_ID and OSS_ACCESS_KEY_SECRET before uploading.", file=sys.stderr)
        return 1

    auth = oss2.Auth(ACCESS_KEY_ID, ACCESS_KEY_SECRET)
    bucket = oss2.Bucket(auth, ENDPOINT, BUCKET_NAME)
    headers = {
        "Content-Type": "image/png",
        "x-oss-object-acl": "public-read",
        "Cache-Control": "public, max-age=31536000, immutable",
    }
    with LOCAL_ICON.open("rb") as icon_file:
        bucket.put_object(OSS_KEY, icon_file, headers=headers)

    public_url = f"https://{BUCKET_NAME}.{ENDPOINT}/{OSS_KEY}"
    print(f"Uploaded: {public_url}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
