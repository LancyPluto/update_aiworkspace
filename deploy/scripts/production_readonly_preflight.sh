#!/usr/bin/env bash
set -euo pipefail
umask 077

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PHASE="${1:-post-migration}"
MYSQL_CONTAINER="${MYSQL_CONTAINER:-ai-supermarket-mysql}"
MYSQL_DB="${MYSQL_DB:-ai_supermarket_v1}"
PRODUCTION_PREFLIGHT_MYSQL_USER="${PRODUCTION_PREFLIGHT_MYSQL_USER:-}"
PRODUCTION_PREFLIGHT_MYSQL_PASSWORD="${PRODUCTION_PREFLIGHT_MYSQL_PASSWORD:-}"
timestamp="$(date -u +%Y%m%dT%H%M%SZ)"
PREFLIGHT_REPORT_FILE="${PREFLIGHT_REPORT_FILE:-$SCRIPT_DIR/../logs/production-readonly-preflight-${PHASE}-${timestamp}.report}"
blocking_issues=0
final_status="FAIL"

if [ "$PHASE" != "historical" ] && [ "$PHASE" != "post-migration" ]; then
  echo "ERROR: phase must be historical or post-migration" >&2
  exit 2
fi
if [ -z "$PRODUCTION_PREFLIGHT_MYSQL_USER" ] || [ -z "$PRODUCTION_PREFLIGHT_MYSQL_PASSWORD" ]; then
  echo "ERROR: PRODUCTION_PREFLIGHT_MYSQL_USER and PRODUCTION_PREFLIGHT_MYSQL_PASSWORD are required" >&2
  exit 2
fi
if [ "$PRODUCTION_PREFLIGHT_MYSQL_USER" = "root" ]; then
  echo "ERROR: read-only preflight account cannot be root" >&2
  exit 2
fi

mkdir -p "$(dirname "$PREFLIGHT_REPORT_FILE")"
printf 'phase=%s\nstarted_at=%s\ndatabase=%s\naccount=%s\n' \
  "$PHASE" "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$MYSQL_DB" "$PRODUCTION_PREFLIGHT_MYSQL_USER" \
  > "$PREFLIGHT_REPORT_FILE"
chmod 600 "$PREFLIGHT_REPORT_FILE"

finish_report() {
  local exit_status="$?"
  if [ "$final_status" = "PASS" ] && [ "$exit_status" -eq 0 ]; then
    printf 'status=PASS\n' >> "$PREFLIGHT_REPORT_FILE"
  else
    printf 'status=FAIL\n' >> "$PREFLIGHT_REPORT_FILE"
  fi
  printf 'finished_at=%s\nblocking_issues=%s\n' \
    "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$blocking_issues" >> "$PREFLIGHT_REPORT_FILE"
  chmod 600 "$PREFLIGHT_REPORT_FILE"
  echo "Read-only preflight report: $PREFLIGHT_REPORT_FILE"
}
trap finish_report EXIT

mysql_raw() {
  docker exec -i -e MYSQL_PWD="$PRODUCTION_PREFLIGHT_MYSQL_PASSWORD" "$MYSQL_CONTAINER" \
    mysql --default-character-set=utf8mb4 --batch --skip-column-names \
      -u"$PRODUCTION_PREFLIGHT_MYSQL_USER" "$MYSQL_DB" "$@"
}

mysql_readonly() {
  local sql="$1"
  mysql_raw -e "SET SESSION TRANSACTION READ ONLY; START TRANSACTION READ ONLY; ${sql}; COMMIT;"
}

grants="$(mysql_raw -e "SHOW GRANTS FOR CURRENT_USER;" | tr -d '\r')"
if ! printf '%s\n' "$grants" | grep -Eq '^GRANT SELECT ON ' ; then
  echo "ERROR: preflight account does not have SELECT permission" >&2
  blocking_issues=$((blocking_issues + 1))
