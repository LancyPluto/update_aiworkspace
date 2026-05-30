#!/usr/bin/env python3
"""Fix customer-service QR on remote: nginx /generated/, kf.jpg, admin setting."""
from __future__ import annotations

import json
import os
import re
import sys
import urllib.error
import urllib.request
from pathlib import Path

import paramiko

ROOT = Path(__file__).resolve().parents[2]
REMOTE = "/root/ai_tool_market"
KF_LOCAL = ROOT / "user-web" / "asset" / "kf.jpg"
NGINX_CONF = ROOT / "deploy" / "nginx" / "default.conf"


def resolve_password() -> str | None:
    password = os.environ.get("DEPLOY_PASSWORD")
    if password:
        return password
    remote_py = ROOT / "_remote.py"
    if not remote_py.is_file():
        return None
    text = remote_py.read_text(encoding="utf-8")
    match = re.search(r"connect\([^,]+,\s*\d+,\s*[^,]+,\s*'([^']+)'", text)
    return match.group(1) if match else None


def post_json(url: str, payload: dict, token: str | None = None) -> dict:
    data = json.dumps(payload, ensure_ascii=False).encode("utf-8")
    headers = {"Content-Type": "application/json; charset=utf-8"}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    req = urllib.request.Request(url, data=data, headers=headers, method="POST")
    with urllib.request.urlopen(req, timeout=60) as resp:
        return json.loads(resp.read().decode("utf-8"))


def put_json(url: str, payload: dict, token: str) -> dict:
    data = json.dumps(payload, ensure_ascii=False).encode("utf-8")
    req = urllib.request.Request(
        url,
        data=data,
        headers={
            "Content-Type": "application/json; charset=utf-8",
            "Authorization": f"Bearer {token}",
        },
        method="PUT",
    )
    with urllib.request.urlopen(req, timeout=60) as resp:
        return json.loads(resp.read().decode("utf-8"))


def upload_file(sftp: paramiko.SFTPClient, local: Path, remote: str) -> None:
    remote_dir = str(Path(remote).parent).replace("\\", "/")
    cur = ""
    for part in remote_dir.split("/"):
        if not part:
            continue
        cur = f"{cur}/{part}" if cur else f"/{part}"
        try:
            sftp.mkdir(cur)
        except OSError:
            pass
    with sftp.open(remote, "wb") as rf:
        rf.write(local.read_bytes())


def main() -> int:
    password = resolve_password()
    host = os.environ.get("DEPLOY_HOST", "8.134.93.203")
    base = f"http://{host}"
    if not password:
        print("Set DEPLOY_PASSWORD or provide _remote.py", file=sys.stderr)
        return 1
    if not KF_LOCAL.is_file():
        print(f"Missing {KF_LOCAL}", file=sys.stderr)
        return 1
    if not NGINX_CONF.is_file():
        print(f"Missing {NGINX_CONF}", file=sys.stderr)
        return 1

    ssh = paramiko.SSHClient()
    ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    ssh.connect(host, username="root", password=password, timeout=30)
    sftp = ssh.open_sftp()

    print("Upload kf.jpg ...")
    upload_file(sftp, KF_LOCAL, f"{REMOTE}/user-web/asset/kf.jpg")

    print("Upload nginx default.conf ...")
    upload_file(sftp, NGINX_CONF, f"{REMOTE}/deploy/nginx/default.conf")

    print("Upload AppShell.vue ...")
    upload_file(
        sftp,
        ROOT / "user-web" / "src" / "components" / "AppShell.vue",
        f"{REMOTE}/user-web/src/components/AppShell.vue",
    )

    sftp.close()

    _, stdout, _ = ssh.exec_command(
        f"cd {REMOTE}/deploy && docker compose -f docker-compose.yml -f docker-compose.nginx.yml "
        "exec -T nginx nginx -s reload 2>/dev/null || "
        "docker compose -f docker-compose.yml -f docker-compose.nginx.yml up -d --force-recreate nginx",
        timeout=120,
    )
    print(stdout.read().decode())

    print("Login admin and set customerService.qrCodeUrl=/kf.jpg ...")
    try:
        login = post_json(
            f"{base}/api/admin/v1/auth/login",
            {"account": os.environ.get("IMPORT_ADMIN_ACCOUNT", "admin"), "password": os.environ.get("IMPORT_ADMIN_PASSWORD", "123456")},
        )
        token = login["data"]["accessToken"]
        put_json(
            f"{base}/api/admin/v1/settings",
            {"settings": {"customerService.qrCodeUrl": "/kf.jpg"}},
            token,
        )
    except urllib.error.HTTPError as e:
        print(f"Admin settings update failed: {e.read().decode()[:500]}", file=sys.stderr)
        ssh.close()
        return 1

    _, stdout, _ = ssh.exec_command(
        f"curl -s -o /dev/null -w 'kf:%{{http_code}}\\n' {base}/kf.jpg; "
        f"curl -s {base}/api/v1/settings/customer-service | head -c 400",
        timeout=30,
    )
    print(stdout.read().decode())
    ssh.close()
    print("Done. Hard-refresh user site and open 联系客服.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
