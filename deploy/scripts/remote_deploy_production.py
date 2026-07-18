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
    "MIHOMO_ENABLED=true",
    "NO_PROXY=localhost,127.0.0.1,mysql,redis,rabbitmq,backend,agent-service,admin-frontend,user-web,nginx,host.docker.internal,wlcloudai.com,8.134.93.203,.aliyuncs.com,.aliyun.com,.cn,.klingai.com,api.deepseek.com,.deepseek.com,ark.cn-beijing.volces.com,.volces.com,api.minimaxi.com,.minimaxi.com,api.minimax.chat,.minimax.chat",
    "CONTAINER_NO_PROXY=localhost,127.0.0.1,mysql,redis,rabbitmq,backend,agent-service,admin-frontend,user-web,nginx,host.docker.internal,wlcloudai.com,8.134.93.203,.aliyuncs.com,.aliyun.com,.cn,.klingai.com,api.deepseek.com,.deepseek.com,ark.cn-beijing.volces.com,.volces.com,api.minimaxi.com,.minimaxi.com,api.minimax.chat,.minimax.chat",
    "BACKUP_OSS_URI=oss://wlcloudai-db-backup-prod/mysql/full",
    "PROMETHEUS_PORT=9091",
    "GRAFANA_PORT=3001",
    "GRAFANA_ROOT_URL=https://wlcloudai.com/grafana/",
    "PROMETHEUS_RETENTION=15d",
    "GRAFANA_ADMIN_USER=admin",
    "CADVISOR_IMAGE=m.daocloud.io/ghcr.io/google/cadvisor:v0.60.5",
]
LEGACY_APPLICATION_PROXY_KEYS = {
    "HTTP_PROXY", "HTTPS_PROXY", "ALL_PROXY",
    "http_proxy", "https_proxy", "all_proxy",
    "CONTAINER_HTTP_PROXY", "CONTAINER_HTTPS_PROXY",
}
ENV_PATCH_SCRIPT = "\n".join(
    [
        "python3 - <<'PY'",
        "import secrets",
        "from pathlib import Path",
        'path = Path("/root/ai_tool_market/.env")',
        f"patch_lines = {repr(_ENV_PATCH_LINES)}",
        f"LEGACY_APPLICATION_PROXY_KEYS = {repr(LEGACY_APPLICATION_PROXY_KEYS)}",
        "patch = {}",
        "for line in patch_lines:",
        '    if "=" not in line:',
        "        continue",
        '    key, value = line.split("=", 1)',
        "    patch[key] = value",
        'lines = path.read_text(encoding="utf-8", errors="replace").splitlines() if path.exists() else []',
        "keys = set(patch) | LEGACY_APPLICATION_PROXY_KEYS",
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
        'data = {}',
        'for line in path.read_text(encoding="utf-8", errors="replace").splitlines():',
        '    if "=" not in line or line.strip().startswith("#"):',
        '        continue',
        '    key, value = line.split("=", 1)',
        '    data[key.strip()] = value',
        'if not data.get("MIHOMO_CONTROLLER_SECRET", "").strip():',
        '    lines = [line for line in path.read_text(encoding="utf-8", errors="replace").splitlines() if not line.startswith("MIHOMO_CONTROLLER_SECRET=")]',
        '    lines.append(f"MIHOMO_CONTROLLER_SECRET={secrets.token_urlsafe(32)}")',
        '    path.write_text("\\n".join(lines) + "\\n", encoding="utf-8")',
        '    print("bootstrapped MIHOMO_CONTROLLER_SECRET for production")',
        'if data.get("GRAFANA_ADMIN_PASSWORD", "") in ("", "admin123456"):',
        '    lines = [line for line in path.read_text(encoding="utf-8", errors="replace").splitlines() if not line.startswith("GRAFANA_ADMIN_PASSWORD=")]',
        '    lines.append("GRAFANA_ADMIN_PASSWORD=123456")',
        '    path.write_text("\\n".join(lines) + "\\n", encoding="utf-8")',
        '    print("initialized GRAFANA_ADMIN_PASSWORD")',
        'deploy_path = Path("/root/ai_tool_market/deploy/.env")',
        'deploy_path.parent.mkdir(parents=True, exist_ok=True)',
        'root_data = {}',
        'for line in path.read_text(encoding="utf-8", errors="replace").splitlines():',
        '    if "=" not in line or line.strip().startswith("#"):',
        '        continue',
        '    key, value = line.split("=", 1)',
        '    if key.strip() not in LEGACY_APPLICATION_PROXY_KEYS:',
        '        root_data[key.strip()] = value',
        'deploy_lines = deploy_path.read_text(encoding="utf-8", errors="replace").splitlines() if deploy_path.exists() else []',
        'mirror_keys = set(patch) | {"GRAFANA_ADMIN_PASSWORD"}',
        'deploy_lines = [line for line in deploy_lines if line.split("=", 1)[0].strip() not in LEGACY_APPLICATION_PROXY_KEYS]',
        'deploy_lines = [line for line in deploy_lines if line.split("=", 1)[0].strip() not in mirror_keys]',
        'for key in mirror_keys:',
        '    if key in root_data:',
        '        deploy_lines.append(f"{key}={root_data[key]}")',
        'deploy_path.write_text("\\n".join(deploy_lines) + "\\n", encoding="utf-8")',
        'print("mirrored monitoring env to", deploy_path)',
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
    return "backend worker agent-service admin-frontend user-web nginx prometheus grafana loki alloy node-exporter cadvisor blackbox-exporter"


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
NEW_SHA="$(git rev-parse deploy-target)"
DEPLOYED_AT="$(date -Iseconds 2>/dev/null || date '+%Y-%m-%dT%H:%M:%S%z')"
cat > "$REMOTE_DIR/deploy/logs/last-deploy.json" <<EOF
{{
  "deployedAt": "$DEPLOYED_AT",
  "oldSha": "$OLD_SHA",
  "newSha": "$NEW_SHA",
  "event": "manual",
  "branch": "$GIT_BRANCH"
}}
EOF

rollback_on_failure() {{
  status=$?
  trap - ERR
  if declare -F cleanup_preflight_user >/dev/null 2>&1; then
    cleanup_preflight_user || true
    trap - EXIT
  fi
  echo "::error::Release failed; starting rollback to $OLD_SHA." >&2
  if ! REMOTE_DIR="$REMOTE_DIR" DEPLOY_SERVICES="$SERVICES" \
      bash "$REMOTE_DIR/deploy/scripts/rollback_release.sh"; then
    echo "::error::Automatic rollback also failed; manual intervention is required." >&2
  fi
  exit "$status"
}}
trap rollback_on_failure ERR

git checkout -B "$GIT_BRANCH" deploy-target -f
git reset --hard deploy-target
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
COMPOSE_ARGS=(--env-file ../.env -f docker-compose.yml -f docker-compose.nginx.yml)
if grep -Eqi '^MIHOMO_ENABLED=true$' "$REMOTE_DIR/.env"; then
  COMPOSE_ARGS+=(-f docker-compose.proxy.yml)
  echo "Mihomo overlay enabled"
fi
if [ -f docker-compose.monitoring.yml ]; then
  COMPOSE_ARGS+=(-f docker-compose.monitoring.yml)
fi
if echo "$SERVICES" | grep -qw banana-slides; then
  COMPOSE_ARGS+=(--profile banana-slides)
fi

if docker inspect mihomo >/dev/null 2>&1; then
  echo "ERROR: Found unmanaged Mihomo container named mihomo; run the one-time managed-overlay migration before deployment." >&2
  exit 1
fi
if docker inspect ai-supermarket-mihomo >/dev/null 2>&1; then
  mihomo_config_files="$(docker inspect --format '{{{{ index .Config.Labels \"com.docker.compose.project.config_files\" }}}}' ai-supermarket-mihomo 2>/dev/null || true)"
  if [[ "$mihomo_config_files" != *docker-compose.proxy.yml* ]]; then
    echo "Removing legacy Mihomo container before managed overlay startup"
    docker rm -f ai-supermarket-mihomo
  fi
fi
docker compose "${{COMPOSE_ARGS[@]}}" up -d mihomo
SERVICES="$(bash "$REMOTE_DIR/deploy/scripts/merge_deploy_services.sh" "$SERVICES" backend worker agent-service)"

read_env_value() {{
  local name="$1"
  local file value
  for file in "$REMOTE_DIR/.env" "$REMOTE_DIR/deploy/.env"; do
    [ -f "$file" ] || continue
    value="$(awk -v key="$name" 'index($0, key "=") == 1 {{ value=substr($0, length(key) + 2) }} END {{ print value }}' "$file" | tr -d '\r')"
    if [ -n "$value" ]; then
      printf '%s' "$value"
      return
    fi
  done
}}

export APP_PRODUCTION_MODE="$(read_env_value APP_PRODUCTION_MODE)"
export APP_ENV="$(read_env_value APP_ENV)"
export PRODUCTION_PREFLIGHT_MYSQL_USER="ci_pf_manual_$(date -u +%Y%m%d%H%M%S)"
export PRODUCTION_PREFLIGHT_MYSQL_PASSWORD="$(openssl rand -hex 24)"

preflight_user_created=false
cleanup_preflight_user() {{
  if [ "$preflight_user_created" = true ]; then
    bash "$REMOTE_DIR/deploy/scripts/manage_preflight_mysql_user.sh" drop || true
    preflight_user_created=false
  fi
}}
trap cleanup_preflight_user EXIT

echo "Preparing persistent production credentials ..."
bash "$REMOTE_DIR/deploy/scripts/prepare_production_credentials.sh"
echo "Verifying production environment configuration ..."
bash "$REMOTE_DIR/deploy/scripts/verify_production_environment.sh"

echo "Starting MySQL for production data gates ..."
docker compose "${{COMPOSE_ARGS[@]}}" up -d mysql
export MYSQL_PASS="$(read_env_value MYSQL_ROOT_PASSWORD)"
export MYSQL_DB="$(read_env_value MYSQL_DATABASE)"
export BACKUP_ENCRYPTION_PASSWORD="$(read_env_value BACKUP_ENCRYPTION_PASSWORD)"
export BACKUP_OSS_URI="$(read_env_value BACKUP_OSS_URI)"
export OSS_ENDPOINT="$(read_env_value OSS_ENDPOINT)"
export OSS_ACCESS_KEY_ID="$(read_env_value OSS_ACCESS_KEY_ID)"
export OSS_ACCESS_KEY_SECRET="$(read_env_value OSS_ACCESS_KEY_SECRET)"
if [ -z "$OSS_ACCESS_KEY_ID" ]; then OSS_ACCESS_KEY_ID="$(read_env_value ALIYUN_ACCESS_KEY_ID)"; fi
if [ -z "$OSS_ACCESS_KEY_ID" ]; then OSS_ACCESS_KEY_ID="$(read_env_value ALIYUN_CAPTCHA_ACCESS_KEY_ID)"; fi
if [ -z "$OSS_ACCESS_KEY_SECRET" ]; then OSS_ACCESS_KEY_SECRET="$(read_env_value ALIYUN_ACCESS_KEY_SECRET)"; fi
if [ -z "$OSS_ACCESS_KEY_SECRET" ]; then OSS_ACCESS_KEY_SECRET="$(read_env_value ALIYUN_CAPTCHA_ACCESS_KEY_SECRET)"; fi
export OSS_ACCESS_KEY_ID OSS_ACCESS_KEY_SECRET
MYSQL_PASS="${{MYSQL_PASS:-root123456}}"
MYSQL_DB="${{MYSQL_DB:-ai_supermarket_v1}}"
bash "$REMOTE_DIR/deploy/scripts/manage_preflight_mysql_user.sh" create
preflight_user_created=true

echo "Running historical data read-only preflight ..."
bash "$REMOTE_DIR/deploy/scripts/production_readonly_preflight.sh" historical
echo "Creating encrypted pre-migration backup ..."
bash "$REMOTE_DIR/deploy/scripts/backup_mysql.sh"
bash "$REMOTE_DIR/deploy/scripts/apply_sql_migrations.sh"
echo "Running post-migration read-only preflight ..."
bash "$REMOTE_DIR/deploy/scripts/production_readonly_preflight.sh" post-migration
cleanup_preflight_user

for svc in $SERVICES; do
  case "$svc" in
    backend|worker|agent-service|admin-frontend|user-web|banana-slides) ;;
    *)
      echo "Skipping build for image-only service: $svc"
      continue
      ;;
  esac
  echo "Building $svc ..."
  docker compose "${{COMPOSE_ARGS[@]}}" build "$svc"
