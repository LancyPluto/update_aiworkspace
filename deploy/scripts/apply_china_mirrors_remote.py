#!/usr/bin/env python3
"""Upload mirror configs and apply on remote server."""
from __future__ import annotations

import os
import sys
from pathlib import Path

import paramiko

ROOT = Path(__file__).resolve().parents[2]
DEPLOY = ROOT / "deploy"
REMOTE = "/root/ai_tool_market"


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

    def put(local: Path, remote: str) -> None:
        sftp.put(str(local), remote)
        print(f"uploaded {local.name} -> {remote}")

    _, stdout, _ = ssh.exec_command("mkdir -p /etc/docker /root/.m2 " + REMOTE + "/deploy/config " + REMOTE + "/deploy/scripts")
    stdout.channel.recv_exit_status()

    put(DEPLOY / "config" / "docker-daemon-china.json", "/etc/docker/daemon.json")
    put(DEPLOY / "config" / "maven-settings-china.xml", "/root/.m2/settings.xml")
    put(DEPLOY / "scripts" / "configure-china-mirrors.sh", f"{REMOTE}/deploy/scripts/configure-china-mirrors.sh")
    put(DEPLOY / "config" / "docker-daemon-china.json", f"{REMOTE}/deploy/config/docker-daemon-china.json")
    put(DEPLOY / "config" / "maven-settings-china.xml", f"{REMOTE}/deploy/config/maven-settings-china.xml")
    sftp.close()

    script = f"""
set -e
chmod +x {REMOTE}/deploy/scripts/configure-china-mirrors.sh
# daemon.json already uploaded; run rest of apt/pip/npm setup
bash {REMOTE}/deploy/scripts/configure-china-mirrors.sh
"""
    _, stdout, stderr = ssh.exec_command(script, timeout=300)
    out = stdout.read().decode()
    err = stderr.read().decode()
    code = stdout.channel.recv_exit_status()
    print(out)
    if err:
        print(err, file=sys.stderr)
    ssh.close()
    return code


if __name__ == "__main__":
    raise SystemExit(main())
