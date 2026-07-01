#!/usr/bin/env python3
"""Restore WeChat/Alipay payment config on production from env backup + git WXcert."""
from __future__ import annotations

import os
import re
import sys

import paramiko

BACKUP_PATH = "/root/ai_tool_market/.env.bak.pre-oss-migration"
ENV_PATHS = [
    "/root/ai_tool_market/.env",
    "/root/ai_tool_market/deploy/.env",
]
WXCERT_DIR = "/root/ai_tool_market/WXcert"
WXCERT_COMMIT = "a1e2deaa80dda0440ea4a463a14870af2e06db29"
WXCERT_FILES = ("apiclient_key.pem", "pub_key.pem", "apiclient_cert.pem")

PAYMENT_KEY_PREFIXES = ("WECHAT_", "ALIPAY_")


def parse_env(text: str) -> dict[str, str]:
    out: dict[str, str] = {}
    for line in text.splitlines():
        if "=" not in line or line.strip().startswith("#"):
            continue
        key, value = line.split("=", 1)
        out[key.strip()] = value
    return out


def merge_payment_keys(existing_lines: list[str], payment: dict[str, str]) -> str:
    by_key: dict[str, str] = {}
    for line in existing_lines:
        if "=" not in line or line.strip().startswith("#"):
            continue
        key = line.split("=", 1)[0].strip()
        by_key[key] = line
    for key, value in payment.items():
        if any(key.startswith(prefix) for prefix in PAYMENT_KEY_PREFIXES):
            by_key[key] = f"{key}={value}"
    return "\n".join(by_key.values()) + "\n"


def main() -> int:
    password = os.environ.get("DEPLOY_PASSWORD")
    host = os.environ.get("DEPLOY_HOST", "8.134.93.203")
    if not password:
        print("DEPLOY_PASSWORD required", file=sys.stderr)
        return 1

    ssh = paramiko.SSHClient()
    ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    ssh.connect(host, username="root", password=password, timeout=30)
    sftp = ssh.open_sftp()

    with sftp.open(BACKUP_PATH, "rb") as remote_file:
        backup = parse_env(remote_file.read().decode("utf-8", errors="replace"))
    payment = {
        key: value
        for key, value in backup.items()
        if any(key.startswith(prefix) for prefix in PAYMENT_KEY_PREFIXES)
    }
    if not payment:
        print("no payment keys found in backup", file=sys.stderr)
        return 1

    for path in ENV_PATHS:
        try:
            with sftp.open(path, "rb") as remote_file:
                current = remote_file.read().decode("utf-8", errors="replace").splitlines()
        except FileNotFoundError:
            current = []
        merged = merge_payment_keys(current, payment)
        with sftp.open(path, "wb") as remote_file:
            remote_file.write(merged.encode("utf-8"))
        print(f"restored {len(payment)} payment keys into {path}")

    sftp.close()

    restore_certs = (
        f"mkdir -p {WXCERT_DIR} && "
        f"cd /root/ai_tool_market && "
        + " && ".join(
            f"git show {WXCERT_COMMIT}:WXcert/{name} > {WXCERT_DIR}/{name}"
            for name in WXCERT_FILES
        )
        + f" && chmod 600 {WXCERT_DIR}/*.pem && ls -la {WXCERT_DIR}"
    )
    _, stdout, stderr = ssh.exec_command(restore_certs, timeout=60)
    cert_out = stdout.read().decode("utf-8", errors="replace")
    cert_err = stderr.read().decode("utf-8", errors="replace")
    print(cert_out.strip() or cert_err.strip() or "restored WXcert files")

    cmd = (
        "cd /root/ai_tool_market/deploy && "
        "docker compose -f docker-compose.yml -f docker-compose.nginx.yml up -d --force-recreate backend && "
        "sleep 45 && "
        "docker ps --filter name=ai-supermarket-backend --format '{{.Status}}' && "
        "docker exec ai-supermarket-backend sh -c '"
        "test -f /app/WXcert/apiclient_key.pem && test -f /app/WXcert/pub_key.pem && echo WXcert_ok"
        "' && "
        "curl -sf http://127.0.0.1:8080/api/health && echo"
    )
    _, stdout, stderr = ssh.exec_command(cmd, timeout=120)
    out = stdout.read().decode("utf-8", errors="replace")
    err = stderr.read().decode("utf-8", errors="replace")
    print(out)
    if err.strip():
        print(err, file=sys.stderr)

    verify = (
        "docker exec ai-supermarket-backend printenv "
        "WECHAT_NATIVE_PAY_ENABLED ALIPAY_PAGE_PAY_ENABLED WECHAT_PAY_APPID ALIPAY_APP_ID "
        "| sed 's/=.*/=***/'"
    )
    _, stdout, _ = ssh.exec_command(verify, timeout=30)
    print(stdout.read().decode("utf-8", errors="replace"))
    ssh.close()
    return 0 if "SUCCESS" in out or '"code":"SUCCESS"' in out else 1


if __name__ == "__main__":
    raise SystemExit(main())