fi
unexpected_grants="$(printf '%s\n' "$grants" | grep -Ev '^GRANT (USAGE ON \*\.\*|SELECT ON .+) TO ' || true)"
if [ -n "$unexpected_grants" ] || printf '%s\n' "$grants" | grep -Eq ' WITH GRANT OPTION'; then
  echo "ERROR: preflight account grants must contain only USAGE and SELECT" >&2
  blocking_issues=$((blocking_issues + 1))
fi

record_check() {
  local name="$1"
  local value="$2"
  local result="$3"
  printf 'check.%s.value=%s\ncheck.%s.result=%s\n' "$name" "$value" "$name" "$result" \
    >> "$PREFLIGHT_REPORT_FILE"
}

check_zero() {
  local name="$1"
  local sql="$2"
  local value
  value="$(mysql_readonly "$sql" | tail -1 | tr -d '\r')"
  if [[ ! "$value" =~ ^[0-9]+$ ]]; then
    record_check "$name" "invalid" "FAIL"
    echo "ERROR: $name did not return a non-negative count" >&2
    blocking_issues=$((blocking_issues + 1))
  elif [ "$value" -ne 0 ]; then
    record_check "$name" "$value" "FAIL"
    echo "ERROR: $name found $value blocking row(s)" >&2
    blocking_issues=$((blocking_issues + 1))
  else
    record_check "$name" "$value" "PASS"
  fi
}

table_exists() {
  local table="$1"
  [ "$(mysql_readonly "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = '${table}'" | tail -1 | tr -d '\r')" = "1" ]
}

column_exists() {
  local table="$1"
  local column="$2"
  [ "$(mysql_readonly "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = '${table}' AND column_name = '${column}'" | tail -1 | tr -d '\r')" = "1" ]
}

require_table() {
  local table="$1"
  if table_exists "$table"; then
    record_check "table_${table}" "1" "PASS"
  else
    record_check "table_${table}" "0" "FAIL"
    echo "ERROR: required table is missing: $table" >&2
    blocking_issues=$((blocking_issues + 1))
  fi
}

require_column() {
  local table="$1"
  local column="$2"
  if column_exists "$table" "$column"; then
    record_check "column_${table}_${column}" "1" "PASS"
  else
    record_check "column_${table}_${column}" "0" "FAIL"
    echo "ERROR: required column is missing: ${table}.${column}" >&2
    blocking_issues=$((blocking_issues + 1))
  fi
}

check_historical_duplicates() {
  if table_exists ai_tasks && column_exists ai_tasks idempotency_key; then
    check_zero "duplicate_ai_task_idempotency" \
      "SELECT COUNT(*) FROM (SELECT 1 FROM ai_tasks WHERE idempotency_key IS NOT NULL AND idempotency_key <> '' GROUP BY user_id, idempotency_key HAVING COUNT(*) > 1) duplicate_keys"
  fi
  if table_exists workflow_runs && column_exists workflow_runs root_task_id; then
    check_zero "duplicate_workflow_root_task" \
      "SELECT COUNT(*) FROM (SELECT 1 FROM workflow_runs WHERE root_task_id IS NOT NULL GROUP BY root_task_id HAVING COUNT(*) > 1) duplicate_keys"
  fi
  if table_exists workflow_runs && column_exists workflow_runs client_request_id; then
    check_zero "duplicate_workflow_client_request" \
      "SELECT COUNT(*) FROM (SELECT 1 FROM workflow_runs WHERE client_request_id IS NOT NULL AND client_request_id <> '' GROUP BY user_id, client_request_id HAVING COUNT(*) > 1) duplicate_keys"
  fi
  if table_exists billing_usage_logs && column_exists billing_usage_logs idempotency_key; then
    check_zero "duplicate_billing_usage_idempotency" \
      "SELECT COUNT(*) FROM (SELECT 1 FROM billing_usage_logs WHERE idempotency_key IS NOT NULL AND idempotency_key <> '' GROUP BY idempotency_key HAVING COUNT(*) > 1) duplicate_keys"
  fi
}

