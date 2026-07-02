#!/usr/bin/env python3
"""Diagnose WeChat Pay cert/key files on production host and container."""
from __future__ import annotations

import os
import sys

import paramiko


def run(ssh: paramiko.SSHClient, title: str, cmd: str, timeout: int = 60) -> None:
    sys.stdout.write(f"\n$ {title}\n")
    _, stdout, stderr = ssh.exec_command(cmd, timeout=timeout)
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

    remote_root = "/root/ai_tool_market"
    run(ssh, "host WXcert dir", f"ls -la {remote_root}/WXcert || true")
    run(ssh, "compose backend volume mount", f"cd {remote_root}/deploy && docker compose ps backend && docker inspect ai-supermarket-backend --format '{{{{json .Mounts}}}}'")
    run(ssh, "container /app/WXcert", "docker exec ai-supermarket-backend sh -lc 'ls -la /app/WXcert || true'")
    run(ssh, "search pem on host (shallow)", "find /root -maxdepth 4 -type f \\( -name '*.pem' -o -name '*.key' \\) 2>/dev/null | head -n 50")
    run(ssh, "search wechat key filenames", "echo '== apiclient_key.pem ==' && find / -type f -name apiclient_key.pem 2>/dev/null | head -n 20 && echo '== pub_key.pem ==' && find / -type f -name pub_key.pem 2>/dev/null | head -n 20 && echo '== wechatpay*.pem ==' && find / -type f -name 'wechatpay*.pem' 2>/dev/null | head -n 20", timeout=120)

    ssh.close()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

