#!/usr/bin/env python3
"""Verify cache headers and OSS image processing for representative public media."""

from __future__ import annotations

import argparse
import urllib.request
from urllib.parse import parse_qsl, urlencode, urlsplit, urlunsplit


def head(url: str) -> dict[str, str]:
    request = urllib.request.Request(url, method="HEAD", headers={"User-Agent": "media-delivery-check/1"})
    with urllib.request.urlopen(request, timeout=20) as response:
        if response.status < 200 or response.status >= 400:
            raise RuntimeError(f"unexpected HTTP {response.status}: {url}")
        return {key.lower(): value for key, value in response.headers.items()}


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("url", help="public content-addressed image URL")
    args = parser.parse_args()
    original = head(args.url)
    parts = urlsplit(args.url)
    query = parse_qsl(parts.query, keep_blank_values=True)
    query = [(key, value) for key, value in query if key.lower() != "x-oss-process"]
    query.append(("x-oss-process", "image/resize,w_640/format,webp/quality,q_85"))
    variant_url = urlunsplit((parts.scheme, parts.netloc, parts.path, urlencode(query), parts.fragment))
    variant_first = head(variant_url)
    variant_second = head(variant_url)
    cache_control = original.get("cache-control", "")
    if "max-age=31536000" not in cache_control or "immutable" not in cache_control:
        raise SystemExit(f"invalid public Cache-Control: {cache_control or '[missing]'}")
    if not original.get("etag"):
        raise SystemExit("public media ETag is missing")
    if "image/webp" not in variant_first.get("content-type", "").lower():
        raise SystemExit(f"OSS image transform did not return WebP: {variant_first.get('content-type', '[missing]')}")
    print(f"original cache-control={cache_control} etag={original['etag']}")
    print(f"variant content-type={variant_first.get('content-type', '[missing]')}")
    print(f"repeat age={variant_second.get('age', '[unavailable]')} x-cache={variant_second.get('x-cache', '[unavailable]')}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
