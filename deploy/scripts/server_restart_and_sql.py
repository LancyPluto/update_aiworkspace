#!/usr/bin/env python3
"""Restart docker stack on server and apply pending SQL migrations."""
from __future__ import annotations

import os
import sys

import paramiko

REMOTE_DIR = "/root/ai_tool_market"
DEPLOY_DIR = f"{REMOTE_DIR}/deploy"
MYSQL_CONTAINER = "ai-supermarket-mysql"
MYSQL_USER = "root"
MYSQL_PASS = "root123456"
MYSQL_DB = "ai_supermarket_v1"


def main() -> int:
    password = os.environ.get("DEPLOY_PASSWORD")
    host = os.environ.get("DEPLOY_HOST", "8.134.93.203")
    if not password:
        print("Set DEPLOY_PASSWORD", file=sys.stderr)
        return 1

    remote_script = r'''#!/bin/bash
set -uo pipefail
cd ''' + DEPLOY_DIR + r'''

mysql_q() {
  docker exec ''' + MYSQL_CONTAINER + r''' mysql -u''' + MYSQL_USER + r''' -p''' + MYSQL_PASS + r''' "$@" 2>&1 | grep -v "Using a password" || true
}

echo "==> Start MySQL for migrations (if not running)"
docker compose -f docker-compose.yml -f docker-compose.nginx.yml up -d mysql
for i in $(seq 1 60); do
  if docker compose -f docker-compose.yml -f docker-compose.nginx.yml ps mysql 2>/dev/null | grep -q healthy; then
    break
  fi
  if docker exec ''' + MYSQL_CONTAINER + r''' mysqladmin ping -h127.0.0.1 -u''' + MYSQL_USER + r''' -p''' + MYSQL_PASS + r''' --silent 2>/dev/null; then
    break
  fi
  sleep 2
done

echo "==> Ensure migration log table"
mysql_q ''' + MYSQL_DB + r''' -e "
CREATE TABLE IF NOT EXISTS _sql_migration_log (
  name VARCHAR(255) NOT NULL PRIMARY KEY,
  applied_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
"

echo "==> Apply pending SQL files (sorted)"
applied=0
skipped=0
failed=0
shopt -s nullglob
for f in $(ls -1 ''' + REMOTE_DIR + r'''/sql/*.sql | sort); do
  base=$(basename "$f")
  exists=$(mysql_q ''' + MYSQL_DB + r''' -N -e "SELECT COUNT(*) FROM _sql_migration_log WHERE name='"'"'"$base"'"'"';" | tail -1)
  if [ "${exists:-0}" = "1" ]; then
    skipped=$((skipped+1))
    continue
  fi
  if [ "$base" = "001_init_v1.sql" ]; then
    has_users=$(mysql_q ''' + MYSQL_DB + r''' -N -e "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='"'"'" + MYSQL_DB + r"'"'"' AND table_name='users';" | tail -1)
    if [ "${has_users:-0}" = "1" ]; then
      echo "  SKIP $base (schema already initialized)"
      mysql_q ''' + MYSQL_DB + r''' -e "INSERT IGNORE INTO _sql_migration_log (name) VALUES ('"'"'"$base"'"'"');"
      skipped=$((skipped+1))
      continue
    fi
  fi
  echo "  RUN $base"
  set +e
  out=$(docker exec -i ''' + MYSQL_CONTAINER + r''' mysql -u''' + MYSQL_USER + r''' -p''' + MYSQL_PASS + r''' ''' + MYSQL_DB + r''' < "$f" 2>&1)
  code=$?
  set -e
  if [ $code -eq 0 ]; then
    mysql_q ''' + MYSQL_DB + r''' -e "INSERT INTO _sql_migration_log (name) VALUES ('"'"'"$base"'"'"');"
    applied=$((applied+1))
  elif echo "$out" | grep -qiE 'Duplicate (column|key|entry)|already exists|1060|1061|1062'; then
    echo "    (idempotent skip) $out" | head -3
    mysql_q ''' + MYSQL_DB + r''' -e "INSERT IGNORE INTO _sql_migration_log (name) VALUES ('"'"'"$base"'"'"');"
    applied=$((applied+1))
  else
    echo "    FAILED: $out" | head -8
    failed=$((failed+1))
  fi
done
echo "SQL summary: applied=$applied skipped=$skipped failed=$failed"

if [ "$failed" -gt 0 ]; then
  echo "WARNING: some SQL files failed; continuing with docker restart"
fi

echo "==> Docker compose rebuild and start all services"
docker compose -f docker-compose.yml -f docker-compose.nginx.yml up -d --build

echo "==> Service status"
sleep 8
docker compose -f docker-compose.yml -f docker-compose.nginx.yml ps

echo "==> HTTP check"
curl -s -o /dev/null -w "nginx:%{http_code}\n" http://127.0.0.1/ || true
curl -s -o /dev/null -w "backend:%{http_code}\n" http://127.0.0.1:8080/actuator/health || true
'''

    ssh = paramiko.SSHClient()
    ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    print(f"Connecting to {host}...")
    ssh.connect(host, username="root", password=password, timeout=30)
    _, stdout, stderr = ssh.exec_command(f"bash -s <<'REMOTE_SCRIPT'\n{remote_script}\nREMOTE_SCRIPT", timeout=3600)
    out = stdout.read().decode("utf-8", errors="replace")
    err = stderr.read().decode("utf-8", errors="replace")
    code = stdout.channel.recv_exit_status()
    if out:
        print(out)
    if err:
        print(err, file=sys.stderr)
    ssh.close()
    return code


if __name__ == "__main__":
    raise SystemExit(main())
