#!/usr/bin/env python3
"""Tail production backend logs for WeChat pay key errors."""
from __future__ import annotations

import os
import sys

import paramiko


def main() -> int:
    host = os.environ.get("DEPLOY_HOST", "8.134.93.203")
    user = os.environ.get("DEPLOY_USER", "root")
    password = os.environ.get("DEPLOY_PASSWORD")
    if not password:
        print("DEPLOY_PASSWORD required", file=sys.stderr)
        return 1

    ssh = paramiko.SSHClient()
    ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    ssh.connect(host, username=user, password=password, timeout=30, allow_agent=False, look_for_keys=False)

    steps = [
        (
            "backend env (wechat)",
            "docker exec ai-supermarket-backend sh -lc \"printenv | grep -E 'WECHAT_PAY_|WECHAT_NATIVE_'\"",
        ),
        (
            "backend key path exists",
            "docker exec ai-supermarket-backend sh -lc 'ls -la \"$WECHAT_PAY_MERCHANT_PRIVATE_KEY_PATH\"'",
        ),
        ("backend WXcert dir", "docker exec ai-supermarket-backend sh -lc 'ls -la /app/WXcert || true'"),
        (
            "backend key head",
            "docker exec ai-supermarket-backend sh -lc 'head -n 5 \"$WECHAT_PAY_MERCHANT_PRIVATE_KEY_PATH\"'",
        ),
        (
            "backend pub key head",
            "docker exec ai-supermarket-backend sh -lc 'head -n 5 \"$WECHAT_PAY_PUBLIC_KEY_PATH\"'",
        ),
        (
            "backend logs (wechat related)",
            "docker logs ai-supermarket-backend --tail 1200 2>&1 | "
            "grep -iE 'wechat|merchant private key|private key cannot|cannot be loaded|native pay|DefaultWechatNativePayClient'",
        ),
    ]

    for title, cmd in steps:
        sys.stdout.write(f"\n$ {title}\n")
        _, stdout, stderr = ssh.exec_command(cmd, timeout=60)
        out = stdout.read().decode("utf-8", "replace")
        err = stderr.read().decode("utf-8", "replace")
        if out:
            sys.stdout.buffer.write(out.encode("utf-8", errors="replace"))
            if not out.endswith("\n"):
                sys.stdout.write("\n")
        if err:
            sys.stderr.buffer.write(err.encode("utf-8", errors="replace"))
            if not err.endswith("\n"):
                sys.stderr.write("\n")
    ssh.close()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

