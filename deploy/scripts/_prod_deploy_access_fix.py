#!/usr/bin/env python3
"""Sync access-fix files, restart frontends/nginx, setup mihomo, verify HTTP codes."""
from __future__ import annotations

import json
import os
import sys
import time
from pathlib import Path

import paramiko

ROOT = Path(__file__).resolve().parents[2]
REMOTE = "/root/ai_tool_market"
HOST = os.environ.get("DEPLOY_HOST", "8.134.93.203")
USER = os.environ.get("DEPLOY_USER", "root")
PASSWORD = os.environ.get("DEPLOY_PASSWORD", "")
MIHOMO_SUB = os.environ.get(
    "MIHOMO_SUB_URL",
    "https://47.112.97.173:5000/api/v1/client/subscribe?token=cc130443085664d48d8164bfaad78e92",
)

SYNC_FILES = [
    "user-web/vite.config.ts",
    "deploy/docker-compose.yml",
    "deploy/nginx/default.conf",
    "deploy/docker-compose.nginx.yml",
    "deploy/scripts/_prod_setup_mihomo.py",
]


def run(ssh: paramiko.SSHClient, title: str, cmd: str, timeout: int = 600) -> int:
    print(f"\n=== {title} ===")
    _, stdout, stderr = ssh.exec_command(cmd, timeout=timeout)
    out = stdout.read().decode("utf-8", errors="replace")
    err = stderr.read().decode("utf-8", errors="replace")
    code = stdout.channel.recv_exit_status()
    if out:
        print(out.rstrip())
    if err:
        print(err.rstrip(), file=sys.stderr)
    print(f"[exit={code}]")
    return code


def sync_files(ssh: paramiko.SSHClient) -> None:
    sftp = ssh.open_sftp()
    for rel in SYNC_FILES:
        local = ROOT / rel
        remote = f"{REMOTE}/{rel.replace(chr(92), '/')}"
        remote_dir = str(Path(remote).parent).replace("\\", "/")
        parts = remote_dir.split("/")
        cur = ""
        for part in parts:
            if not part:
                continue
            cur = f"{cur}/{part}" if cur else f"/{part}"
            try:
                sftp.mkdir(cur)
            except OSError:
                pass
        data = local.read_bytes().replace(b"\r\n", b"\n")
        with sftp.open(remote, "wb") as target:
            target.write(data)
        print(f"synced {rel}")
    sftp.close()


def main() -> int:
    if not PASSWORD:
        print("DEPLOY_PASSWORD required", file=sys.stderr)
        return 1

    ssh = paramiko.SSHClient()
    ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    ssh.connect(HOST, username=USER, password=PASSWORD, timeout=30, allow_agent=False, look_for_keys=False)

    sync_files(ssh)
    run(
        ssh,
        "recreate user-web + nginx",
        f"cd {REMOTE}/deploy && docker compose -f docker-compose.yml -f docker-compose.nginx.yml "
        "up -d --force-recreate user-web nginx",
        timeout=900,
    )
    print("waiting for user-web build/preview...")
    for i in range(36):
        time.sleep(10)
        code = run(
            ssh,
            f"readiness {i + 1}/36",
            "curl -s -o /dev/null -w 'preview_host:%{http_code}\\n' -H 'Host: wlcloudai.com' --max-time 8 http://127.0.0.1:5173/; "
            "curl -s -o /dev/null -w 'nginx_host:%{http_code}\\n' -H 'Host: wlcloudai.com' --max-time 8 http://127.0.0.1/",
            timeout=30,
        )
        if code == 0:
            _, stdout, _ = ssh.exec_command(
                "curl -s -o /dev/null -w '%{http_code}' -H 'Host: wlcloudai.com' --max-time 8 http://127.0.0.1:5173/",
                timeout=20,
            )
            preview_code = stdout.read().decode().strip()
            if preview_code == "200":
                print("user-web preview ready")
                break
    else:
        print("user-web did not become ready in time", file=sys.stderr)
        ssh.close()
        return 1

    run(
        ssh,
        "external-style checks",
        "curl -s -o /dev/null -w 'root:%{http_code}\\n' --max-time 15 http://127.0.0.1/; "
        "curl -s -o /dev/null -w 'admin:%{http_code}\\n' -L --max-time 20 http://127.0.0.1/admin; "
        "curl -s -o /dev/null -w 'healthz:%{http_code}\\n' --max-time 10 http://127.0.0.1/healthz",
    )

    ssh.close()

    env = os.environ.copy()
    env["DEPLOY_PASSWORD"] = PASSWORD
    env["DEPLOY_HOST"] = HOST
    env["MIHOMO_SUB_URL"] = MIHOMO_SUB
    import subprocess

    mihomo_script = ROOT / "deploy" / "scripts" / "_prod_setup_mihomo.py"
    print("\n=== setup mihomo ===")
    result = subprocess.run([sys.executable, str(mihomo_script)], env=env, check=False)
    if result.returncode != 0:
        return result.returncode

    ssh.connect(HOST, username=USER, password=PASSWORD, timeout=30, allow_agent=False, look_for_keys=False)
    run(
        ssh,
        "post-mihomo site checks",
        "curl -s -o /dev/null -w 'root:%{http_code}\\n' --max-time 15 http://127.0.0.1/; "
        "curl -s -o /dev/null -w 'admin:%{http_code}\\n' -L --max-time 20 http://127.0.0.1/admin",
    )
    ssh.close()

    print("\n=== remote curl from local ===")
    import urllib.request

    for url in ("http://wlcloudai.com/", "http://wlcloudai.com/admin", "http://8.134.93.203/"):
        try:
            req = urllib.request.Request(url, method="HEAD")
            with urllib.request.urlopen(req, timeout=15) as resp:
                print(f"{url} -> {resp.status}")
        except Exception as exc:
            print(f"{url} -> ERROR {exc}")

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
