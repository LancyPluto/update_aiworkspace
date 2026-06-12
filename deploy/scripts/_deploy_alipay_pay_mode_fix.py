#!/usr/bin/env python3
from __future__ import annotations

import os
import re
import sys
from pathlib import Path

import paramiko

ROOT = Path(__file__).resolve().parents[2]
REMOTE = "/root/ai_tool_market"
FILES = [
    "backend/src/main/java/com/aiminilab/aitoolmarket/credit/dto/RechargePaymentOptionsResponse.java",
    "backend/src/main/java/com/aiminilab/aitoolmarket/credit/service/CreditRechargeService.java",
    "backend/src/main/java/com/aiminilab/aitoolmarket/credit/service/impl/CreditRechargeServiceImpl.java",
    "backend/src/main/java/com/aiminilab/aitoolmarket/credit/controller/CreditController.java",
    "backend/src/main/java/com/aiminilab/aitoolmarket/credit/alipay/DefaultAlipayPagePayClient.java",
    "backend/src/main/resources/application.yml",
    "backend/src/test/java/com/aiminilab/aitoolmarket/credit/CreditRechargeApiTest.java",
    "user-web/src/pages/Billing/RechargeSection.vue",
    "user-web/src/components/CreditRechargeModal.vue",
    "user-web/src/api/creditApi.ts",
    "user-web/src/utils/rechargePayment.ts",
    ".env.example",
]


def patch_remote_env(sftp: paramiko.SFTPClient) -> None:
    env_path = f"{REMOTE}/.env"
    with sftp.open(env_path, "r") as remote_file:
        content = remote_file.read().decode("utf-8", errors="replace")
    if re.search(r"^ALIPAY_PAY_MODE=.*$", content, flags=re.MULTILINE):
        content = re.sub(r"^ALIPAY_PAY_MODE=.*$", "ALIPAY_PAY_MODE=PRECREATE", content, flags=re.MULTILINE)
    else:
        content = content.rstrip() + "\nALIPAY_PAY_MODE=PRECREATE\n"
    with sftp.open(env_path, "w") as remote_file:
        remote_file.write(content.replace("\r\n", "\n").encode("utf-8"))
    print("patched remote .env ALIPAY_PAY_MODE=PRECREATE")


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

    for rel in FILES:
        local = ROOT / rel
        remote_path = f"{REMOTE}/{rel}"
        remote_dir = os.path.dirname(remote_path)
        try:
            sftp.stat(remote_dir)
        except OSError:
            parts = remote_dir.split("/")
            current = ""
            for part in parts:
                if not part:
                    continue
                current += "/" + part
                try:
                    sftp.stat(current)
                except OSError:
                    sftp.mkdir(current)
        with sftp.open(remote_path, "wb") as remote_file:
            remote_file.write(local.read_bytes().replace(b"\r\n", b"\n"))
        print(f"uploaded {rel}")

    patch_remote_env(sftp)
    sftp.close()

    cmd = (
        "cd /root/ai_tool_market/deploy && "
        "docker compose -f docker-compose.yml -f docker-compose.nginx.yml build backend user-web && "
        "docker compose -f docker-compose.yml -f docker-compose.nginx.yml up -d --force-recreate backend user-web nginx"
    )
    _, stdout, stderr = ssh.exec_command(cmd, timeout=900)
    out = stdout.read().decode("utf-8", errors="replace")
    err = stderr.read().decode("utf-8", errors="replace")
    print((out + err)[-8000:])
    ssh.close()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
