#!/usr/bin/env python3
"""Deploy batch-2 fixes: worker proxy, media sync, admin rebuild, service restart."""
from __future__ import annotations

import os
import subprocess
import sys
import tarfile
import tempfile
from pathlib import Path

import paramiko

ROOT = Path(__file__).resolve().parents[2]
HOST = os.environ.get("DEPLOY_HOST", "8.134.93.203")
USER = os.environ.get("DEPLOY_USER", "root")
PASSWORD = os.environ.get("DEPLOY_PASSWORD", "")
REMOTE = "/root/ai_tool_market"


def run_local(cmd: list[str], cwd: Path | None = None) -> None:
    print("LOCAL:", " ".join(cmd))
    subprocess.run(cmd, cwd=cwd or ROOT, check=True)


def ssh() -> paramiko.SSHClient:
    if not PASSWORD:
        raise SystemExit("DEPLOY_PASSWORD required")
    client = paramiko.SSHClient()
    client.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    client.connect(HOST, username=USER, password=PASSWORD, timeout=30)
    return client


def remote_exec(client: paramiko.SSHClient, cmd: str) -> None:
    print("REMOTE:", cmd[:200])
    _, stdout, stderr = client.exec_command(cmd, timeout=1800)
    out = stdout.read().decode()
    err = stderr.read().decode()
    code = stdout.channel.recv_exit_status()
    if out.strip():
        print(out.rstrip())
    if err.strip():
        print(err.rstrip(), file=sys.stderr)
    if code != 0:
        raise SystemExit(f"remote command failed: {code}")


def upload_tree(client: paramiko.SSHClient, local_dir: Path, remote_dir: str) -> None:
    if not local_dir.exists():
        print(f"skip missing dir {local_dir}")
        return
    tmp = tempfile.NamedTemporaryFile(suffix=".tar.gz", delete=False)
    tmp.close()
    archive = Path(tmp.name)
    try:
        with tarfile.open(archive, "w:gz") as tar:
            for path in local_dir.rglob("*"):
                if path.is_file():
                    tar.add(path, arcname=str(path.relative_to(local_dir)).replace("\\", "/"))
        remote_tar = "/tmp/generated_media_sync.tar.gz"
        sftp = client.open_sftp()
        sftp.put(str(archive), remote_tar)
        sftp.close()
        remote_exec(
            client,
            f"mkdir -p {remote_dir} && tar -xzf {remote_tar} -C {remote_dir} && rm -f {remote_tar}",
        )
    finally:
        archive.unlink(missing_ok=True)


def upload_files(client: paramiko.SSHClient, pairs: list[tuple[Path, str]]) -> None:
    sftp = client.open_sftp()
    for local, remote in pairs:
        data = local.read_bytes().replace(b"\r\n", b"\n")
        remote_dir = os.path.dirname(remote).replace("\\", "/")
        try:
            sftp.stat(remote_dir)
        except OSError:
            client.exec_command(f"mkdir -p '{remote_dir}'")
        with sftp.open(remote, "wb") as handle:
            handle.write(data)
        print(f"uploaded {local.name} -> {remote}")
    sftp.close()


def main() -> int:
    run_local(["python", "-m", "pytest", "tests/test_openai_images_client.py", "-q"], cwd=ROOT / "worker")
    client = ssh()
    try:
        upload_files(
            client,
            [
                (ROOT / "worker/client/openai_images_client.py", f"{REMOTE}/worker/client/openai_images_client.py"),
                (ROOT / "backend/src/main/java/com/aiminilab/aitoolmarket/config/AuthInterceptor.java",
                 f"{REMOTE}/backend/src/main/java/com/aiminilab/aitoolmarket/config/AuthInterceptor.java"),
                (ROOT / "backend/src/main/java/com/aiminilab/aitoolmarket/config/CorsConfig.java",
                 f"{REMOTE}/backend/src/main/java/com/aiminilab/aitoolmarket/config/CorsConfig.java"),
                (ROOT / "admin-frontend/lib/api/auth.ts", f"{REMOTE}/admin-frontend/lib/api/auth.ts"),
                (ROOT / "admin-frontend/lib/api/http.ts", f"{REMOTE}/admin-frontend/lib/api/http.ts"),
                (ROOT / "deploy/docker-compose.yml", f"{REMOTE}/deploy/docker-compose.yml"),
            ],
        )
        upload_tree(client, ROOT / "data/generated-media", f"{REMOTE}/data/generated-media")
        remote_exec(
            client,
            f"grep -q '^HTTP_PROXY=' {REMOTE}/.env || echo 'HTTP_PROXY=http://host.docker.internal:7890' >> {REMOTE}/.env; "
            f"grep -q '^HTTPS_PROXY=' {REMOTE}/.env || echo 'HTTPS_PROXY=http://host.docker.internal:7890' >> {REMOTE}/.env; "
            f"grep -q '^NO_PROXY=' {REMOTE}/.env || echo 'NO_PROXY=localhost,127.0.0.1,backend,mysql,redis,rabbitmq,agent-service,nginx,admin-frontend,user-web' >> {REMOTE}/.env",
        )
        remote_exec(
            client,
            f"cd {REMOTE}/deploy && docker compose -f docker-compose.yml build backend worker && "
            "docker volume rm deploy_admin_frontend_next 2>/dev/null || true && "
            "docker compose -f docker-compose.yml -f docker-compose.nginx.yml up -d --force-recreate backend worker admin-frontend nginx",
        )
        remote_exec(
            client,
            "sleep 90 && curl -s -o /dev/null -w 'admin:%{http_code}\\n' https://wlcloudai.com/admin && "
            "curl -s -o /dev/null -w 'user:%{http_code}\\n' https://wlcloudai.com/ && "
            "docker logs ai-supermarket-worker --tail 5 2>&1 | tail -3",
        )
    finally:
        client.close()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
