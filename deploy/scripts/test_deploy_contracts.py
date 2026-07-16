import pathlib
import unittest


ROOT = pathlib.Path(__file__).resolve().parents[2]


class DeployContractTests(unittest.TestCase):
    def read(self, relative_path: str) -> str:
        return (ROOT / relative_path).read_text(encoding="utf-8")

    def test_migrations_are_immutable_and_fail_closed(self) -> None:
        script = self.read("deploy/scripts/apply_sql_migrations.sh")
        self.assertIn("checksum_sha256", script)
        self.assertIn("applied migration changed", script)
        self.assertIn("workflow P0 migration preflight found historical idempotency duplicates", script)
        self.assertIn("workflow P0 migration appears partially applied but is not recorded", script)
        self.assertIn("GROUP BY user_id, idempotency_key", script)
        self.assertIn("GROUP BY root_task_id", script)
        self.assertIn("074_vendor_account_console_cookie.sql", script)
        self.assertIn("compatible columns already supplied by historical 044", script)
        self.assertIn("character_maximum_length=20", script)
        self.assertIn("column_default='UNKNOWN'", script)
        self.assertIn("084_configurable_image_token_estimates.sql", script)
        self.assertIn("compatible columns already supplied by historical 061", script)
        self.assertIn("column_default IS NULL", script)
        self.assertNotIn("Duplicate column name.*success", script)
        self.assertNotIn("Duplicate key name.*success", script)

    def test_workflow_schema_deployment_does_not_switch_existing_tool(self) -> None:
        migration = self.read("sql/093_workflow_rollout_safety.sql")
        self.assertIn("SET execution_mode = 'DIRECT'", migration)
        self.assertIn("billing_mode = 'FIXED'", migration)
        self.assertIn("agent_surface_enabled = 0", migration)
        self.assertIn("SET workflow.execution_enabled = 0", migration)

    def test_fresh_database_has_model_route_prerequisite_before_066(self) -> None:
        prerequisite = "065_zz_agent_model_extra_auth_compat.sql"
        dependent = "066_standardize_model_execution_routes.sql"
        self.assertLess(prerequisite, dependent)
        migration = self.read("sql/" + prerequisite)
        self.assertIn("column_name = 'extra_auth_json'", migration)
        self.assertIn("ADD COLUMN extra_auth_json", migration)

    def test_task_runtime_columns_are_migrated_before_application_start(self) -> None:
        migration = self.read("sql/094_task_runtime_columns.sql")
        for column in (
            "user_deleted",
            "user_deleted_at",
            "model_snapshot_json",
            "claimed_by",
            "claim_token",
            "lease_until",
            "claimed_at",
            "lease_renewed_at",
            "execution_attempt",
        ):
            self.assertIn("column_name = '" + column + "'", migration)
            self.assertIn("ADD COLUMN " + column, migration)
        self.assertIn("index_name = 'idx_tasks_lease'", migration)
        self.assertIn("index_name = 'idx_tasks_claim_token'", migration)

    def test_provider_checkpoint_columns_are_migrated_for_worker_restart_recovery(self) -> None:
        migration = self.read("sql/095_task_provider_checkpoint.sql")
        for column in ("provider_checkpoint_json", "provider_checkpoint_version"):
            self.assertIn("column_name = '" + column + "'", migration)
            self.assertIn("ADD COLUMN " + column, migration)

    def test_backup_is_encrypted_and_validated(self) -> None:
        script = self.read("deploy/scripts/backup_mysql.sh")
        self.assertIn("umask 077", script)
        self.assertIn("gzip -t", script)
        self.assertIn("-aes-256-cbc", script)
        self.assertIn("manifest_version=1", script)
        self.assertIn("encryption=aes-256-cbc", script)
        self.assertIn("kdf=pbkdf2-sha256", script)
        self.assertIn("schema_checkpoint=", script)
        self.assertIn("sha256sum", script)
        self.assertIn("BACKUP_ENCRYPTION_PASSWORD is required", script)
        self.assertIn("BACKUP_OSS_URI is required in production", script)
        self.assertIn('ossutil stat "$remote_encrypted"', script)
        self.assertIn('ossutil stat "$remote_manifest"', script)

    def test_restore_cannot_target_production_database(self) -> None:
        script = self.read("deploy/scripts/restore_mysql_to_staging.sh")
        self.assertIn("umask 077", script)
        self.assertIn("(_staging|_restore|_verify)", script)
        self.assertIn("backup checksum verification failed", script)
        self.assertIn("backup size verification failed", script)
        self.assertIn("DROP DATABASE IF EXISTS", script)
        self.assertIn("RESTORE_DRILL_REPORT", script)
        self.assertIn("status=SUCCESS", script)
        self.assertIn("backup_sha256=", script)
        self.assertIn("schema_checkpoint=", script)
        self.assertIn("elapsed_seconds=", script)
        self.assertIn('chmod 600 "$RESTORE_DRILL_REPORT"', script)
        self.assertIn("checkpoint_number", script)
        self.assertIn("workflow_tables=()", script)

    def test_production_environment_preflight_fails_closed(self) -> None:
        script = self.read("deploy/scripts/verify_production_environment.sh")
        for required_setting in (
            "RABBITMQ_USERNAME",
            "RABBITMQ_PASSWORD",
            "BACKUP_ENCRYPTION_PASSWORD",
            "BACKUP_OSS_URI",
            "PRODUCTION_PREFLIGHT_MYSQL_USER",
            "PRODUCTION_PREFLIGHT_MYSQL_PASSWORD",
            "WORKFLOW_RUNTIME_EXECUTION_ENABLED",
            "WORKFLOW_RUNTIME_MAX_PROVIDER_DAILY_COST_CNY",
            "WORKFLOW_RUNTIME_COST_ALERT_WEBHOOK_URL",
        ):
            self.assertIn(required_setting, script)
        self.assertIn("guest RabbitMQ credentials are forbidden", script)
        self.assertIn("read-only preflight account cannot be root", script)
        self.assertIn("require_positive_decimal", script)
        self.assertIn("cost alert webhook must use https://", script)
        self.assertIn("backup bucket must be separate from application asset buckets", script)
        self.assertIn("production environment preflight failed", script)

    def test_production_database_preflight_is_read_only_and_auditable(self) -> None:
        script = self.read("deploy/scripts/production_readonly_preflight.sh")
        self.assertIn("umask 077", script)
        self.assertIn("PRODUCTION_PREFLIGHT_MYSQL_USER", script)
        self.assertIn("read-only preflight account cannot be root", script)
        self.assertIn("SHOW GRANTS FOR CURRENT_USER", script)
        self.assertIn("unexpected_grants", script)
        self.assertIn("GRANT (USAGE ON", script)
        self.assertIn("START TRANSACTION READ ONLY", script)
        self.assertIn("information_schema", script)
        self.assertIn("workflow_step_charges", script)
        self.assertIn("billing_usage_logs", script)
        self.assertIn("provider_cost IS NULL", script)
        self.assertIn("status = 'CAPTURED'", script)
        self.assertIn("active_workflow_provider_reservation_missing", script)
        self.assertIn("provider_cost_reserved_cny <= 0", script)
        self.assertNotIn("status = 'CHARGED'", script)
        self.assertIn("095_task_provider_checkpoint.sql", script)
        self.assertIn("096_workflow_provider_accounting.sql", script)
        self.assertIn("097_workflow_provider_cost_gate_index.sql", script)
        self.assertIn("098_workflow_provider_cost_reservation.sql", script)
        self.assertIn('migration_count" = "11"', script)
        self.assertIn("PREFLIGHT_REPORT_FILE", script)
        self.assertIn("status=PASS", script)
        self.assertNotIn("DROP DATABASE", script)
        self.assertNotIn("DELETE FROM", script)
        self.assertNotIn("UPDATE workflow", script)

    def test_production_database_preflight_blocks_unknown_actual_provider_cost(self) -> None:
        script = self.read("deploy/scripts/production_readonly_preflight.sh")
        self.assertIn("successful_workflow_actual_provider_cost_unknown", script)
        self.assertIn("outcome IN ('SUCCESS', 'CANCELLED_LATE_SUCCESS')", script)
        self.assertIn("provider_charged = 0", script)
        self.assertIn("UPPER(TRIM(provider_cost_currency)) = 'UNKNOWN'", script)

    def test_workflow_cost_guard_settings_are_deployable(self) -> None:
        root_environment = self.read(".env.example")
        deploy_environment = self.read("deploy/.env.example")
        compose = self.read("deploy/docker-compose.yml")
        application = self.read("backend/src/main/resources/application.yml")
        for setting in (
            "WORKFLOW_RUNTIME_MAX_PROVIDER_DAILY_COST_CNY",
            "WORKFLOW_RUNTIME_COST_ALERT_WEBHOOK_URL",
        ):
            self.assertIn(setting + "=", root_environment)
            self.assertIn("${" + setting + ":", application)
            self.assertNotIn(setting + "=", deploy_environment)
            self.assertNotIn(setting + ": ${" + setting, compose)

    def test_provider_accounting_columns_are_migrated_before_backend_start(self) -> None:
        migration = self.read("sql/096_workflow_provider_accounting.sql")
        for column in (
            "provider_cost_currency",
            "outcome",
            "error_code",
            "failure_stage",
            "provider_error_code",
            "provider_request_id",
            "provider_charged",
        ):
            self.assertIn("column_name = '" + column + "'", migration)
            self.assertIn("ADD COLUMN " + column, migration)

    def test_provider_cost_reservation_schema_is_migrated_before_backend_start(self) -> None:
        migration = self.read("sql/098_workflow_provider_cost_reservation.sql")
        self.assertIn("workflow_provider_cost_budget_days", migration)
        self.assertIn("column_name = 'provider_cost_reserved_cny'", migration)
        self.assertIn("ADD COLUMN provider_cost_reserved_cny", migration)
        self.assertIn("idx_workflow_run_provider_cost_reservation", migration)

    def test_deploy_runs_production_preflight_around_migrations(self) -> None:
        deploy = self.read("deploy/scripts/ci_remote_deploy_light.sh")
        environment_gate = deploy.index("verify_production_environment.sh")
        historical_gate = deploy.index("production_readonly_preflight.sh")
        backup = deploy.index("backup_mysql.sh")
        migrations = deploy.index("apply_sql_migrations.sh")
        post_migration_gate = deploy.index(
            "production_readonly_preflight.sh", historical_gate + 1
        )
        self.assertLess(environment_gate, historical_gate)
        self.assertLess(historical_gate, backup)
        self.assertLess(backup, migrations)
        self.assertLess(migrations, post_migration_gate)

    def test_windows_production_entrypoint_cannot_bypass_data_gates(self) -> None:
        deploy = self.read("deploy/scripts/remote_deploy_production.py")
        environment_gate = deploy.index("verify_production_environment.sh")
        historical_gate = deploy.index("production_readonly_preflight.sh")
        backup = deploy.index("backup_mysql.sh")
        migrations = deploy.index("apply_sql_migrations.sh")
        post_migration_gate = deploy.index(
            "production_readonly_preflight.sh", historical_gate + 1
        )
        build = deploy.index('echo "Building $svc ..."')
        self.assertLess(environment_gate, historical_gate)
        self.assertLess(historical_gate, backup)
        self.assertLess(backup, migrations)
        self.assertLess(migrations, post_migration_gate)
        self.assertLess(post_migration_gate, build)

    def test_release_health_is_a_blocking_gate(self) -> None:
        deploy = self.read("deploy/scripts/ci_remote_deploy_light.sh")
        health = self.read("deploy/scripts/verify_release_health.sh")
        self.assertIn("trap rollback_on_failure ERR", deploy)
        self.assertIn("verify_release_health.sh", deploy)
        self.assertNotIn('curl -sf -o /dev/null -w "root:%{http_code}', deploy)
        self.assertIn("release health verification failed", health)
        self.assertIn("Pre-migration backup skipped", deploy)
        self.assertIn('if [ -n "\\$BACKUP_ENCRYPTION_PASSWORD" ]', deploy)
        self.assertIn('--resolve "$PUBLIC_HOST:443:127.0.0.1"', health)
        self.assertIn("http://127.0.0.1:8080/api/health", health)
        self.assertIn("http://127.0.0.1:5174/admin", health)
        self.assertIn("http://127.0.0.1:8090/health", health)
        self.assertIn("docker logs --tail 80", health)
        self.assertIn("worker_media_runtime_ready", health)
        self.assertIn("get_ffmpeg_exe", health)
        self.assertIn("MEDIA_HEALTHCHECK_URL", health)
        self.assertIn("verify_media_delivery.py", health)
        self.assertIn("&& worker_media_runtime_ready", health)
        self.assertNotIn("http_ok http://127.0.0.1/", health)

    def test_monitoring_is_blocking_in_every_deploy_entry(self) -> None:
        linux_deploy = self.read("deploy/scripts/ci_remote_deploy_light.sh")
        windows_deploy = self.read("deploy/scripts/remote_deploy_production.py")
        for deploy in (linux_deploy, windows_deploy):
            self.assertIn(
                "prometheus|grafana|loki|alloy|node-exporter|cadvisor|blackbox-exporter",
                deploy,
            )
            self.assertNotIn("promtail", deploy)
            self.assertNotIn("OPTIONAL_MONITORING_SERVICES", deploy)
            self.assertNotIn("monitoring stack update failed", deploy)
            self.assertIn("verify_release_health.sh", deploy)
            self.assertIn("Ensuring complete monitoring stack", deploy)
            self.assertIn("prometheus grafana loki alloy node-exporter cadvisor blackbox-exporter", deploy)
            self.assertLess(
                deploy.index("Ensuring complete monitoring stack"),
                deploy.index("verify_release_health.sh"),
            )

    def test_mihomo_is_managed_in_every_production_deploy_entry(self) -> None:
        deploys = (
            self.read("deploy/scripts/ci_remote_deploy_light.sh"),
            self.read("deploy/scripts/ci_remote_deploy.sh"),
            self.read("deploy/scripts/remote_deploy_production.py"),
        )
        for deploy in deploys:
            self.assertIn("MIHOMO_ENABLED=true", deploy)
            self.assertIn("MIHOMO_CONTROLLER_SECRET", deploy)
            self.assertIn("docker-compose.proxy.yml", deploy)
            self.assertIn("--env-file ../.env", deploy)
            self.assertIn("Removing legacy Mihomo container", deploy)
            self.assertIn("up -d mihomo", deploy)
            self.assertIn("docker inspect mihomo", deploy)
            self.assertIn("unmanaged Mihomo container named mihomo", deploy)
            self.assertIn("--force-recreate --no-deps", deploy)

        selective_deploys = (deploys[0], deploys[2])
        for deploy in selective_deploys:
            self.assertIn("mihomo|mihomo-init)", deploy)

    def test_project_proxy_overlay_does_not_force_global_application_proxy(self) -> None:
        overlay = self.read("deploy/docker-compose.proxy.yml")
        base = self.read("deploy/docker-compose.yml")
        self.assertIn('"127.0.0.1:${MIHOMO_PROXY_PORT:-7890}:7890"', overlay)
        self.assertNotIn("network_mode: host", overlay)
        for service in ("backend:", "worker:", "agent-service:"):
            self.assertIn(service, overlay)
        self.assertNotIn("HTTP_PROXY: http://mihomo:7890", overlay)
        self.assertNotIn("HTTPS_PROXY: http://mihomo:7890", overlay)
        self.assertNotIn("ALL_PROXY: http", overlay)
        for key in ("HTTP_PROXY", "HTTPS_PROXY", "ALL_PROXY", "http_proxy", "https_proxy", "all_proxy"):
            self.assertEqual(overlay.count(f'{key}: ""'), 3)
            self.assertEqual(base.count(f'{key}: ""'), 5)

        deploys = (
            self.read("deploy/scripts/ci_remote_deploy_light.sh"),
            self.read("deploy/scripts/ci_remote_deploy.sh"),
            self.read("deploy/scripts/remote_deploy_production.py"),
        )
        for deploy in deploys:
            self.assertNotIn("CONTAINER_HTTP_PROXY=http://host.docker.internal:7890", deploy)
            self.assertNotIn("CONTAINER_HTTPS_PROXY=http://host.docker.internal:7890", deploy)
            self.assertIn("LEGACY_APPLICATION_PROXY_KEYS", deploy)
            for key in ("HTTP_PROXY", "HTTPS_PROXY", "ALL_PROXY", "http_proxy", "https_proxy", "all_proxy", "CONTAINER_HTTP_PROXY", "CONTAINER_HTTPS_PROXY"):
                self.assertIn(key, deploy)

    def test_rollback_does_not_recreate_application_dependencies(self) -> None:
        rollback = self.read("deploy/scripts/rollback_release.sh")
        self.assertIn(
            'up -d --force-recreate --no-deps "${app_services[@]}"',
            rollback,
        )
        self.assertIn("--env-file ../.env", rollback)
        self.assertIn("mihomo|mihomo-init)", rollback)

    def test_release_gate_requires_fresh_monitoring_metrics(self) -> None:
        health = self.read("deploy/scripts/verify_release_health.sh")
        self.assertIn('REQUIRE_MONITORING="${REQUIRE_MONITORING:-1}"', health)
        self.assertIn(
            'http://127.0.0.1:${PROMETHEUS_PORT}/api/v1/query',
            health,
        )
        self.assertIn('up{job="alloy"}', health)
        self.assertIn('up{job="cadvisor"}', health)
        self.assertIn(
            'timestamp(container_cpu_usage_seconds_total{job="cadvisor",name=~"ai-supermarket-.*"})',
            health,
        )
        self.assertIn(
            'timestamp(container_memory_working_set_bytes{job="cadvisor",name=~"ai-supermarket-.*"})',
            health,
        )
        self.assertIn(
            'sum(count_over_time({container=~".+"}[15m]))',
            health,
        )
        self.assertIn(
            'http://127.0.0.1:${LOKI_PORT}/loki/api/v1/query',
            health,
        )
        self.assertIn("loki_query_ready", health)
        self.assertGreaterEqual(
            health.count('json_response_has_positive_value "$response"'),
            2,
        )
        self.assertIn("container_memory_metric", health)
        self.assertIn("loki_logs", health)
        self.assertIn("METRIC_MAX_AGE_SECONDS", health)
        self.assertIn('docker port "$container" "${container_port}/tcp"', health)
        self.assertIn(
            'published_port ai-supermarket-grafana 3000 "${GRAFANA_PORT:-3001}"',
            health,
        )
        self.assertIn(
            'published_port ai-supermarket-prometheus 9090 "${PROMETHEUS_PORT:-9091}"',
            health,
        )
        self.assertIn(
            'published_port ai-supermarket-loki 3100 "${LOKI_PORT:-3100}"',
            health,
        )
        self.assertIn(
            'published_port ai-supermarket-alloy 12345 "${ALLOY_PORT:-12345}"',
            health,
        )
        for container in (
            "ai-supermarket-prometheus",
            "ai-supermarket-grafana",
            "ai-supermarket-loki",
            "ai-supermarket-alloy",
            "ai-supermarket-node-exporter",
            "ai-supermarket-cadvisor",
            "ai-supermarket-blackbox-exporter",
        ):
            self.assertIn(container, health)

    def test_monitoring_smoke_check_requires_every_container(self) -> None:
        check = self.read("deploy/scripts/check_monitoring_stack.sh")
        self.assertIn("for container in", check)
        self.assertIn("docker inspect", check)
        self.assertNotIn("grep -E", check)
        self.assertIn('docker port "$container" "${container_port}/tcp"', check)
        self.assertIn(
            'published_port ai-supermarket-grafana 3000 "${GRAFANA_PORT:-3001}"',
            check,
        )
        self.assertIn(
            'published_port ai-supermarket-prometheus 9090 "${PROMETHEUS_PORT:-9091}"',
            check,
        )
        self.assertIn(
            'published_port ai-supermarket-loki 3100 "${LOKI_PORT:-3100}"',
            check,
        )
        self.assertIn(
            'published_port ai-supermarket-alloy 12345 "${ALLOY_PORT:-12345}"',
            check,
        )
        for container in (
            "ai-supermarket-prometheus",
            "ai-supermarket-grafana",
            "ai-supermarket-loki",
            "ai-supermarket-alloy",
            "ai-supermarket-node-exporter",
            "ai-supermarket-cadvisor",
            "ai-supermarket-blackbox-exporter",
        ):
            self.assertIn(container, check)

    def test_production_secrets_are_normalized_and_checked_before_recreate(self) -> None:
        deploy = self.read("deploy/scripts/ci_remote_deploy_light.sh")
        normalize = 'value[0] == value[-1] and value[0] in ("\'", \'"\')'
        self.assertGreaterEqual(deploy.count(normalize), 2)
        self.assertIn("production secret preflight failed", deploy)
        self.assertIn("differs between .env and deploy/.env", deploy)
        self.assertLess(deploy.index("production secret preflight passed"), deploy.index("Force-recreating application containers"))
    def test_rabbitmq_queue_names_are_consistent_across_services(self) -> None:
        environment = self.read(".env.example")
        backend = self.read("backend/src/main/resources/application.yml")
        worker = self.read("worker/config.py")
        compose = self.read("deploy/docker-compose.yml")
        compose_lines = compose.splitlines()

        self.assertIn("RABBITMQ_DEAD_QUEUE=ai.tool.normal.dead", environment)
        self.assertIn("RABBITMQ_RETRY_QUEUE_PREFIX=ai.tool.normal.retry", environment)
        self.assertIn("RABBITMQ_USERNAME=guest", environment)
        self.assertIn("RABBITMQ_PASSWORD=guest", environment)
        self.assertNotIn("RABBITMQ_TASK_DEAD_QUEUE=", environment)
        self.assertNotIn("RABBITMQ_TASK_RETRY_QUEUE_PREFIX=", environment)
        self.assertIn("username: ${RABBITMQ_USERNAME:guest}", backend)
        self.assertIn("password: ${RABBITMQ_PASSWORD:guest}", backend)
        self.assertIn(
            "${RABBITMQ_DEAD_QUEUE:${RABBITMQ_TASK_DEAD_QUEUE:ai.tool.normal.dead}}",
            backend,
        )
        self.assertIn(
            "${RABBITMQ_RETRY_QUEUE_PREFIX:${RABBITMQ_TASK_RETRY_QUEUE_PREFIX:ai.tool.normal.retry}}",
            backend,
        )
        self.assertIn("os.getenv('RABBITMQ_DEAD_QUEUE', 'ai.tool.normal.dead')", worker)
        self.assertIn("os.getenv('RABBITMQ_USERNAME', 'guest')", worker)
        self.assertIn("os.getenv('RABBITMQ_PASSWORD', 'guest')", worker)
        self.assertIn(
            "os.getenv('RABBITMQ_RETRY_QUEUE_PREFIX', 'ai.tool.normal.retry')",
            worker,
        )
        self.assertEqual(
            compose.count(
                "RABBITMQ_DEAD_QUEUE: ${RABBITMQ_DEAD_QUEUE:-ai.tool.normal.dead}"
            ),
            2,
        )
        self.assertEqual(
            compose.count(
                "RABBITMQ_RETRY_QUEUE_PREFIX: ${RABBITMQ_RETRY_QUEUE_PREFIX:-ai.tool.normal.retry}"
            ),
            2,
        )
        self.assertIn(
            "RABBITMQ_DEFAULT_USER: ${RABBITMQ_USERNAME:-guest}",
            compose,
        )
        self.assertIn(
            "RABBITMQ_DEFAULT_PASS: ${RABBITMQ_PASSWORD:-guest}",
            compose,
        )
        self.assertEqual(
            compose_lines.count(
                "      RABBITMQ_USERNAME: ${RABBITMQ_USERNAME:-guest}"
            ),
            2,
        )
        self.assertEqual(
            compose_lines.count(
                "      RABBITMQ_PASSWORD: ${RABBITMQ_PASSWORD:-guest}"
            ),
            2,
        )

    def test_rollback_uses_recorded_previous_revision(self) -> None:
        rollback = self.read("deploy/scripts/rollback_release.sh")
        self.assertIn('get("oldSha", "")', rollback)
        self.assertIn('git reset --hard "$old_sha"', rollback)
        self.assertIn("verify_release_health.sh", rollback)

    def test_deploy_and_rollback_pin_reachable_cadvisor_registry(self) -> None:
        image = "m.daocloud.io/ghcr.io/google/cadvisor:v0.60.5"
        linux_deploy = self.read("deploy/scripts/ci_remote_deploy_light.sh")
        windows_deploy = self.read("deploy/scripts/remote_deploy_production.py")
        rollback = self.read("deploy/scripts/rollback_release.sh")
        tracked_env = self.read("deploy/.env")

        self.assertIn(f"CADVISOR_IMAGE={image}", linux_deploy)
        self.assertIn(f'"CADVISOR_IMAGE={image}"', windows_deploy)
        self.assertIn(f"CADVISOR_IMAGE={image}", tracked_env)
        self.assertIn(f"CADVISOR_IMAGE:-{image}", rollback)
        self.assertIn("export CADVISOR_IMAGE", rollback)

    def test_rollback_restores_production_env_after_git_reset(self) -> None:
        rollback = self.read("deploy/scripts/rollback_release.sh")
        reset = rollback.index('git reset --hard "$old_sha"')
        restore = rollback.index("restored production env for rollback")
        compose = rollback.index('docker compose "${compose_args[@]}" config --services')

        self.assertLess(reset, restore)
        self.assertLess(restore, compose)
        for key in (
            "JWT_SECRET",
            "INTERNAL_API_TOKEN",
            "GRAFANA_ADMIN_PASSWORD",
            "CADVISOR_IMAGE",
        ):
            self.assertIn(key, rollback)
        self.assertIn("rollback env restore failed", rollback)

    def test_monitoring_rollback_recreates_old_revision_stack(self) -> None:
        rollback = self.read("deploy/scripts/rollback_release.sh")
        self.assertIn(
            "prometheus grafana loki alloy node-exporter cadvisor blackbox-exporter",
            rollback,
        )
        self.assertIn(
            'docker compose "${compose_args[@]}" config --services',
            rollback,
        )
        self.assertIn("monitoring_requested", rollback)
        self.assertIn('if [ -z "${DEPLOY_SERVICES//[[:space:]]/}" ]', rollback)
        self.assertIn(
            'up -d --force-recreate "${available_monitoring_services[@]}"',
            rollback,
        )
        self.assertIn("Restoring complete old revision monitoring stack", rollback)
        self.assertIn('REQUIRE_MONITORING=1 bash "$health_script"', rollback)
        self.assertIn('REQUIRE_MONITORING=0 bash "$health_script"', rollback)
        self.assertIn("compatibility mode", rollback)
        self.assertIn("nginx_requested", rollback)
        self.assertIn('up -d --force-recreate nginx', rollback)
        self.assertIn('for service in "${missing_monitoring_services[@]}"', rollback)
        self.assertIn('prometheus) container="ai-supermarket-prometheus"', rollback)
        self.assertIn('alloy) container="ai-supermarket-alloy"', rollback)
        self.assertIn('cadvisor) container="ai-supermarket-cadvisor"', rollback)
        self.assertIn('docker rm -f "$container"', rollback)
        self.assertIn("monitoring_health_contract_supported", rollback)
        self.assertIn('docker port "$container" "${container_port}/tcp"', rollback)
        self.assertIn("old revision lacks loopback monitoring endpoints", rollback)

    def test_default_and_nginx_only_rollback_restore_nginx(self) -> None:
        rollback = self.read("deploy/scripts/rollback_release.sh")
        self.assertIn('nginx) nginx_requested=true', rollback)
        self.assertIn('if [ "$nginx_requested" = true ]', rollback)
        self.assertNotIn('app_services+=("nginx")', rollback)

    def test_windows_deploy_writes_manifest_and_rolls_back_on_failure(self) -> None:
        deploy = self.read("deploy/scripts/remote_deploy_production.py")
        self.assertIn('"oldSha": "$OLD_SHA"', deploy)
        self.assertIn('"newSha": "$NEW_SHA"', deploy)
        self.assertIn("rollback_on_failure()", deploy)
        self.assertIn("trap rollback_on_failure ERR", deploy)
        self.assertIn("rollback_release.sh", deploy)
        self.assertIn("trap - ERR", deploy)
        self.assertLess(
            deploy.index("trap rollback_on_failure ERR"),
            deploy.index('git checkout -B "$GIT_BRANCH" deploy-target -f'),
        )

    def test_media_cache_backfill_is_dry_run_by_default(self) -> None:
        script = self.read("deploy/scripts/backfill_oss_cache_control.py")
        self.assertIn('parser.add_argument("--apply", action="store_true"', script)
        self.assertIn('if args.apply:', script)
        self.assertIn('bucket.update_object_meta', script)
        self.assertNotIn("delete_object", script)

    def test_media_delivery_verifies_immutable_cache_and_transform(self) -> None:
        script = self.read("deploy/scripts/verify_media_delivery.py")
        self.assertIn("max-age=31536000", script)
        self.assertIn('("x-oss-process", "image/resize,w_640', script)
        self.assertIn("etag", script)
        self.assertIn("image/webp", script)

    def test_nginx_only_marks_content_addressed_generated_media_immutable(self) -> None:
        config = self.read("deploy/nginx/snippets/app_locations.conf")
        self.assertIn(
            'location ~* "^/generated/(?:.*/)?[0-9a-f]{40}'
            '(?:\\.[a-z0-9]+)?(?:\\.[a-z0-9-]+\\.[a-z0-9]+)?$" {',
            config,
        )
        self.assertIn("max-age=31536000, immutable", config)
        self.assertIn("max-age=300, must-revalidate", config)
        self.assertEqual(config.count("proxy_hide_header Cache-Control"), 2)

    def test_deploy_contracts_run_real_nginx_syntax_validation(self) -> None:
        workflow = self.read(".github/workflows/dev-delivery.yml")
        self.assertIn("nginx -t", workflow)
        self.assertIn('cert_dir="$(mktemp -d)"', workflow)
        self.assertIn("--add-host backend:127.0.0.1", workflow)
        self.assertIn("--add-host admin-frontend:127.0.0.1", workflow)
        self.assertNotIn("deploy/nginx/wlcloudai.com.key", workflow)


if __name__ == "__main__":
    unittest.main()
