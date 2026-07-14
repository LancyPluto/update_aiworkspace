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
        self.assertNotIn("http_ok http://127.0.0.1/", health)

    def test_rollback_uses_recorded_previous_revision(self) -> None:
        rollback = self.read("deploy/scripts/rollback_release.sh")
        self.assertIn('get("oldSha", "")', rollback)
        self.assertIn('git reset --hard "$old_sha"', rollback)
        self.assertIn("verify_release_health.sh", rollback)

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
