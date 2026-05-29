#!/usr/bin/env python3
"""Sync local project to server /root/ai_tool_market (tar upload)."""
from __future__ import annotations

import os
import sys
import tarfile
import tempfile
from pathlib import Path

import paramiko

ROOT = Path(__file__).resolve().parents[2]
REMOTE_DIR = "/root/ai_tool_market"
REMOTE_TAR = "/tmp/ai_tool_market_sync.tar.gz"

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
    ".idea",
}
EXCLUDE_SUFFIXES = {".pyc", ".pyo", ".class", ".log"}
PRESERVE_ON_SERVER = (".env", "engines/banana-slides/.env")


def should_skip(rel: Path) -> bool:
    if any(part in EXCLUDE_DIRS for part in rel.parts):
        return True
    if rel.suffix in EXCLUDE_SUFFIXES:
        return True
    if rel.name.endswith(".tar.gz"):
        return True
    if rel.name.startswith(".") and rel.name not in {
        ".env",
        ".env.example",
        ".env.development",
        ".gitignore",
        ".dockerignore",
    }:
        return True
    return False


def build_archive() -> Path:
    tmp = tempfile.NamedTemporaryFile(suffix=".tar.gz", delete=False)
    tmp.close()
    archive_path = Path(tmp.name)
    count = 0
    print(f"Creating archive...")
    with tarfile.open(archive_path, "w:gz") as tar:
        for path in ROOT.rglob("*"):
            if not path.is_file():
                continue
            rel = path.relative_to(ROOT)
            if should_skip(rel):
                continue
            tar.add(path, arcname=str(rel).replace("\\", "/"))
            count += 1
    size_mb = archive_path.stat().st_size / (1024 * 1024)
    print(f"Packed {count} files, {size_mb:.1f} MB")
    return archive_path


def main() -> int:
    password = os.environ.get("DEPLOY_PASSWORD")
    host = os.environ.get("DEPLOY_HOST", "8.134.93.203")
    user = os.environ.get("DEPLOY_USER", "root")
    if not password:
        print("Set DEPLOY_PASSWORD environment variable", file=sys.stderr)
        return 1

    archive = build_archive()
    try:
        ssh = paramiko.SSHClient()
        ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
        print(f"Connecting to {user}@{host}...")
        ssh.connect(host, username=user, password=password, timeout=30)

        sftp = ssh.open_sftp()
        print(f"Uploading to {REMOTE_TAR}...")
        sftp.put(str(archive), REMOTE_TAR)
        sftp.close()

        preserve_cmds = []
        restore_cmds = []
        for rel in PRESERVE_ON_SERVER:
            remote = f"{REMOTE_DIR}/{rel}"
            bak = f"/tmp/ai_tool_market_preserve_{rel.replace('/', '_')}"
            preserve_cmds.append(f"[ -f '{remote}' ] && cp '{remote}' '{bak}' || true")
            restore_cmds.append(
                f"[ -f '{bak}' ] && mkdir -p \"$(dirname '{remote}')\" && cp '{bak}' '{remote}' || true"
            )

        script = f"""
set -e
{' ; '.join(preserve_cmds)}
rm -rf {REMOTE_DIR}
mkdir -p {REMOTE_DIR}
tar -xzf {REMOTE_TAR} -C {REMOTE_DIR}
{' ; '.join(restore_cmds)}
rm -f {REMOTE_TAR}
echo "Sync done -> {REMOTE_DIR}"
ls -la {REMOTE_DIR} | head -15
"""
        _, stdout, stderr = ssh.exec_command(script, timeout=600)
        out = stdout.read().decode("utf-8", errors="replace")
        err = stderr.read().decode("utf-8", errors="replace")
        code = stdout.channel.recv_exit_status()
        if out.strip():
            print(out.rstrip())
        if err.strip():
            print(err.rstrip(), file=sys.stderr)
        ssh.close()
        if code != 0:
            print(f"Remote extract failed with code {code}", file=sys.stderr)
            return code
        print("Project synced successfully.")
        return 0
    finally:
        try:
            os.unlink(archive)
        except OSError:
            pass


if __name__ == "__main__":
    raise SystemExit(main())
