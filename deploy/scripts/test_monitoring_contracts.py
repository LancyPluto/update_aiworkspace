import json
import pathlib
import unittest


ROOT = pathlib.Path(__file__).resolve().parents[2]


class MonitoringContractTests(unittest.TestCase):
    def read(self, relative_path: str) -> str:
        return (ROOT / relative_path).read_text(encoding="utf-8")

    def test_deploy_uses_alloy_and_no_promtail(self) -> None:
        detector = self.read("deploy/scripts/detect_deploy_services.sh")
        deploy = self.read("deploy/scripts/ci_remote_deploy_light.sh")
        self.assertIn("add alloy", detector)
        self.assertNotIn("add promtail", detector)
        self.assertIn("alloy", deploy)
        self.assertNotIn("promtail", deploy)
        self.assertNotIn(
            "Core monitoring stack update failed; application deploy continues",
            deploy,
        )

    def test_alloy_collects_all_docker_logs_with_bounded_labels(self) -> None:
        alloy = self.read("deploy/monitoring/alloy/config.alloy")
        self.assertIn('target_label  = "compose_project"', alloy)
        self.assertNotIn('target_label  = "container_id"', alloy)
        self.assertNotIn('action        = "keep"', alloy)
        self.assertIn("stage.docker {}", alloy)

    def test_loki_retention_is_seven_days(self) -> None:
        loki = self.read("deploy/monitoring/loki/config.yml")
        self.assertIn("retention_period: 168h", loki)
        self.assertIn("retention_enabled: true", loki)

    def test_blackbox_probes_real_production_entrypoints(self) -> None:
        blackbox = self.read("deploy/monitoring/blackbox/config.yml")
        prometheus = self.read("deploy/monitoring/prometheus/prometheus.yml")
        self.assertIn("http_2xx_wlcloud", blackbox)
        self.assertIn("Host: wlcloudai.com", blackbox)
        self.assertNotIn("http://user-web:5173/", prometheus)
        self.assertIn("http://nginx/healthz", prometheus)
        self.assertIn("https://wlcloudai.com/", prometheus)

    def test_cadvisor_and_health_ports_are_declared(self) -> None:
        compose = self.read("deploy/docker-compose.monitoring.yml")
        self.assertIn(
            "m.daocloud.io/gcr.io/cadvisor/cadvisor:v0.49.1",
            compose,
        )
        self.assertIn('127.0.0.1:${LOKI_PORT:-3100}:3100', compose)
        self.assertIn('127.0.0.1:${ALLOY_PORT:-12345}:12345', compose)

    def test_grafana_dashboards_are_valid_and_cover_failures_and_logs(self) -> None:
        overview = json.loads(
            self.read(
                "deploy/monitoring/grafana/dashboards/ai-tool-market-overview.json"
            )
        )
        logs_path = (
            ROOT
            / "deploy/monitoring/grafana/dashboards/production-container-logs-cn.json"
        )
        self.assertTrue(logs_path.exists(), "production container logs dashboard missing")
        logs = json.loads(logs_path.read_text(encoding="utf-8"))
        overview_text = json.dumps(overview, ensure_ascii=False)
        logs_text = json.dumps(logs, ensure_ascii=False)
        self.assertIn(
            "sum(up) / clamp_min(count(up), 1) * 100",
            overview_text,
        )
        self.assertIn("up == 0", overview_text)
        self.assertIn('label_values({container=~\".+\"}, container)', logs_text)
        self.assertIn("${container:regex}", logs_text)
        self.assertIn("${traceId}", logs_text)


if __name__ == "__main__":
    unittest.main()
