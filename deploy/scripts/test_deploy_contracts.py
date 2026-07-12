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

    def test_rollback_uses_recorded_previous_revision(self) -> None:
        rollback = self.read("deploy/scripts/rollback_release.sh")
        self.assertIn('get("oldSha", "")', rollback)
        self.assertIn('git reset --hard "$old_sha"', rollback)
        self.assertIn("verify_release_health.sh", rollback)


if __name__ == "__main__":
    unittest.main()
