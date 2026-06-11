#!/usr/bin/env python3
"""Windows-friendly production deploy: git bundle sync + selective Docker rebuild."""
from __future__ import annotations

import os
import subprocess
import sys
import tempfile
from pathlib import Path

import paramiko

ROOT = Path(__file__).resolve().parents[2]
REMOTE_DIR = os.environ.get("REMOTE_DIR", "/root/ai_tool_market")
GIT_BRANCH = os.environ.get("DEPLOY_GIT_BRANCH", "dev")

_ENV_PATCH_LINES = [
    "APP_PRODUCTION_MODE=true",
    "VITE_API_BASE_URL=",
    "VITE_DEV_PROXY_TARGET=http://backend:8080",
    "ADMIN_NEXT_PUBLIC_API_BASE_URL=",
    "ADMIN_NEXT_PUBLIC_API_PROXY_TARGET=http://backend:8080",
    "CORS_ALLOWED_ORIGINS=http://wlcloudai.com,http://www.wlcloudai.com,http://8.134.93.203,https://wlcloudai.com,https://www.wlcloudai.com,https://8.134.93.203",
    "HTTP_PROXY=http://host.docker.internal:7890",
    "HTTPS_PROXY=http://host.docker.internal:7890",
    "NO_PROXY=localhost,127.0.0.1,mysql,redis,rabbitmq,backend,agent-service,admin-frontend,user-web,nginx,wlcloudai.com,8.134.93.203,.aliyuncs.com,.aliyun.com,.cn",
]
ENV_PATCH_SCRIPT = "\n".join(
    [
        "python3 - <<'PY'",
        "from pathlib import Path",
        'path = Path("/root/ai_tool_market/.env")',
        f"patch_lines = {repr(_ENV_PATCH_LINES)}",
        "patch = {}",
        "for line in patch_lines:",
        '    if "=" not in line:',
        "        continue",
        '    key, value = line.split("=", 1)',
        "    patch[key] = value",
        'lines = path.read_text(encoding="utf-8", errors="replace").splitlines() if path.exists() else []',
        "keys = set(patch)",
        "out = []",
        "for line in lines:",
        '    key = line.split("=", 1)[0].strip()',
        "    if key in keys:",
        "        continue",
        "    out.append(line)",
        "for key, value in patch.items():",
        '    out.append(f"{key}={value}")',
        "path.parent.mkdir(parents=True, exist_ok=True)",
        'path.write_text("\\n".join(out) + "\\n", encoding="utf-8")',
        'print("patched", path)',
        "PY",
    ]
)


def run_local(cmd: list[str], **kwargs) -> subprocess.CompletedProcess:
    opts = {"cwd": ROOT, "check": True, "text": True, "capture_output": True}
    opts.update(kwargs)
    return subprocess.run(cmd, **opts)


def detect_services() -> str:
    script = ROOT / "deploy/scripts/detect_deploy_services.sh"
    git_bash = os.environ.get("GIT_BASH", r"D:\tools\Git\bin\bash.exe")
    if script.exists() and Path(git_bash).exists():
        try:
            proc = subprocess.run(
                [git_bash, str(script)],
                cwd=ROOT,
                check=True,
                text=True,
                capture_output=True,
            )
            services = proc.stdout.strip()
            if services:
                return services
        except (subprocess.CalledProcessError, FileNotFoundError):
            pass
    return "backend worker agent-service admin-frontend user-web nginx"


