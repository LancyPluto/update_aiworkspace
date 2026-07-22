import ast
import os
import pathlib
import re
import shutil
import subprocess
import tempfile
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

    def test_model_capability_migration_normalizes_provider_join_collation(self) -> None:
        migration = self.read("sql/111_ai_tool_required_model_capabilities.sql")
        self.assertIn("SET NAMES utf8mb4 COLLATE utf8mb4_unicode_ci;", migration)
        self.assertRegex(
            migration,
            r"ON metadata\.provider_code COLLATE utf8mb4_unicode_ci\s*"
            r"= model\.provider COLLATE utf8mb4_unicode_ci",
        )

    def test_backend_build_uses_strict_persistent_maven_cache(self) -> None:
        dockerfile = self.read("backend/Dockerfile")
        cache_mount = (
            "RUN --mount=type=cache,target=/root/.m2/repository,sharing=locked"
        )
        self.assertTrue(dockerfile.startswith("# syntax=docker/dockerfile:1.7\n"))
        self.assertEqual(2, dockerfile.count(cache_mount))
        self.assertIn("mvn -B dependency:go-offline -DskipTests", dockerfile)
        self.assertNotRegex(
            dockerfile,
            r"dependency:go-offline[^\n]*\|\|\s*true",
        )

    def test_workflow_routes_ci_and_cd_to_isolated_self_hosted_runners(self) -> None:
        workflow = self.read(".github/workflows/dev-delivery.yml")
        fork_guard = (
            "github.event_name != 'pull_request' || "
            "github.event.pull_request.head.repo.full_name == github.repository"
        )

        def job_body(job_name: str) -> str:
            match = re.search(
                rf"^  {re.escape(job_name)}:\n(?P<body>.*?)(?=^  [a-zA-Z0-9_-]+:\n|\Z)",
                workflow,
                re.MULTILINE | re.DOTALL,
            )
            self.assertIsNotNone(match, f"workflow job {job_name} is missing")
            return match.group("body")

        for job_name in (
            "changes",
            "backend-test",
            "agent-service-test",
            "worker-test",
            "frontend-build",
            "deploy-contract-test",
            "python-import-check",
            "security-audit",
        ):
            body = job_body(job_name)
            self.assertIn(
                "runs-on: [self-hosted, linux, x64, ci-isolated]",
                body,
            )
            self.assertIn(fork_guard, body)

        deploy_body = job_body("deploy-production")
        self.assertIn(
            "runs-on: [self-hosted, linux, x64, production-deploy]",
            deploy_body,
        )
        self.assertNotIn("ci-isolated", deploy_body)

    def test_self_hosted_workflow_enforces_trusted_execution_boundaries(self) -> None:
        workflow = self.read(".github/workflows/dev-delivery.yml")
        self.assertIn("permissions:\n  contents: read", workflow)
        self.assertIn(
            "cancel-in-progress: ${{ github.event_name == 'pull_request' }}",
            workflow,
        )
        self.assertIn("fork-pr-policy:", workflow)
        self.assertIn("Reject Fork PR On Self-Hosted CI", workflow)
        self.assertIn(
            "(github.event_name == 'push' || github.event_name == 'workflow_dispatch') "
            "&& github.ref == 'refs/heads/dev'",
            workflow,
        )
        self.assertRegex(
            workflow,
            r"(?s)deploy-production:.*?always\(\)\s*&&\s*!cancelled\(\)",
        )
        self.assertEqual(
            workflow.count("uses: actions/checkout@v6"),
            workflow.count("persist-credentials: false"),
        )

        self.assertIn(
            "CD_CREDENTIALS_FILE: /home/runner/.config/ai-tool-market/deploy.env",
            workflow,
        )
        self.assertIn("Local credential file must be owned", workflow)
        self.assertNotIn("secrets.DEPLOY_", workflow)
        self.assertNotIn("sudo apt-get install", workflow)
        self.assertNotIn('>> "$GITHUB_ENV"', workflow)
        self.assertIn("escaped=\"${escaped//'%'/'%25'}\"", workflow)

    def test_deploy_transport_keeps_credentials_out_of_process_arguments_and_git_config(self) -> None:
        deploy = self.read("deploy/scripts/ci_remote_deploy_light.sh")
        sync = self.read("deploy/scripts/remote_production_git_sync.sh")
        migration = self.read("deploy/scripts/run_prod_asset_migration.py")

        self.assertIn("StrictHostKeyChecking=yes", deploy)
        self.assertIn('UserKnownHostsFile="$DEPLOY_KNOWN_HOSTS_FILE"', deploy)
        self.assertNotIn("UserKnownHostsFile=/dev/null", deploy)
        self.assertIn('[ -L "$DEPLOY_KNOWN_HOSTS_FILE" ]', deploy)
        self.assertIn("8#$known_hosts_mode & 022", deploy)
        self.assertIn("sshpass -e ssh", deploy)
        self.assertIn("sshpass -e scp", deploy)
        self.assertNotIn('sshpass -p "$DEPLOY_PASSWORD"', deploy)
        self.assertNotIn("x-access-token:${GITHUB_TOKEN}@github.com", deploy)
        self.assertIn("GITHUB_TOKEN_STDIN=1", deploy)
        self.assertNotIn('GITHUB_TOKEN="${GITHUB_TOKEN:-}" \\', deploy)

        self.assertIn("GIT_ASKPASS_FILE", sync)
        self.assertIn("GitHub job token was not received on stdin", sync)
        self.assertIn('remote set-url origin "$GIT_REPO_URL"', sync)
        self.assertIn("cleanup_git_auth", sync)
        self.assertIn("git -c credential.helper= clone", sync)
        self.assertIn("git -c credential.helper= fetch", sync)
        self.assertNotRegex(
            migration,
            r'os\.environ\.get\("DEPLOY_PASSWORD",\s*"',
        )

    def test_production_ssh_passwords_are_never_hardcoded(self) -> None:
        candidates = list((ROOT / "deploy" / "scripts").glob("*.py"))
        candidates.extend((ROOT / "scripts").glob("prod*.py"))
        violations: list[str] = []

        for path in candidates:
            source = path.read_text(encoding="utf-8-sig")
            if "paramiko" not in source and "DEPLOY_PASSWORD" not in source:
                continue
            tree = ast.parse(source, filename=str(path))
            for node in ast.walk(tree):
                if isinstance(node, ast.Call) and len(node.args) >= 2:
                    key, default = node.args[:2]
                    if (
                        isinstance(key, ast.Constant)
                        and key.value == "DEPLOY_PASSWORD"
                        and isinstance(default, ast.Constant)
                        and isinstance(default.value, str)
                        and default.value
                    ):
                        violations.append(f"{path.relative_to(ROOT)}:{node.lineno}:env-default")

                if isinstance(node, ast.Call) and isinstance(node.func, ast.Attribute):
                    if node.func.attr == "connect":
                        password = next(
                            (keyword.value for keyword in node.keywords if keyword.arg == "password"),
                            None,
                        )
                        if (
                            isinstance(password, ast.Constant)
                            and isinstance(password.value, str)
                            and password.value
                        ):
                            violations.append(f"{path.relative_to(ROOT)}:{node.lineno}:connect")

                if isinstance(node, (ast.Assign, ast.AnnAssign)):
                    targets = node.targets if isinstance(node, ast.Assign) else [node.target]
                    value = node.value
                    if (
                        isinstance(value, ast.Constant)
                        and isinstance(value.value, str)
                        and value.value
                        and any(
                            isinstance(target, ast.Name)
                            and target.id.lower() in {"password", "deploy_password"}
                            for target in targets
                        )
                    ):
                        violations.append(f"{path.relative_to(ROOT)}:{node.lineno}:assignment")

        powershell = self.read("deploy/diagnose_502.ps1")
        if re.search(r'(?im)^\s*\$password\s*=\s*["\'][^"\']+["\']', powershell):
            violations.append("deploy/diagnose_502.ps1:password-assignment")

        self.assertEqual([], violations, "Hardcoded production SSH credentials: " + ", ".join(violations))

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
        self.assertIn('"$OSSUTIL" stat --endpoint "$OSS_ENDPOINT" --region "$OSS_REGION" "$remote_encrypted"', script)
        self.assertIn('"$OSSUTIL" stat --endpoint "$OSS_ENDPOINT" --region "$OSS_REGION" "$remote_manifest"', script)

    def test_backup_bootstraps_verified_ossutil_and_receives_credentials(self) -> None:
        backup = self.read("deploy/scripts/backup_mysql.sh")
        self.assertIn("OSSUTIL_VERSION=\"${OSSUTIL_VERSION:-2.3.0}\"", backup)
        self.assertIn("expected_ossutil_sha256", backup)
        self.assertIn("ossutil archive checksum mismatch", backup)
        self.assertIn('OSSUTIL="$ossutil_install_dir/ossutil"', backup)
        self.assertIn('--endpoint "$OSS_ENDPOINT"', backup)
        self.assertIn('"$OSSUTIL" cp -f', backup)
        self.assertNotIn("\nossutil() {", backup)
        self.assertIn('OSS_REGION="${OSS_REGION:-}"', backup)
        self.assertIn('OSS_REGION="${endpoint_region#oss-}"', backup)
        self.assertIn('--region "$OSS_REGION"', backup)
        self.assertNotIn('-i "$OSS_ACCESS_KEY_ID"', backup)
        self.assertNotIn('-k "$OSS_ACCESS_KEY_SECRET"', backup)

        for deploy_entry in (
            "deploy/scripts/ci_remote_deploy_light.sh",
            "deploy/scripts/remote_deploy_production.py",
        ):
            deploy = self.read(deploy_entry)
            expansion = "\\$(" if deploy_entry.endswith(".sh") else "$("
            self.assertIn(
                f'export OSS_ENDPOINT="{expansion}read_env_value OSS_ENDPOINT)"', deploy
            )
            self.assertIn(
                f'export OSS_REGION="{expansion}read_env_value OSS_REGION)"', deploy
            )
            self.assertIn(
                f'export OSS_ACCESS_KEY_ID="{expansion}read_env_value OSS_ACCESS_KEY_ID)"',
                deploy,
            )
            self.assertIn(
                f'export OSS_ACCESS_KEY_SECRET="{expansion}read_env_value OSS_ACCESS_KEY_SECRET)"',
                deploy,
            )

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
        ):
            self.assertIn(required_setting, script)
        self.assertIn("guest RabbitMQ credentials are forbidden", script)
        self.assertIn("read-only preflight account cannot be root", script)
        self.assertNotIn("require_positive_decimal", script)
        self.assertNotIn("WORKFLOW_RUNTIME_MAX_RUN_COST_CREDITS", script)
        self.assertNotIn("WORKFLOW_RUNTIME_MAX_USER_DAILY_COST_CREDITS", script)
        self.assertNotIn("WORKFLOW_RUNTIME_MAX_PROVIDER_DAILY_COST_CNY", script)
        self.assertNotIn("WORKFLOW_RUNTIME_COST_ALERT_WEBHOOK_URL", script)
        self.assertIn("backup bucket must be separate from application asset buckets", script)
        self.assertIn("production environment preflight failed", script)

    def test_production_deploy_entries_configure_independent_backup_bucket(self) -> None:
        backup_uri = "BACKUP_OSS_URI=oss://wlcloudai-db-backup-prod/mysql/full"
        for deploy_entry in (
            "deploy/scripts/ci_remote_deploy_light.sh",
            "deploy/scripts/remote_deploy_production.py",
        ):
            self.assertIn(backup_uri, self.read(deploy_entry))

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
        self.assertIn("status = 'CAPTURED'", script)
        self.assertNotIn("active_workflow_provider_reservation_missing", script)
        self.assertNotIn("successful_workflow_actual_provider_cost_unknown", script)
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

    def test_provider_cost_limits_and_direct_webhook_are_not_runtime_configuration(self) -> None:
        root_environment = self.read(".env.example")
        application = self.read("backend/src/main/resources/application.yml")
        preflight = self.read("deploy/scripts/verify_production_environment.sh")
        for setting in (
            "WORKFLOW_RUNTIME_MAX_PROVIDER_DAILY_COST_CNY",
            "WORKFLOW_RUNTIME_COST_ALERT_WEBHOOK_URL",
        ):
            self.assertNotIn(setting, root_environment)
            self.assertNotIn(setting, application)
            self.assertNotIn(setting, preflight)

    def test_deploy_uses_ephemeral_select_only_mysql_preflight_account(self) -> None:
        workflow = self.read(".github/workflows/dev-delivery.yml")
        linux_deploy = self.read("deploy/scripts/ci_remote_deploy_light.sh")
        windows_deploy = self.read("deploy/scripts/remote_deploy_production.py")
        account_script = self.read("deploy/scripts/manage_preflight_mysql_user.sh")
        self.assertIn("openssl rand -hex 24", workflow)
        self.assertIn("PRODUCTION_PREFLIGHT_MYSQL_PASSWORD", workflow)
        for deploy in (linux_deploy, windows_deploy):
            self.assertIn("manage_preflight_mysql_user.sh\" create", deploy)
            self.assertIn("manage_preflight_mysql_user.sh\" drop", deploy)
        self.assertIn("GRANT SELECT ON", account_script)
        self.assertIn("DROP USER IF EXISTS", account_script)
        self.assertNotIn("GRANT ALL", account_script)

    def test_production_credentials_are_persisted_without_logging_values(self) -> None:
        script = self.read("deploy/scripts/prepare_production_credentials.sh")
        self.assertIn("ai-supermarket-rabbitmq", script)
        self.assertIn("rabbitmq-diagnostics -q check_running", script)
        self.assertNotIn("rabbitmq-diagnostics -q ping", script)
        self.assertIn("command output suppressed to protect credentials", script)
        self.assertIn(">/dev/null 2>&1", script)
        self.assertIn("BACKUP_ENCRYPTION_PASSWORD", script)
        self.assertIn("secrets.token_hex", script)
        self.assertIn("secrets.token_urlsafe", script)
        self.assertIn("temporary.chmod(0o600)", script)
        self.assertIn("WORKFLOW_RUNTIME_ENABLED\": \"true", script)
        self.assertIn("WORKFLOW_RUNTIME_EXECUTION_ENABLED\": \"true", script)

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

    def test_linux_remote_deploy_materializes_script_before_execution(self) -> None:
        deploy = self.read("deploy/scripts/ci_remote_deploy_light.sh")
        self.assertIn("run_remote_script() {", deploy)
        self.assertIn('cat > "$remote_script"', deploy)
        self.assertIn('bash "$remote_script"', deploy)
        self.assertIn("run_remote_script <<REMOTE", deploy)
        self.assertNotIn('ssh_cmd "bash -s" <<REMOTE', deploy)

    def test_actual_remote_diff_adds_services_missed_by_runner_detection(self) -> None:
        resolver = ROOT / "deploy/scripts/resolve_deploy_services.sh"
        bash = shutil.which("bash") or "bash"
        if os.name == "nt":
            git = shutil.which("git")
            if git:
                git_bash = pathlib.Path(git).resolve().parent.parent / "bin/bash.exe"
                if git_bash.exists():
                    bash = str(git_bash)
        result = subprocess.run(
            [
                bash,
                resolver.relative_to(ROOT).as_posix(),
                "backend",
                "backend/src/main/java/Example.java",
                "user-web/src/pages/Dashboard/Page.vue",
            ],
            cwd=ROOT,
            check=True,
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="replace",
        )
        self.assertEqual("backend user-web nginx", result.stdout.strip())

    def test_cancelled_sync_remains_in_next_authoritative_diff(self) -> None:
        bash = shutil.which("bash") or "bash"
        if os.name == "nt":
            git_command = shutil.which("git")
            if git_command:
                git_bash = pathlib.Path(git_command).resolve().parent.parent / "bin/bash.exe"
                if git_bash.exists():
                    bash = str(git_bash)
        git_env = os.environ.copy()
        git_env.update(
            {
                "GIT_AUTHOR_NAME": "Deploy Test",
                "GIT_AUTHOR_EMAIL": "deploy-test@example.com",
                "GIT_COMMITTER_NAME": "Deploy Test",
                "GIT_COMMITTER_EMAIL": "deploy-test@example.com",
            }
        )

        temp_root = ROOT / "deploy/logs"
        temp_root.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(dir=temp_root) as temp_dir:
            temp = pathlib.Path(temp_dir)
            origin = temp / "origin.git"
            seed = temp / "seed"
            production = temp / "production"

            def bash_path(path: pathlib.Path) -> str:
                value = path.resolve().as_posix()
                if os.name == "nt":
                    return f"/{value[0].lower()}{value[2:]}"
                return value

            def git(cwd: pathlib.Path, *args: str) -> str:
                result = subprocess.run(
                    ["git", *args],
                    cwd=cwd,
                    env=git_env,
                    check=True,
                    capture_output=True,
                    text=True,
                    encoding="utf-8",
                )
                return result.stdout.strip()

            subprocess.run(["git", "init", "--bare", str(origin)], check=True, capture_output=True)
            seed.mkdir()
            git(seed, "init")
            git(seed, "checkout", "-b", "dev")
            (seed / "README.md").write_text("baseline\n", encoding="utf-8")
            git(seed, "add", ".")
            git(seed, "commit", "-m", "release A")
            release_a = git(seed, "rev-parse", "HEAD")

            user_page = seed / "user-web/src/pages/中文页面.vue"
            user_page.parent.mkdir(parents=True)
            user_page.write_text("<template>aligned</template>\n", encoding="utf-8")
            git(seed, "add", ".")
            git(seed, "commit", "-m", "cancelled release B")
            release_b = git(seed, "rev-parse", "HEAD")

            backend_file = seed / "backend/src/main/java/Example.java"
            backend_file.parent.mkdir(parents=True)
            backend_file.write_text("final class Example {}\n", encoding="utf-8")
            git(seed, "add", ".")
            git(seed, "commit", "-m", "release C")
            release_c = git(seed, "rev-parse", "HEAD")
            git(seed, "remote", "add", "origin", str(origin))
            git(seed, "push", "-u", "origin", "dev")

            subprocess.run(["git", "clone", str(origin), str(production)], check=True, capture_output=True)
            git(production, "checkout", "-B", "dev", release_b)
            (production / ".deploy_revision").write_text(release_a + "\n", encoding="ascii")

            sync_env = git_env.copy()
            sync_env.update(
                {
                    "REMOTE_DIR": str(production),
                    "GIT_REPO_URL": bash_path(origin),
                    "DEPLOY_GIT_REF": release_c,
                    "DEPLOY_GIT_BRANCH": "dev",
                    "DEPLOY_EVENT": "push",
                    "GITHUB_SHA": release_c,
                }
            )
            sync_script = ROOT / "deploy/scripts/remote_production_git_sync.sh"
            sync_env["REMOTE_DIR"] = bash_path(production)

            def run_sync() -> None:
                result = subprocess.run(
                    [bash, sync_script.relative_to(ROOT).as_posix()],
                    cwd=ROOT,
                    env=sync_env,
                    check=False,
                    capture_output=True,
                    text=True,
                    encoding="utf-8",
                    errors="replace",
                )
                self.assertEqual(0, result.returncode, result.stdout + result.stderr)

            for _ in range(2):
                run_sync()
                changed = (production / "deploy/logs/last-deploy.files.txt").read_text(encoding="utf-8")
                self.assertIn("user-web/src/pages/中文页面.vue", changed)
                self.assertIn("backend/src/main/java/Example.java", changed)
                self.assertEqual(
                    release_a,
                    (production / ".deploy_revision").read_text(encoding="ascii").strip(),
                )
                self.assertEqual(
                    "valid",
                    (production / "deploy/logs/deploy-diff-base.status").read_text(encoding="ascii").strip(),
                )

            revision_file = production / ".deploy_revision"
            for invalid_revision in (None, "not-a-sha", "d" * 40):
                if invalid_revision is None:
                    revision_file.unlink(missing_ok=True)
                else:
                    revision_file.write_text(invalid_revision + "\n", encoding="ascii")
                run_sync()
                self.assertEqual(
                    "full",
                    (production / "deploy/logs/deploy-diff-base.status").read_text(encoding="ascii").strip(),
                )

            revision_file.write_text(release_a + "\n", encoding="ascii")
            pending_revision = production / ".deploy_revision.pending"
            pending_meta = production / ".deploy_meta.pending"
            finalize_script = ROOT / "deploy/scripts/finalize_production_release.sh"
            finalize_env = sync_env.copy()
            finalize_env["EXPECTED_SHA"] = release_c

            pending_revision.write_text(release_b + "\n", encoding="ascii")
            pending_meta.write_text("push\ndev\n\n", encoding="utf-8")
            failed_finalize = subprocess.run(
                [bash, finalize_script.relative_to(ROOT).as_posix()],
                cwd=ROOT,
                env=finalize_env,
                check=False,
                capture_output=True,
            )
            self.assertNotEqual(0, failed_finalize.returncode)
            self.assertEqual(release_a, revision_file.read_text(encoding="ascii").strip())

            pending_revision.write_text(release_c + "\n", encoding="ascii")
            successful_finalize = subprocess.run(
                [bash, finalize_script.relative_to(ROOT).as_posix()],
                cwd=ROOT,
                env=finalize_env,
                check=False,
                capture_output=True,
            )
            self.assertEqual(0, successful_finalize.returncode)
            self.assertEqual(release_c, revision_file.read_text(encoding="ascii").strip())

    def test_deploy_revision_advances_only_after_release_gate(self) -> None:
        deploy = self.read("deploy/scripts/ci_remote_deploy_light.sh")
        health = deploy.index('bash "\\$REMOTE_DIR/deploy/scripts/verify_release_health.sh"')
        build_info = deploy.index("Writing user-web build-info.json")
        pending = deploy.index('mv -f "\\$PENDING_REVISION_TMP" "\\$REMOTE_DIR/.deploy_revision.pending"')
        disable_remote_rollback = deploy.index("trap - ERR", pending)
        self.assertLess(health, build_info)
        self.assertLess(build_info, pending)
        self.assertLess(pending, disable_remote_rollback)
        self.assertNotIn('mv -f "\\$REVISION_TMP" "\\$REMOTE_DIR/.deploy_revision"', deploy)
        self.assertIn("EXPECTED_USER_WEB_IMAGE_ID", deploy)
        self.assertIn("RUNNING_USER_WEB_IMAGE_ID", deploy)
        self.assertIn("rollback_after_public_gate_failure", deploy)
        self.assertIn("trap 'rollback_on_failure 129' HUP", deploy)
        self.assertIn("trap 'rollback_on_failure 130' INT TERM", deploy)
        self.assertIn("last-deploy.services.txt", deploy)
        self.assertLess(
            deploy.index("for url in http://wlcloudai.com/"),
            deploy.index("finalize_production_release.sh"),
        )

        finalize = self.read("deploy/scripts/finalize_production_release.sh")
        self.assertIn('pending_sha" != "$EXPECTED_SHA', finalize)
        self.assertIn('head_sha" != "$EXPECTED_SHA', finalize)
        self.assertLess(
            finalize.index('mv -f "$PENDING_META" "$REMOTE_DIR/.deploy_meta"'),
            finalize.index('mv -f "$PENDING_REVISION" "$REMOTE_DIR/.deploy_revision"'),
        )

        sync = self.read("deploy/scripts/remote_production_git_sync.sh")
        self.assertIn('DEPLOY_REVISION_FILE="$REMOTE_DIR/.deploy_revision"', sync)
        self.assertIn('DIFF_BASE_STATUS="full"', sync)
        self.assertIn("core.quotePath=false", sync)
        self.assertNotIn('echo "$NEW_SHA" > .deploy_revision', sync)

        for bootstrap in ("deploy/scripts/bootstrap_production_git.sh", "deploy/scripts/bootstrap_production_git.py"):
            self.assertIn("rm -f .deploy_revision .deploy_meta", self.read(bootstrap))

        manual = self.read("deploy/scripts/remote_deploy_production.py")
        self.assertIn("recorded_sha", manual)
        self.assertIn("EXPECTED_USER_WEB_IMAGE_ID", manual)
        self.assertIn("manual user-web build-info SHA mismatch", manual)
        self.assertIn("trap 'rollback_on_failure 129' HUP", manual)
        self.assertIn("trap 'rollback_on_failure 130' INT TERM", manual)
        self.assertLess(
            manual.index('bash "$REMOTE_DIR/deploy/scripts/verify_release_health.sh"'),
            manual.index('mv -f "$REVISION_TMP" "$REMOTE_DIR/.deploy_revision"'),
        )

        rollback = self.read("deploy/scripts/rollback_release.sh")
        self.assertIn("/dist-out/build-info.json", rollback)
        self.assertLess(
            rollback.index('bash "$health_script"'),
            rollback.index('mv -f "$revision_tmp" "$REMOTE_DIR/.deploy_revision"'),
        )

    def test_production_cd_rejects_rsync_mode(self) -> None:
        workflow = self.read(".github/workflows/dev-delivery.yml")
        deploy = self.read("deploy/scripts/ci_remote_deploy_light.sh")
        self.assertNotIn("          - rsync", workflow)
        self.assertIn('if [ "$DEPLOY_SYNC_MODE" != "git" ]; then', deploy)
        self.assertNotIn("rsync -az --delete", deploy)

    def test_build_info_readback_retries_transient_nginx_disconnects(self) -> None:
        for path in (
            "deploy/scripts/ci_remote_deploy_light.sh",
            "deploy/scripts/remote_deploy_production.py",
        ):
            deploy = self.read(path)
            self.assertIn("for build_info_attempt in", deploy)
            self.assertIn("build-info read attempt", deploy)
            self.assertIn("retrying in 2s", deploy)
            self.assertIn("remained unavailable after retries", deploy)
            self.assertIn("--noproxy '*'", deploy)
            self.assertIn("--resolve wlcloudai.com:443:127.0.0.1", deploy)
            self.assertIn("https://wlcloudai.com/build-info.json?release=", deploy)
            self.assertIn("CANDIDATE_BUILD_INFO_SHA", deploy)
            self.assertNotIn("http://127.0.0.1/build-info.json", deploy)

    def test_external_smoke_check_fails_on_non_success_responses(self) -> None:
        workflow = self.read(".github/workflows/dev-delivery.yml")
        deploy = self.read("deploy/scripts/ci_remote_deploy_light.sh")
        self.assertIn("bash deploy/scripts/ci_remote_deploy_light.sh", workflow)
        self.assertIn("curl --silent --show-error --location", deploy)
        self.assertIn('[[ ! "$code" =~ ^2[0-9]{2}$ ]]', deploy)
        self.assertIn("Public release gate failed", deploy)
        self.assertIn("https://wlcloudai.com/build-info.json", deploy)
        self.assertIn("ACTUAL_DEPLOY_SERVICES", deploy)
        self.assertIn('if [ "$public_user_web_sha" != "${GITHUB_SHA:-unknown}" ]', deploy)
        self.assertIn("Public user-web SHA mismatch", deploy)
        marker = deploy.index("Writing user-web build-info.json")
        health = deploy.index("verify_release_health.sh")
        self.assertGreater(marker, health)
        self.assertIn('if echo "\\$DEPLOY_SERVICES" | grep -qw user-web; then\n  echo "Writing user-web build-info.json', deploy)
        self.assertIn("preserving its existing build-info.json", deploy)

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
            'up -d --force-recreate --no-deps --no-build --pull never "${restorable_app_services[@]}"',
            rollback,
        )
        self.assertIn("--env-file ../.env", rollback)
        self.assertIn("mihomo|mihomo-init)", rollback)

    def test_deploy_snapshots_old_images_and_rollback_never_rebuilds(self) -> None:
        capture = self.read("deploy/scripts/capture_rollback_images.sh")
        rollback = self.read("deploy/scripts/rollback_release.sh")
        linux_deploy = self.read("deploy/scripts/ci_remote_deploy_light.sh")
        windows_deploy = self.read("deploy/scripts/remote_deploy_production.py")

        self.assertIn("last-deploy.images.tsv", capture)
        self.assertIn("{{.Image}}", capture)
        self.assertIn("{{.Config.Image}}", capture)
        self.assertIn("ai-tool-market-rollback-${service}:previous", capture)
        self.assertIn("capture_rollback_images.sh", linux_deploy)
        self.assertIn("capture_rollback_images.sh", windows_deploy)
        self.assertLess(
            linux_deploy.index("capture_rollback_images.sh"),
            linux_deploy.index("apply_sql_migrations.sh"),
        )
        self.assertLess(
            windows_deploy.index("capture_rollback_images.sh"),
            windows_deploy.index("apply_sql_migrations.sh"),
        )
        self.assertIn('docker image tag "$rollback_ref" "$image_ref"', rollback)
        self.assertIn("preserved rollback image changed", rollback)
        self.assertIn("last-deploy.images.tsv", rollback)
        self.assertNotIn(
            'docker compose "${compose_args[@]}" build',
            rollback,
        )

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
