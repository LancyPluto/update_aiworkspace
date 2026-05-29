#!/usr/bin/env python3
"""Upload project and bootstrap production on remote server."""
from __future__ import annotations

import os
import stat
import sys
import tarfile
import tempfile
from pathlib import Path

import paramiko

ROOT = Path(__file__).resolve().parents[2]
REMOTE_DIR = "/root/ai_tool_market"
EXCLUDE_DIRS = {
    ".git",
    "node_modules",
    "target",
    "__pycache__",
    ".next",
    "dist",
    ".venv",
    "venv",
    ".pytest_cache",
    ".mypy_cache",
    ".cursor",
    ".claude",
}
EXCLUDE_SUFFIXES = {".pyc", ".pyo", ".class", ".log"}


def should_skip(path: Path, rel: Path) -> bool:
    parts = set(rel.parts)
    if parts & EXCLUDE_DIRS:
        return True
    if any(p.startswith(".") and p not in {".env", ".env.example", ".env.development"} for p in rel.parts if p != rel.name):
        if rel.name.startswith(".") and rel.name not in {".env", ".env.example", ".env.development"}:
            pass
    for part in rel.parts:
        if part in EXCLUDE_DIRS:
            return True
    if rel.suffix in EXCLUDE_SUFFIXES:
        return True
    if rel.name.endswith(".tar.gz"):
        return True
    return False


def build_archive() -> Path:
    tmp = tempfile.NamedTemporaryFile(suffix=".tar.gz", delete=False)
    tmp.close()
    archive_path = Path(tmp.name)
    print(f"Creating archive {archive_path} ...")
    with tarfile.open(archive_path, "w:gz") as tar:
        for path in ROOT.rglob("*"):
            if not path.is_file():
                continue
            rel = path.relative_to(ROOT)
            if should_skip(path, rel):
                continue
            tar.add(path, arcname=str(rel).replace("\\", "/"))
    size_mb = archive_path.stat().st_size / (1024 * 1024)
    print(f"Archive size: {size_mb:.1f} MB")
    return archive_path


def run_remote(ssh: paramiko.SSHClient, cmd: str, timeout: int = 600) -> tuple[int, str, str]:
    print(f"$ {cmd}")
    _, stdout, stderr = ssh.exec_command(cmd, timeout=timeout)
    out = stdout.read().decode("utf-8", errors="replace")
    err = stderr.read().decode("utf-8", errors="replace")
    code = stdout.channel.recv_exit_status()
    if out.strip():
        print(out.rstrip())
    if err.strip():
        print(err.rstrip(), file=sys.stderr)
    return code, out, err


def main() -> int:
    host = os.environ.get("DEPLOY_HOST", "8.134.93.203")
    user = os.environ.get("DEPLOY_USER", "root")
    password = os.environ.get("DEPLOY_PASSWORD")
    if not password:
        print("Set DEPLOY_PASSWORD env var", file=sys.stderr)
        return 1

    archive = build_archive()
    try:
        ssh = paramiko.SSHClient()
        ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
        print(f"Connecting to {user}@{host} ...")
        ssh.connect(host, username=user, password=password, timeout=30)

        sftp = ssh.open_sftp()
        remote_tar = "/tmp/ai_tool_market_deploy.tar.gz"
        print(f"Uploading to {remote_tar} ...")
        sftp.put(str(archive), remote_tar)
        sftp.close()

        cmds = [
            f"mkdir -p {REMOTE_DIR}",
            f"rm -rf {REMOTE_DIR}/*",
            f"tar -xzf {remote_tar} -C {REMOTE_DIR}",
            f"rm -f {remote_tar}",
        ]
        for c in cmds:
            code, _, _ = run_remote(ssh, c, timeout=120)
            if code != 0:
                return code

        # Production env overrides
        prod_env = f"""
# --- production overrides (deploy script) ---
VITE_API_BASE_URL=
VITE_DEV_PROXY_TARGET=http://backend:8080
NGINX_HTTP_PORT=80
APP_PRODUCTION_MODE=true
JWT_SECRET=prod-{host.replace('.', '-')}-jwt-secret-change-me
""".strip()
        run_remote(
            ssh,
            f"grep -q 'production overrides' {REMOTE_DIR}/.env 2>/dev/null || cat >> {REMOTE_DIR}/.env << 'ENVEOF'\n{prod_env}\nENVEOF",
        )

        # Install docker if missing
        run_remote(
            ssh,
            "command -v docker >/dev/null 2>&1 || (curl -fsSL https://get.docker.com | sh && systemctl enable docker && systemctl start docker)",
            timeout=900,
        )
        run_remote(ssh, "docker compose version >/dev/null 2>&1 || (apt-get update -qq && apt-get install -y -qq docker-compose-plugin 2>/dev/null || yum install -y docker-compose-plugin 2>/dev/null || true)")

        # Stop host nginx if it conflicts with port 80 (docker nginx will bind 80)
        run_remote(ssh, "systemctl stop nginx 2>/dev/null || true")
        run_remote(ssh, "systemctl disable nginx 2>/dev/null || true")

        deploy_cmd = (
            f"cd {REMOTE_DIR}/deploy && "
            "docker compose -f docker-compose.yml -f docker-compose.nginx.yml pull -q 2>/dev/null; "
            "docker compose -f docker-compose.yml -f docker-compose.nginx.yml up -d --build"
        )
        code, _, _ = run_remote(ssh, deploy_cmd, timeout=3600)
        if code != 0:
            print("docker compose up failed", file=sys.stderr)
            return code

        run_remote(ssh, f"cd {REMOTE_DIR}/deploy && docker compose -f docker-compose.yml -f docker-compose.nginx.yml ps")
        run_remote(ssh, "curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1/ || true")
        ssh.close()
        print("Deploy finished.")
        return 0
    finally:
        try:
            os.unlink(archive)
        except OSError:
            pass


if __name__ == "__main__":
    raise SystemExit(main())