for table in ai_tasks billing_usage_logs; do
  require_table "$table"
done
check_historical_duplicates

if [ "$PHASE" = "post-migration" ]; then
  for table in _sql_migration_log workflow_runs workflow_run_steps workflow_step_attempts workflow_step_charges billing_usage_logs; do
    require_table "$table"
  done

  if table_exists _sql_migration_log; then
    migration_count="$(mysql_readonly "SELECT COUNT(*) FROM _sql_migration_log WHERE name IN ('088_workflow_tools_p0.sql','089_workflow_credit_log_source.sql','090_workflow_billing_idempotency.sql','091_workflow_attempt_cancellation_generation.sql','092_workflow_charge_binding.sql','093_workflow_rollout_safety.sql','094_task_runtime_columns.sql','095_task_provider_checkpoint.sql','096_workflow_provider_accounting.sql','097_workflow_provider_cost_gate_index.sql','098_workflow_provider_cost_reservation.sql') AND checksum_sha256 IS NOT NULL AND checksum_sha256 <> ''" | tail -1 | tr -d '\r')"
    if [ "$migration_count" = "11" ]; then
      record_check "workflow_migrations_registered" "$migration_count" "PASS"
    else
      record_check "workflow_migrations_registered" "${migration_count:-invalid}" "FAIL"
      echo "ERROR: workflow migrations 088-098 are not fully registered with checksums" >&2
      blocking_issues=$((blocking_issues + 1))
    fi
  fi

  if table_exists workflow_step_charges; then
    check_zero "orphan_workflow_charge_run" \
      "SELECT COUNT(*) FROM workflow_step_charges charge_row LEFT JOIN workflow_runs run_row ON run_row.id = charge_row.run_id WHERE run_row.id IS NULL"
    check_zero "orphan_workflow_charge_step" \
      "SELECT COUNT(*) FROM workflow_step_charges charge_row LEFT JOIN workflow_run_steps step_row ON step_row.id = charge_row.step_id WHERE step_row.id IS NULL"
    check_zero "provider_charged_workflow_usage_binding_missing" \
      "SELECT COUNT(*) FROM workflow_step_charges charge_row LEFT JOIN billing_usage_logs usage_row ON usage_row.id = charge_row.billing_usage_id WHERE (charge_row.status = 'CAPTURED' OR (charge_row.status = 'RELEASED' AND charge_row.provider_cost IS NOT NULL)) AND (charge_row.billing_usage_id IS NULL OR usage_row.id IS NULL)"
  fi

  check_zero "model_identifier_collation_mismatch" \
    "SELECT 19 - COUNT(*)
     FROM information_schema.columns
     WHERE table_schema = DATABASE()
       AND CONCAT(table_name, '.', column_name) IN (
         'agent_model_configs.config_code',
         'agent_model_configs.provider',
         'agent_model_configs.model_name',
         'model_provider_metadata.provider_code',
         'model_vendor_accounts.vendor_code',
         'model_vendors.vendor_code',
         'model_account_routing_pools.vendor_code',
         'agent_context_snapshots.model_provider_code',
         'agent_context_snapshots.model_name',
         'agent_model_request_snapshots.model_provider_code',
         'agent_model_request_snapshots.model_name',
         'agent_runs.model_provider_code',
         'agent_runs.model_name',
         'billing_usage_logs.provider',
         'billing_usage_logs.model_name',
         'workflow_step_attempts.provider_code',
         'provider_callback_registrations.provider_code',
         'provider_callback_inbox.provider_code',
         'user_generation_subjects.provider_code'
       )
       AND character_set_name = 'utf8mb4'
       AND collation_name = 'utf8mb4_unicode_ci'"
fi

if [ "$blocking_issues" -ne 0 ]; then
  echo "Production read-only preflight found $blocking_issues blocking issue(s)." >&2
  exit 1
fi

final_status="PASS"
echo "Production read-only preflight passed for phase: $PHASE"