def main() -> int:
    host = os.environ.get("DEPLOY_HOST", "8.134.93.203")
    user = os.environ.get("DEPLOY_USER", "root")
    password = os.environ.get("DEPLOY_PASSWORD")
    if not password:
        print("DEPLOY_PASSWORD required", file=sys.stderr)
        return 1

    git_ref = os.environ.get("DEPLOY_GIT_REF") or os.environ.get("GITHUB_SHA")
    if not git_ref:
        git_ref = run_local(["git", "rev-parse", "HEAD"]).stdout.strip()

    services = os.environ.get("DEPLOY_SERVICES") or detect_services()
    print(f"Deploy ref={git_ref} services={services}")

    with tempfile.NamedTemporaryFile(suffix=".bundle", delete=False) as tmp:
        bundle_path = tmp.name

    try:
        run_local(["git", "fetch", "origin", GIT_BRANCH], check=False)
        # Bundle must include history; a lone SHA produces an empty bundle on Windows git.
        bundle_ref = git_ref
        probe = subprocess.run(
            ["git", "bundle", "create", bundle_path, bundle_ref],
            cwd=ROOT,
            capture_output=True,
            text=True,
        )
        if probe.returncode != 0:
            bundle_ref = "HEAD"
            run_local(["git", "bundle", "create", bundle_path, bundle_ref])
            git_ref = run_local(["git", "rev-parse", "HEAD"]).stdout.strip()

        ssh = paramiko.SSHClient()
        ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
        ssh.connect(host, username=user, password=password, timeout=30, allow_agent=False, look_for_keys=False)

        sftp = ssh.open_sftp()
        remote_bundle = "/tmp/ai-tool-market-deploy.bundle"
        print("Uploading bundle ...")
        sftp.put(bundle_path, remote_bundle)
        sftp.close()

        remote_script = f"""set -euo pipefail
REMOTE_DIR="{REMOTE_DIR}"
GIT_BRANCH="{GIT_BRANCH}"
GIT_REF="{git_ref}"
BUNDLE="{remote_bundle}"
SERVICES="{services}"

git config --global --add safe.directory "$REMOTE_DIR" 2>/dev/null || true
chown -R root:root "$REMOTE_DIR" 2>/dev/null || true
mkdir -p "$REMOTE_DIR/deploy/logs"

for rel in .env engines/banana-slides/.env; do
  if [ -f "$REMOTE_DIR/$rel" ]; then
    cp "$REMOTE_DIR/$rel" "/tmp/preserve_${{rel//\\//_}}"
    echo "preserved $rel"
  fi
done

cd "$REMOTE_DIR"
if [ ! -d .git ]; then
  git init
  git config user.email "deploy@wlcloudai.com"
  git config user.name "Production Deploy"
  git remote add origin "https://github.com/AI-miniLab/ai-tool-market.git" 2>/dev/null || true
fi

OLD_SHA="$(git rev-parse HEAD 2>/dev/null || echo '')"
if ! git fetch "$BUNDLE" "$GIT_REF:refs/heads/deploy-target" 2>/dev/null; then
  if ! git fetch "$BUNDLE" "HEAD:refs/heads/deploy-target" 2>/dev/null; then
    git fetch "$BUNDLE" "refs/remotes/origin/$GIT_BRANCH:refs/heads/deploy-target"
  fi
fi
git checkout -B "$GIT_BRANCH" deploy-target -f
git reset --hard deploy-target
NEW_SHA="$(git rev-parse HEAD)"
echo "Synced $OLD_SHA -> $NEW_SHA"

for rel in .env engines/banana-slides/.env; do
  bak="/tmp/preserve_${{rel//\\//_}}"
  if [ -f "$bak" ]; then
    mkdir -p "$(dirname "$REMOTE_DIR/$rel")"
    cp "$bak" "$REMOTE_DIR/$rel"
    echo "restored $rel"
  fi
done

echo "$NEW_SHA" > .deploy_revision
printf 'manual\\n%s\\n\\n' "$GIT_BRANCH" > .deploy_meta
git log -1 --oneline
rm -f "$BUNDLE"

{ENV_PATCH_SCRIPT}

cd "$REMOTE_DIR/deploy"
COMPOSE_ARGS=(-f docker-compose.yml -f docker-compose.nginx.yml)
if echo "$SERVICES" | grep -qw banana-slides; then
  COMPOSE_ARGS+=(--profile banana-slides)
fi

for svc in $SERVICES; do
  echo "Building $svc ..."
  docker compose "${{COMPOSE_ARGS[@]}}" build "$svc"
done

docker compose "${{COMPOSE_ARGS[@]}}" up -d --force-recreate $SERVICES

echo "Waiting for user-web health..."
for i in $(seq 1 36); do
  health="$(docker inspect --format '{{{{.State.Health.Status}}}}' ai-supermarket-user-web 2>/dev/null || echo missing)"
  echo "  attempt $i: user-web=$health"
  if [[ "$health" == "healthy" ]]; then
    break
  fi
  sleep 10
done

curl -sf -o /dev/null -w "root:%{{http_code}}\\n" http://127.0.0.1/ || true
curl -sf -o /dev/null -w "api:%{{http_code}}\\n" http://127.0.0.1/api/health || true
curl -sf -o /dev/null -w "admin:%{{http_code}}\\n" -L http://127.0.0.1/admin || true
docker compose "${{COMPOSE_ARGS[@]}}" ps
echo "Deploy complete: $NEW_SHA"
"""
        _, stdout, stderr = ssh.exec_command(remote_script, timeout=1800)
        out = stdout.read().decode()
        err = stderr.read().decode()
        code = stdout.channel.recv_exit_status()
        if out:
            print(out, end="" if out.endswith("\n") else "\n")
        if err:
            print(err, file=sys.stderr, end="" if err.endswith("\n") else "\n")
        ssh.close()
        return code
    finally:
        Path(bundle_path).unlink(missing_ok=True)


if __name__ == "__main__":
    raise SystemExit(main())
