#!/usr/bin/env python3
"""Check container outbound connectivity for model providers.

This script is intentionally dependency-light on the host. It shells into the
agent-service container, where httpx is already installed, and verifies that
model-provider domains can return any HTTP response through the same proxy and
NO_PROXY environment the runtime uses. A 401/403/404 response is considered
network-reachable; connection exceptions are failures.
"""
from __future__ import annotations

import argparse
import os
import subprocess
import sys
from pathlib import Path


DEFAULT_CONTAINER = "ai-supermarket-agent-service"
DEFAULT_URLS = (
    "https://api.deepseek.com",
    "https://ark.cn-beijing.volces.com/api/v3/chat/completions",
    "https://api.minimaxi.com/v1",
    "https://dashscope.aliyuncs.com/compatible-mode/v1",
)


def _repo_root() -> Path:
    return Path(__file__).resolve().parents[2]


def _read_env_file(path: Path) -> dict[str, str]:
    values: dict[str, str] = {}
    if not path.exists():
        return values
    for raw in path.read_text(encoding="utf-8", errors="replace").splitlines():
        line = raw.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        values[key.strip()] = value.strip().strip('"').strip("'")
    return values


def _merged_env() -> dict[str, str]:
    root = _repo_root()
    merged: dict[str, str] = {}
    for rel in (".env", "deploy/.env"):
        merged.update(_read_env_file(root / rel))
    return merged


def _run(args: list[str], timeout: int) -> subprocess.CompletedProcess[str]:
    return subprocess.run(args, text=True, capture_output=True, timeout=timeout)


def _docker_available() -> bool:
    proc = _run(["docker", "ps", "--format", "{{.Names}}"], timeout=10)
    return proc.returncode == 0


def _container_exists(container: str) -> bool:
    proc = _run(["docker", "ps", "--format", "{{.Names}}"], timeout=10)
    return proc.returncode == 0 and container in proc.stdout.splitlines()


def _print_env_summary(container: str, env: dict[str, str], include_container: bool = True) -> None:
    print("== env files ==")
    for key in (
        "CONTAINER_HTTP_PROXY",
        "CONTAINER_HTTPS_PROXY",
        "CONTAINER_NO_PROXY",
        "HTTP_PROXY",
        "HTTPS_PROXY",
        "NO_PROXY",
    ):
        if key in env:
            print(f"{key}={env[key]}")
    if not include_container:
        return
    print("\n== container env ==")
    proc = _run(
        [
            "docker",
            "exec",
            container,
            "sh",
            "-lc",
            'printf "HTTP_PROXY=%s\\nHTTPS_PROXY=%s\\nNO_PROXY=%s\\n" "$HTTP_PROXY" "$HTTPS_PROXY" "$NO_PROXY"',
        ],
        timeout=20,
    )
    print(proc.stdout.strip() or "(empty)")
    if proc.stderr.strip():
        print(proc.stderr.strip(), file=sys.stderr)


def _check_container_urls(container: str, urls: list[str], timeout_seconds: int) -> int:
    code = r"""
import httpx
import os
import sys
import time

timeout = float(sys.argv[1])
urls = sys.argv[2:]
print("runtime_proxy=" + (os.environ.get("HTTPS_PROXY") or os.environ.get("HTTP_PROXY") or ""))
print("runtime_no_proxy=" + (os.environ.get("NO_PROXY") or ""))
failed = 0
for url in urls:
    started = time.time()
    try:
        response = httpx.get(url, timeout=timeout, trust_env=True, follow_redirects=False)
        elapsed = time.time() - started
        print(f"OK {url} status={response.status_code} time={elapsed:.3f}s")
    except Exception as exc:
        elapsed = time.time() - started
        failed += 1
        print(f"FAIL {url} {type(exc).__name__}: {str(exc)[:220]} time={elapsed:.3f}s")
sys.exit(1 if failed else 0)
"""
    proc = _run(
        ["docker", "exec", container, "python", "-c", code, str(timeout_seconds), *urls],
        timeout=max(30, timeout_seconds * max(1, len(urls)) + 15),
    )
    print("\n== outbound url checks ==")
    print(proc.stdout.strip())
    if proc.stderr.strip():
        print(proc.stderr.strip(), file=sys.stderr)
    return proc.returncode


def main() -> int:
    parser = argparse.ArgumentParser(description="Check model-provider outbound connectivity from agent-service.")
    parser.add_argument("--container", default=os.environ.get("OUTBOUND_CHECK_CONTAINER", DEFAULT_CONTAINER))
    parser.add_argument(
        "--url",
        action="append",
        dest="urls",
        help="URL to probe. Can be supplied multiple times. Defaults to common model provider endpoints.",
    )
    parser.add_argument("--timeout", type=int, default=int(os.environ.get("OUTBOUND_CHECK_TIMEOUT", "12")))
    parser.add_argument("--skip-docker", action="store_true", help="Only print env-file proxy settings.")
    args = parser.parse_args()

    urls = args.urls or [url for url in os.environ.get("OUTBOUND_CHECK_URLS", "").split(",") if url.strip()] or list(DEFAULT_URLS)
    env = _merged_env()

    if args.skip_docker:
        _print_env_summary(args.container, env, include_container=False)
        return 0
    if not _docker_available():
        print("docker is unavailable; cannot check container outbound connectivity", file=sys.stderr)
        return 2
    if not _container_exists(args.container):
        print(f"container not running: {args.container}", file=sys.stderr)
        return 2

    _print_env_summary(args.container, env)
    return _check_container_urls(args.container, urls, args.timeout)


if __name__ == "__main__":
    raise SystemExit(main())
