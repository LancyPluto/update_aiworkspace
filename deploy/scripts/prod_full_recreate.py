#!/usr/bin/env python3
"""Force-recreate all app containers on production."""
from __future__ import annotations

import paramiko

HOST = "8.134.93.203"
USER = "root"
PASSWORD = "KeChuangDianAi17728033019"
REMOTE_CMD = r"""
set -euo pipefail
cd /root/ai_tool_market/deploy
COMPOSE="docker compose -f docker-compose.yml -f docker-compose.nginx.yml"
SERVICES="backend worker agent-service admin-frontend user-web nginx"
echo "Full recreate: $SERVICES"
for svc in $SERVICES; do $COMPOSE build "$svc" || true; done
$COMPOSE up -d --force-recreate $SERVICES
$COMPOSE restart nginx
SHA=$(git -C /root/ai_tool_market rev-parse --short HEAD)
docker exec ai-supermarket-user-web sh -c "printf '%s\n' \"{\\\"gitSha\\\":\\\"${SHA}\\\",\\\"builtAt\\\":\\\"$(date -Iseconds)\\\"}\" > /dist-out/build-info.json"
sleep 20
docker ps --format '{{.Names}} {{.Status}}' | grep ai-supermarket | grep -E 'backend|agent|user-web|nginx|admin' || true
curl -sf http://127.0.0.1/build-info.json; echo
curl -sf http://127.0.0.1/api/health; echo
"""


def main() -> None:
    client = paramiko.SSHClient()
    client.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    client.connect(HOST, username=USER, password=PASSWORD, timeout=20)
    stdin, stdout, stderr = client.exec_command(REMOTE_CMD, timeout=600)
    out = stdout.read().decode("utf-8", errors="replace")
    err = stderr.read().decode("utf-8", errors="replace")
    print(out)
    if err.strip():
        print("STDERR:", err)
    client.close()


if __name__ == "__main__":
    main()
