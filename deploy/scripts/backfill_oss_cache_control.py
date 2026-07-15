#!/usr/bin/env python3
"""Backfill OSS Cache-Control metadata without downloading or replacing objects."""

from __future__ import annotations

import argparse
import os
import re

import oss2

HASHED_NAME = re.compile(r"^[0-9a-f]{40}(?:\.[a-z0-9]+)?(?:\.[a-z0-9-]+\.[a-z0-9]+)?$", re.I)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--apply", action="store_true", help="write metadata; default is dry-run")
    parser.add_argument("--visibility", choices=("public", "private"), default="public")
    parser.add_argument("--prefix", default=os.getenv("OSS_KEY_PREFIX", ""))
    parser.add_argument("--limit", type=int, default=0)
    return parser.parse_args()


def required(name: str) -> str:
    value = os.getenv(name, "").strip()
    if not value:
        raise SystemExit(f"{name} is required")
    return value


def main() -> int:
    args = parse_args()
    endpoint = required("OSS_ENDPOINT")
    if not endpoint.startswith(("http://", "https://")):
        endpoint = f"https://{endpoint}"
    access_key = required("OSS_ACCESS_KEY_ID")
    access_secret = required("OSS_ACCESS_KEY_SECRET")
    bucket_name = required("OSS_PUBLIC_BUCKET" if args.visibility == "public" else "OSS_PRIVATE_BUCKET")
    bucket = oss2.Bucket(oss2.Auth(access_key, access_secret), endpoint, bucket_name)
    public_policy = os.getenv("ASSET_PUBLIC_CACHE_CONTROL", "public,max-age=31536000,immutable")
    private_policy = os.getenv("ASSET_PRIVATE_CACHE_CONTROL", "private,max-age=3600")
    legacy_policy = os.getenv("ASSET_LEGACY_CACHE_CONTROL", "public,max-age=300,must-revalidate")
    changed = 0
    for item in oss2.ObjectIterator(bucket, prefix=args.prefix):
        filename = item.key.rsplit("/", 1)[-1]
        policy = private_policy if args.visibility == "private" else (public_policy if HASHED_NAME.match(filename) else legacy_policy)
        print(f"{'APPLY' if args.apply else 'DRY-RUN'} {bucket_name}/{item.key} Cache-Control={policy}")
        if args.apply:
            bucket.update_object_meta(item.key, {"Cache-Control": policy})
        changed += 1
        if args.limit > 0 and changed >= args.limit:
            break
    print(f"objects={changed} mode={'apply' if args.apply else 'dry-run'}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
