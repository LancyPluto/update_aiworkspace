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
        self.assertNotIn("Duplicate column name.*success", script)
        self.assertNotIn("Duplicate key name.*success", script)

    def test_backup_is_encrypted_and_validated(self) -> None:
        script = self.read("deploy/scripts/backup_mysql.sh")
        self.assertIn("gzip -t", script)
        self.assertIn("-aes-256-cbc", script)
        self.assertIn("sha256sum", script)
        self.assertIn("BACKUP_ENCRYPTION_PASSWORD is required", script)

    def test_restore_cannot_target_production_database(self) -> None:
        script = self.read("deploy/scripts/restore_mysql_to_staging.sh")
        self.assertIn("(_staging|_restore|_verify)", script)
        self.assertIn("backup checksum verification failed", script)
        self.assertIn("DROP DATABASE IF EXISTS", script)

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

    def test_rollback_uses_recorded_previous_revision(self) -> None:
        rollback = self.read("deploy/scripts/rollback_release.sh")
        self.assertIn('get("oldSha", "")', rollback)
        self.assertIn('git reset --hard "$old_sha"', rollback)
        self.assertIn("verify_release_health.sh", rollback)

    def test_deploy_and_rollback_pin_reachable_cadvisor_registry(self) -> None:
        image = "m.daocloud.io/gcr.io/cadvisor/cadvisor:v0.49.1"
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
        self.assertIn("[0-9a-f]{40}", config)
        self.assertIn("max-age=31536000, immutable", config)
        self.assertIn("max-age=300, must-revalidate", config)
        self.assertEqual(config.count("proxy_hide_header Cache-Control"), 2)


if __name__ == "__main__":
    unittest.main()