done

APP_SERVICES=""
MONITORING_SERVICES=""
MONITORING_STACK="prometheus grafana loki alloy node-exporter cadvisor blackbox-exporter"
for svc in $SERVICES; do
  case "$svc" in
    prometheus|grafana|loki|alloy|node-exporter|cadvisor|blackbox-exporter)
      MONITORING_SERVICES="$MONITORING_SERVICES $svc"
      ;;
    mihomo|mihomo-init)
      ;;
    *)
      APP_SERVICES="$APP_SERVICES $svc"
      ;;
  esac
done

if [ -n "$APP_SERVICES" ]; then
  docker compose "${{COMPOSE_ARGS[@]}}" up -d --force-recreate --no-deps $APP_SERVICES
fi

if [ -n "$MONITORING_SERVICES" ]; then
  docker compose "${{COMPOSE_ARGS[@]}}" up -d --force-recreate $MONITORING_SERVICES
fi

echo "Ensuring complete monitoring stack: $MONITORING_STACK"
docker compose "${{COMPOSE_ARGS[@]}}" up -d $MONITORING_STACK

echo "Waiting for user-web health..."
for i in $(seq 1 36); do
  health="$(docker inspect --format '{{{{.State.Health.Status}}}}' ai-supermarket-user-web 2>/dev/null || echo missing)"
  echo "  attempt $i: user-web=$health"
  if [[ "$health" == "healthy" ]]; then
    break
  fi
  sleep 10
done

echo "Verifying release health..."
bash "$REMOTE_DIR/deploy/scripts/verify_release_health.sh"
docker compose "${{COMPOSE_ARGS[@]}}" ps
trap - ERR
trap - EXIT
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

