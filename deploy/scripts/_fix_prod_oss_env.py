#!/usr/bin/env python3
"""Restore OSS credentials on production from backup and restart backend."""
from __future__ import annotations

import os
import sys

import paramiko

RESTORE_KEYS = [
    "ALIYUN_CAPTCHA_ACCESS_KEY_ID",
    "ALIYUN_CAPTCHA_ACCESS_KEY_SECRET",
    "OSS_ENDPOINT",
    "OSS_BUCKET",
    "OSS_KEY_PREFIX",
    "OSS_PUBLIC_BUCKET",
    "OSS_PRIVATE_BUCKET",
    "OSS_LEGACY_BUCKET",
    "MODEL_PROVIDER",
    "MODEL_API_BASE_URL",
    "MODEL_API_KEY",
    "MODEL_NAME",
    "AGENT_ENABLED",
    "AGENT_SERVICE_BASE_URL",
    "RABBITMQ_USERNAME",
    "RABBITMQ_PASSWORD",
    "TASK_QUEUE_BACKEND",
    "SILICONFLOW_API_KEY",
    "SILICONFLOW_BASE_URL",
    "SILICONFLOW_VOICE_MODEL",
    "SILICONFLOW_ASR_MODEL",
    "SILICONFLOW_IMAGE_MODEL",
    "WECHAT_NATIVE_PAY_ENABLED",
    "WECHAT_PAY_APPID",
    "WECHAT_PAY_MCHID",
    "WECHAT_PAY_MERCHANT_SERIAL_NO",
    "WECHAT_PAY_MERCHANT_PRIVATE_KEY_PATH",
    "WECHAT_PAY_API_V3_KEY",
    "WECHAT_PAY_PUBLIC_KEY_ID",
    "WECHAT_PAY_PUBLIC_KEY_PATH",
    "WECHAT_PAY_NOTIFY_URL",
    "WECHAT_PAY_API_BASE_URL",
    "ALIPAY_PAGE_PAY_ENABLED",
    "ALIPAY_APP_ID",
    "ALIPAY_MERCHANT_PRIVATE_KEY",
    "ALIPAY_PUBLIC_KEY",
    "ALIPAY_NOTIFY_URL",
    "ALIPAY_RETURN_URL",
    "ALIPAY_GATEWAY_URL",
]

EXTRA_DEFAULTS = {
    "OSS_PUBLIC_BUCKET": "wlcloudai-assets-public",
    "OSS_PRIVATE_BUCKET": "wlcloudai-assets-private",
    "OSS_LEGACY_BUCKET": "wlcloudai-assets-prod",
}

BACKUP_PATH = "/root/ai_tool_market/.env.bak.pre-oss-migration"
ENV_PATHS = [
    "/root/ai_tool_market/.env",
    "/root/ai_tool_market/deploy/.env",
]


def parse_env(text: str) -> dict[str, str]:
    out: dict[str, str] = {}
    for line in text.splitlines():
        if "=" not in line or line.strip().startswith("#"):
            continue
        key, value = line.split("=", 1)
        out[key.strip()] = value
    return out


def merge_env(existing_lines: list[str], sources: dict[str, str]) -> str:
    by_key: dict[str, str] = {}
    for line in existing_lines:
        if "=" not in line or line.strip().startswith("#"):
            continue
        key = line.split("=", 1)[0].strip()
        by_key[key] = line
    for key in RESTORE_KEYS:
        value = sources.get(key) or EXTRA_DEFAULTS.get(key)
        if value:
            by_key[key] = f"{key}={value}"
    model_line = by_key.get("MODEL_API_KEY", "")
    model_value = model_line.split("=", 1)[1] if "=" in model_line else ""
    if not model_value or model_value.startswith("replace-with-"):
        sf_line = by_key.get("SILICONFLOW_API_KEY", "")
        sf_value = sf_line.split("=", 1)[1] if "=" in sf_line else sources.get("SILICONFLOW_API_KEY", "")
        if sf_value and not sf_value.startswith("replace-with-"):
            by_key["MODEL_API_KEY"] = f"MODEL_API_KEY={sf_value}"
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

    for path in ENV_PATHS:
        try:
            with sftp.open(path, "rb") as remote_file:
                current = remote_file.read().decode("utf-8", errors="replace").splitlines()
        except FileNotFoundError:
            current = []
        merged = merge_env(current, backup)
        with sftp.open(path, "wb") as remote_file:
            remote_file.write(merged.encode("utf-8"))
        print(f"merged OSS keys into {path}")

    sftp.close()

    cmd = (
        "cd /root/ai_tool_market/deploy && "
        "docker compose -f docker-compose.yml -f docker-compose.nginx.yml up -d --force-recreate backend worker agent-service && "
        "sleep 50 && "
        "docker ps --filter name=ai-supermarket-backend --format '{{.Status}}' && "
        "docker ps --filter name=ai-supermarket-worker --format '{{.Status}}' && "
        "docker ps --filter name=ai-supermarket-agent-service --format '{{.Status}}' && "
        "(curl -sf http://127.0.0.1:8080/api/health && echo ' backend health OK') || "
        "(echo 'backend health FAILED' && docker logs ai-supermarket-backend --tail 20 2>&1)"
    )
    _, stdout, stderr = ssh.exec_command(cmd, timeout=180)
    print(stdout.read().decode("utf-8", errors="replace"))
    err = stderr.read().decode("utf-8", errors="replace")
    if err.strip():
        print(err, file=sys.stderr)
    ssh.close()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
