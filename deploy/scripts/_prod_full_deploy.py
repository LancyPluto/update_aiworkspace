#!/usr/bin/env python3
"""Full sync dev -> production, fix .env, redeploy stack + mihomo, verify."""
from __future__ import annotations

import os
import subprocess
import sys
import time
from pathlib import Path

import paramiko

ROOT = Path(__file__).resolve().parents[2]
HOST = os.environ.get("DEPLOY_HOST", "8.134.93.203")
PASSWORD = os.environ.get("DEPLOY_PASSWORD", "")
MIHOMO_SUB = os.environ.get(
    "MIHOMO_SUB_URL",
    "https://47.112.97.173:5000/api/v1/client/subscribe?token=cc130443085664d48d8164bfaad78e92",
)

PROD_ENV_PATCH = """
APP_PRODUCTION_MODE=true
VITE_API_BASE_URL=
VITE_DEV_PROXY_TARGET=http://backend:8080
ADMIN_NEXT_PUBLIC_API_BASE_URL=
ADMIN_NEXT_PUBLIC_API_PROXY_TARGET=http://backend:8080
CORS_ALLOWED_ORIGINS=http://wlcloudai.com,http://www.wlcloudai.com,http://8.134.93.203
HTTP_PROXY=http://host.docker.internal:7890
HTTPS_PROXY=http://host.docker.internal:7890
NO_PROXY=localhost,127.0.0.1,mysql,redis,rabbitmq,backend,agent-service,admin-frontend,user-web,nginx,wlcloudai.com,8.134.93.203,.aliyuncs.com,.aliyun.com,.cn
""".strip()


def run_ssh(ssh: paramiko.SSHClient, title: str, cmd: str, timeout: int = 600) -> int:
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


def patch_prod_env(ssh: paramiko.SSHClient) -> None:
    patch_lines = [line for line in PROD_ENV_PATCH.splitlines() if "=" in line]
    patch_repr = repr(patch_lines)
    script = f"""
from pathlib import Path
path = Path("/root/ai_tool_market/.env")
lines = path.read_text(encoding="utf-8", errors="replace").splitlines() if path.exists() else []
patch_lines = {patch_repr}
patch = {{}}
for line in patch_lines:
    key, value = line.split("=", 1)
    patch[key] = value
keys = set(patch)
out = []
for line in lines:
    key = line.split("=", 1)[0].strip()
    if key in keys:
        continue
    out.append(line)
for key, value in patch.items():
    out.append(f"{{key}}={{value}}")
path.parent.mkdir(parents=True, exist_ok=True)
path.write_text("\\n".join(out) + "\\n", encoding="utf-8")
print("patched", path)
for key in sorted(patch):
    print(f"{{key}}={{patch[key]}}")
"""
    sftp = ssh.open_sftp()
    with sftp.open("/tmp/patch_prod_env.py", "w") as f:
        f.write(script)
    sftp.close()
    run_ssh(ssh, "patch production .env", "python3 /tmp/patch_prod_env.py")


def main() -> int:
    if not PASSWORD:
        print("DEPLOY_PASSWORD required", file=sys.stderr)
        return 1

    print("=== sync project to production ===")
    env = os.environ.copy()
    env["DEPLOY_PASSWORD"] = PASSWORD
    env["DEPLOY_HOST"] = HOST
    sync = subprocess.run([sys.executable, str(ROOT / "deploy/scripts/sync_to_server.py")], env=env)
    if sync.returncode != 0:
        return sync.returncode

    ssh = paramiko.SSHClient()
    ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    ssh.connect(HOST, username="root", password=PASSWORD, timeout=30, allow_agent=False, look_for_keys=False)

    patch_prod_env(ssh)
    run_ssh(
        ssh,
        "deploy stack",
        "cd /root/ai_tool_market/deploy && "
        "docker compose -f docker-compose.yml -f docker-compose.nginx.yml up -d --force-recreate "
        "user-web nginx admin-frontend backend worker agent-service",
        timeout=900,
    )

    print("waiting for user-web static build...")
    for i in range(40):
        time.sleep(15)
        _, stdout, _ = ssh.exec_command(
            "docker inspect --format '{{.State.Health.Status}}' ai-supermarket-user-web 2>/dev/null || echo missing",
            timeout=20,
        )
        health = stdout.read().decode().strip()
        print(f"  user-web health: {health}")
        if health == "healthy":
            break
    else:
        run_ssh(ssh, "user-web logs", "docker logs --tail 40 ai-supermarket-user-web")
        ssh.close()
        return 1

    run_ssh(
        ssh,
        "verify http",
        "curl -s -o /dev/null -w 'root:%{http_code}\\n' http://127.0.0.1/; "
        "curl -s -o /dev/null -w 'assets:%{http_code}\\n' http://127.0.0.1/assets/$(docker exec ai-supermarket-nginx ls /usr/share/nginx/user-web/assets 2>/dev/null | head -1); "
        "curl -s -o /dev/null -w 'admin:%{http_code}\\n' -L http://127.0.0.1/admin; "
        "curl -s -o /dev/null -w 'api:%{http_code}\\n' http://127.0.0.1/api/v1/tools",
    )
    ssh.close()

    mihomo = subprocess.run(
        [sys.executable, str(ROOT / "deploy/scripts/_prod_setup_mihomo.py")],
        env={**env, "MIHOMO_SUB_URL": MIHOMO_SUB, "MIHOMO_TUN": "false"},
    )
    if mihomo.returncode != 0:
        return mihomo.returncode

    ssh.connect(HOST, username="root", password=PASSWORD, timeout=30, allow_agent=False, look_for_keys=False)
    run_ssh(
        ssh,
        "proxy check",
        "curl -s -o /dev/null -w 'google_proxy:%{http_code}\\n' -x http://127.0.0.1:7890 -L --max-time 20 https://www.google.com; "
        "docker exec ai-supermarket-worker python -c \"import os,urllib.request; "
        "print('worker_proxy', os.environ.get('HTTP_PROXY','')); "
        "r=urllib.request.urlopen('https://www.google.com', timeout=25); print('status', r.status)\"",
        timeout=90,
    )
    ssh.close()

    import urllib.request

    print("\n=== external verify ===")
    for url in ("http://wlcloudai.com/", "http://wlcloudai.com/admin", "http://8.134.93.203/"):
        try:
            with urllib.request.urlopen(url, timeout=20) as resp:
                print(f"{url} -> {resp.status}")
        except Exception as exc:
            print(f"{url} -> ERROR {exc}")

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
