"""Probe Kling API credentials and endpoint behavior.

Examples:
  python worker/scripts/probe_kling_api.py --access-key AK --secret-key SK
  python worker/scripts/probe_kling_api.py --extra-auth-json "{\"accessKey\":\"...\",\"secretKey\":\"...\"}"
  python worker/scripts/probe_kling_api.py --path /v1/videos/omni-video --model kling-v2-6

The script intentionally uses only the Python standard library so it can run
even when the worker virtualenv dependencies are not installed.
"""

from __future__ import annotations

import argparse
import base64
import hashlib
import hmac
import json
import os
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path
from typing import Any


PROJECT_ROOT = Path(__file__).resolve().parents[2]


def _load_dotenv(path: Path) -> None:
    if not path.is_file():
        return
    for raw_line in path.read_text(encoding="utf-8").splitlines():
        line = raw_line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        key = key.strip()
        if not key or key in os.environ:
            continue
        value = value.strip().strip('"').strip("'")
        os.environ[key] = value


def _base64url_json(value: dict[str, Any]) -> str:
    raw = json.dumps(value, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
    return base64.urlsafe_b64encode(raw).rstrip(b"=").decode("ascii")


def _jwt_token(access_key: str, secret_key: str) -> str:
    now = int(time.time())
    signing_input = ".".join(
        [
            _base64url_json({"alg": "HS256", "typ": "JWT"}),
            _base64url_json({"iss": access_key, "exp": now + 1800, "nbf": now - 5}),
        ]
    )
    signature = hmac.new(
        secret_key.encode("utf-8"),
        signing_input.encode("utf-8"),
        hashlib.sha256,
    ).digest()
    encoded_signature = base64.urlsafe_b64encode(signature).rstrip(b"=").decode("ascii")
    return f"{signing_input}.{encoded_signature}"


def _credentials(args: argparse.Namespace) -> tuple[str, str, str]:
    extra_auth = args.extra_auth_json or os.getenv("KLING_EXTRA_AUTH_JSON", "")
    if extra_auth.strip():
        try:
            parsed = json.loads(extra_auth)
        except json.JSONDecodeError as exc:
            raise SystemExit(f"extra auth json is invalid: {exc}") from exc
        access_key = str(parsed.get("accessKey") or parsed.get("access_key") or "").strip()
        secret_key = str(parsed.get("secretKey") or parsed.get("secret_key") or "").strip()
        if access_key and secret_key:
            return access_key, secret_key, "extraAuthJson"

    access_key = (args.access_key or os.getenv("KLING_ACCESS_KEY") or "").strip()
    secret_key = (args.secret_key or os.getenv("KLING_SECRET_KEY") or "").strip()
    if access_key and secret_key:
        return access_key, secret_key, "KLING_ACCESS_KEY/KLING_SECRET_KEY"

    raise SystemExit(
        "Kling AK/SK is missing. Use --extra-auth-json, --access-key/--secret-key, "
        "or KLING_ACCESS_KEY/KLING_SECRET_KEY."
    )


def _build_payload(args: argparse.Namespace) -> dict[str, Any]:
    if args.payload_json:
        try:
            payload = json.loads(args.payload_json)
        except json.JSONDecodeError as exc:
            raise SystemExit(f"payload json is invalid: {exc}") from exc
        if not isinstance(payload, dict):
            raise SystemExit("payload json must be an object")
        return payload

    payload: dict[str, Any] = {
        "model_name": args.model,
        "prompt": args.prompt,
    }
    if args.duration:
        payload["duration"] = args.duration
    if args.aspect_ratio:
        payload["aspect_ratio"] = args.aspect_ratio
    if args.mode:
        payload["mode"] = args.mode
    if args.negative_prompt:
        payload["negative_prompt"] = args.negative_prompt
    if args.resolution:
        payload["resolution"] = args.resolution
    return payload


def _post_json(url: str, token: str, payload: dict[str, Any]) -> tuple[int, str]:
    body = json.dumps(payload, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
    request = urllib.request.Request(
        url=url,
        data=body,
        headers={
            "Authorization": f"Bearer {token}",
            "Content-Type": "application/json",
            "Accept": "application/json",
        },
        method="POST",
    )
    try:
        with urllib.request.urlopen(request, timeout=60) as response:
            return response.status, response.read().decode("utf-8", errors="replace")
    except urllib.error.HTTPError as exc:
        return exc.code, exc.read().decode("utf-8", errors="replace")
    except urllib.error.URLError as exc:
        raise SystemExit(f"request failed before HTTP response: {exc}") from exc


def main() -> int:
    _load_dotenv(PROJECT_ROOT / ".env")
    _load_dotenv(PROJECT_ROOT / "worker" / ".env")

    parser = argparse.ArgumentParser(description="Probe Kling video API with AK/SK.")
    parser.add_argument("--base-url", default=os.getenv("KLING_BASE_URL", "https://api-beijing.klingai.com"))
    parser.add_argument("--path", default=os.getenv("KLING_VIDEO_TEXT_PATH", "/v1/videos/text2video"))
    parser.add_argument("--model", default=os.getenv("KLING_VIDEO_MODEL", "kling-v2-6"))
    parser.add_argument("--prompt", default="一只白色咖啡杯放在木桌上，镜头缓慢推进")
    parser.add_argument("--duration", default="5")
    parser.add_argument("--aspect-ratio", default="16:9")
    parser.add_argument("--mode", default="")
    parser.add_argument("--resolution", default="")
    parser.add_argument("--negative-prompt", default="")
    parser.add_argument("--payload-json", default="")
    parser.add_argument("--extra-auth-json", default="")
    parser.add_argument("--access-key", default="")
    parser.add_argument("--secret-key", default="")
    args = parser.parse_args()

    access_key, secret_key, credential_source = _credentials(args)
    token = _jwt_token(access_key, secret_key)
    payload = _build_payload(args)
    base_url = args.base_url.rstrip("/")
    path = args.path if args.path.startswith("/") else f"/{args.path}"
    url = f"{base_url}{path}"

    print(f"credential_source={credential_source}")
    print(f"access_key_prefix={access_key[:4]}***")
    print(f"url={url}")
    print(f"payload={json.dumps(payload, ensure_ascii=False)}")

    status, text = _post_json(url, token, payload)
    print(f"http_status={status}")
    print(f"body={text}")
    return 0 if 200 <= status < 300 else 1


if __name__ == "__main__":
    sys.exit(main())
