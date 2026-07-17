#!/usr/bin/env bash
set -euo pipefail

ACTION="${1:-}"
MYSQL_CONTAINER="${MYSQL_CONTAINER:-ai-supermarket-mysql}"
MYSQL_DB="${MYSQL_DB:-ai_supermarket_v1}"
MYSQL_PASS="${MYSQL_PASS:-root123456}"
PRODUCTION_PREFLIGHT_MYSQL_USER="${PRODUCTION_PREFLIGHT_MYSQL_USER:-}"
PRODUCTION_PREFLIGHT_MYSQL_PASSWORD="${PRODUCTION_PREFLIGHT_MYSQL_PASSWORD:-}"

if [ "$ACTION" != "create" ] && [ "$ACTION" != "drop" ]; then
  echo "ERROR: action must be create or drop" >&2
  exit 2
fi
if [[ ! "$PRODUCTION_PREFLIGHT_MYSQL_USER" =~ ^ci_pf_[A-Za-z0-9_]{1,26}$ ]]; then
  echo "ERROR: preflight MySQL username must be a Runner-generated ci_pf_ account" >&2
  exit 2
fi
if [[ ! "$PRODUCTION_PREFLIGHT_MYSQL_PASSWORD" =~ ^[a-f0-9]{48}$ ]]; then
  echo "ERROR: preflight MySQL password must be a 48-character Runner-generated hex value" >&2
  exit 2
fi
if [[ ! "$MYSQL_DB" =~ ^[A-Za-z0-9_]+$ ]]; then
  echo "ERROR: MYSQL_DB contains unsupported characters" >&2
  exit 2
fi

mysql_root() {
  docker exec -i -e MYSQL_PWD="$MYSQL_PASS" "$MYSQL_CONTAINER" \
    mysql --default-character-set=utf8mb4 --batch --skip-column-names -uroot "$@"
}

if [ "$ACTION" = "create" ]; then
  mysql_root <<SQL
DROP USER IF EXISTS '${PRODUCTION_PREFLIGHT_MYSQL_USER}'@'localhost';
CREATE USER '${PRODUCTION_PREFLIGHT_MYSQL_USER}'@'localhost' IDENTIFIED BY '${PRODUCTION_PREFLIGHT_MYSQL_PASSWORD}';
GRANT SELECT ON \`${MYSQL_DB}\`.* TO '${PRODUCTION_PREFLIGHT_MYSQL_USER}'@'localhost';
SQL
  echo "Ephemeral preflight MySQL account created with SELECT-only access"
else
  mysql_root -e "DROP USER IF EXISTS '${PRODUCTION_PREFLIGHT_MYSQL_USER}'@'localhost';"
  echo "Ephemeral preflight MySQL account removed"
fi
