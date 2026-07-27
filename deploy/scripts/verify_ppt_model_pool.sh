#!/usr/bin/env bash
set -euo pipefail

: "${MYSQL_PASS:?MYSQL_PASS is required}"
MYSQL_DB="${MYSQL_DB:-ai_supermarket_v1}"
COMPOSE_ARGS=(--env-file ../.env -f docker-compose.yml -f docker-compose.ppt.yml)

query_count() {
  local capability="$1"
  docker compose "${COMPOSE_ARGS[@]}" exec -T mysql \
    mysql -uroot "-p${MYSQL_PASS}" -N -s "$MYSQL_DB" -e "
      SELECT COUNT(*)
      FROM agent_model_configs config
      WHERE COALESCE(config.is_deleted, 0) = 0
        AND config.enabled = 1
        AND UPPER(COALESCE(config.capabilities, '')) LIKE '%${capability}%'
        AND NULLIF(TRIM(config.model_name), '') IS NOT NULL
        AND (
          NULLIF(TRIM(config.api_key), '') IS NOT NULL
          OR NULLIF(TRIM(config.extra_auth_json), '') IS NOT NULL
          OR EXISTS (
            SELECT 1
            FROM model_vendor_accounts account
            WHERE account.id = config.vendor_account_id
              AND COALESCE(account.is_deleted, 0) = 0
              AND account.enabled = 1
              AND (
                NULLIF(TRIM(account.api_key), '') IS NOT NULL
                OR NULLIF(TRIM(account.extra_auth_json), '') IS NOT NULL
              )
          )
        );
    "
}

text_count="$(query_count TEXT_GENERATION)"
image_count="$(query_count IMAGE_GENERATION)"
if [ "${text_count:-0}" -lt 1 ] || [ "${image_count:-0}" -lt 1 ]; then
  echo "::error::PPT requires at least one executable TEXT_GENERATION and IMAGE_GENERATION model" >&2
  exit 1
fi
echo "PPT platform model pool verified: text=${text_count}, image=${image_count}"
