import json
import pathlib
import re
import unittest


ROOT = pathlib.Path(__file__).resolve().parents[2]


class MonitoringContractTests(unittest.TestCase):
    def read(self, relative_path: str) -> str:
        return (ROOT / relative_path).read_text(encoding="utf-8")

    def dashboard(self, filename: str) -> dict:
        return json.loads(
            self.read(f"deploy/monitoring/grafana/dashboards/{filename}")
        )

    def panel(self, dashboard: dict, panel_id: int) -> dict:
        return next(panel for panel in dashboard["panels"] if panel["id"] == panel_id)

    def expressions(self, dashboard: dict) -> str:
        return "\n".join(
            target.get("expr", "")
            for panel in dashboard.get("panels", [])
            for target in panel.get("targets", [])
        )

    def test_deploy_uses_alloy_and_no_promtail(self) -> None:
        detector = self.read("deploy/scripts/detect_deploy_services.sh")
        deploy = self.read("deploy/scripts/ci_remote_deploy_light.sh")
        windows_deploy = self.read("deploy/scripts/remote_deploy_production.py")
        self.assertIn("add alloy", detector)
        self.assertNotIn("add promtail", detector)
        self.assertIn("alloy", deploy)
        self.assertNotIn("promtail", deploy)
        self.assertIn("alloy", windows_deploy)
        self.assertNotIn("promtail", windows_deploy)
        self.assertIn("verify_release_health.sh", windows_deploy)
        self.assertNotIn(
            "Core monitoring stack update failed; application deploy continues",
            deploy,
        )
        self.assertNotIn(
            "Core monitoring stack update failed; application deploy continues",
            windows_deploy,
        )

    def test_alloy_collects_all_docker_logs_with_bounded_labels(self) -> None:
        alloy = self.read("deploy/monitoring/alloy/config.alloy")
        self.assertIn('target_label  = "compose_project"', alloy)
        self.assertNotIn('target_label  = "container_id"', alloy)
        self.assertNotIn('action        = "keep"', alloy)
        self.assertIn('loki.source.docker "docker_containers"', alloy)
        self.assertIn('host             = "unix:///var/run/docker.sock"', alloy)
        self.assertNotIn('loki.source.file "docker_containers"', alloy)
        self.assertNotIn('target_label  = "__path__"', alloy)
        self.assertIn("stage.label_drop", alloy)
        self.assertIn('values = ["filename"]', alloy)

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
        self.assertIn("job_name: blackbox-http-nginx", prometheus)
        self.assertIn("job_name: blackbox-http-public", prometheus)

    def test_provider_cost_audit_anomalies_are_visible_as_prometheus_alerts(self) -> None:
        rules = self.read(
            "deploy/monitoring/prometheus/rules/workflow-cost-audit.yml"
        )
        metrics = self.read(
            "backend/src/main/java/com/aiminilab/aitoolmarket/workflow/metrics/WorkflowMetrics.java"
        )
        self.assertIn("workflow_provider_cost_anomaly_total", metrics)
        self.assertIn("WorkflowProviderActualCostUnknown", rules)
        self.assertIn("WorkflowProviderCostCurrencyUnexpected", rules)
        self.assertIn("actual_cost_unknown", rules)
        self.assertIn("currency_unsupported", rules)

    def test_cadvisor_and_health_ports_are_declared(self) -> None:
        compose = self.read("deploy/docker-compose.monitoring.yml")
        self.assertIn(
            "m.daocloud.io/ghcr.io/google/cadvisor:v0.60.5",
            compose,
        )
        self.assertIn('127.0.0.1:${LOKI_PORT:-3100}:3100', compose)
        self.assertIn('127.0.0.1:${ALLOY_PORT:-12345}:12345', compose)

    def test_backend_publishes_http_histograms_and_tomcat_mbeans(self) -> None:
        application = self.read("backend/src/main/resources/application.yml")
        self.assertIn(
            "  tomcat:\n    mbeanregistry:\n      enabled: true",
            application,
        )
        self.assertIn(
            "  metrics:\n    distribution:\n      percentiles-histogram:\n"
            "        http.server.requests: true",
            application,
        )

    def test_release_gate_verifies_monitoring_metrics_and_rollback_is_compatible(
        self,
    ) -> None:
        health = self.read("deploy/scripts/verify_release_health.sh")
        rollback = self.read("deploy/scripts/rollback_release.sh")
        self.assertIn("ai-supermarket-loki", health)
        self.assertIn("ai-supermarket-alloy", health)
        self.assertIn("ai-supermarket-cadvisor", health)
        self.assertIn('up{job="alloy"}', health)
        self.assertIn('up{job="cadvisor"}', health)
        self.assertIn("container_cpu_usage_seconds_total", health)
        self.assertIn("REQUIRE_MONITORING", health)
        self.assertIn("REQUIRE_MONITORING=0", rollback)

    def test_reliability_dashboard_uses_real_http_and_tomcat_metrics(self) -> None:
        dashboard = json.loads(
            self.read(
                "deploy/monitoring/grafana/dashboards/ai-tool-market-reliability-cn.json"
            )
        )
        dashboard_text = json.dumps(dashboard, ensure_ascii=False)
        expressions = self.expressions(dashboard)
        self.assertIn("http_server_requests_seconds_bucket", dashboard_text)
        self.assertIn("tomcat_threads_busy_threads", dashboard_text)
        self.assertIn("tomcat_threads_config_max_threads", dashboard_text)
        self.assertIn("sum by (le, method, uri)", expressions)
        self.assertIn('uri!~"/actuator.*"', expressions)
        self.assertIn(
            "tomcat_threads_busy_threads / clamp_min(tomcat_threads_config_max_threads, 1) * 100",
            expressions,
        )
        self.assertNotIn("ai-supermarket-(backend|worker|agent-service", expressions)
        self.assertIn('{container=~".+"}', expressions)
        self.assertIn('|= ${traceId:doublequote}', expressions)
        self.assertNotIn("${traceId:raw}", expressions)

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
        logs_expressions = self.expressions(logs)
        self.assertIn(
            "sum(up) / clamp_min(count(up), 1) * 100",
            overview_text,
        )
        self.assertIn("up == 0", overview_text)
        container = next(
            item for item in logs["templating"]["list"] if item["name"] == "container"
        )
        self.assertEqual('label_values({container=~".+"}, container)', container["query"])
        self.assertIn("${container:regex}", logs_expressions)
        self.assertIn("${traceId:doublequote}", logs_expressions)

    def test_grafana_provisioning_is_read_only_and_uses_builtin_theme(self) -> None:
        dashboards = self.read(
            "deploy/monitoring/grafana/provisioning/dashboards/dashboards.yml"
        )
        mysql = self.read(
            "deploy/monitoring/grafana/provisioning/datasources/mysql.yml"
        )
        prometheus = self.read(
            "deploy/monitoring/grafana/provisioning/datasources/prometheus.yml"
        )
        compose = self.read("deploy/docker-compose.monitoring.yml")
        self.assertIn("allowUiUpdates: false", dashboards)
        self.assertNotIn("editable: true", dashboards)
        self.assertNotIn("editable: true", mysql)
        self.assertNotIn("editable: true", prometheus)
        self.assertIn('GF_USERS_DEFAULT_THEME: ${GRAFANA_THEME:-dark}', compose)
        self.assertIn('GF_USERS_DEFAULT_LANGUAGE: ${GRAFANA_LANGUAGE:-zh-Hans}', compose)

    def test_overview_has_explicit_datasources_and_volume_capacity(self) -> None:
        overview = json.loads(
            self.read(
                "deploy/monitoring/grafana/dashboards/ai-tool-market-overview.json"
            )
        )
        expressions = self.expressions(overview)
        self.assertIn('job=~"blackbox-http.*"', expressions)
        self.assertIn("node_filesystem_size_bytes", expressions)
        self.assertIn("node_filesystem_avail_bytes", expressions)
        self.assertTrue(all(panel.get("datasource") for panel in overview["panels"]))
        self.assertEqual(
            {panel["id"] for panel in overview["panels"]},
            set(range(1, len(overview["panels"]) + 1)),
        )

    def test_ops_command_center_contract(self) -> None:
        filename = "ops-command-center-cn.json"
        path = ROOT / "deploy/monitoring/grafana/dashboards" / filename
        self.assertTrue(path.exists(), "operations command center dashboard missing")

        dashboard = self.dashboard(filename)
        dashboard_text = json.dumps(dashboard, ensure_ascii=False)
        expressions = self.expressions(dashboard)
        self.assertEqual("ops-command-center-cn", dashboard["uid"])
        self.assertEqual("生产运维驾驶舱", dashboard["title"])
        self.assertEqual("15s", dashboard["refresh"])
        self.assertEqual("now-1h", dashboard["time"]["from"])
        self.assertFalse(dashboard["editable"])
        panel_ids = [panel["id"] for panel in dashboard["panels"]]
        self.assertEqual(20, len(panel_ids))
        self.assertEqual(20, len(set(panel_ids)))
        self.assertEqual(set(range(1, 21)), set(panel_ids))
        for panel in dashboard["panels"]:
            datasource = panel.get("datasource")
            self.assertIsInstance(
                datasource,
                dict,
                f'panel {panel["id"]} datasource must be an object',
            )
            self.assertIsInstance(
                datasource.get("uid"),
                str,
                f'panel {panel["id"]} datasource uid must be a string',
            )
            self.assertTrue(
                datasource["uid"].strip(),
                f'panel {panel["id"]} datasource uid must not be empty',
            )
            if panel["id"] != 19:
                self.assertNotEqual("-- Mixed --", datasource["uid"])

        variables = {
            item["name"]: item for item in dashboard["templating"]["list"]
        }
        self.assertEqual({"component", "uri"}, set(variables))
        component = variables["component"]
        self.assertEqual("custom", component["type"])
        self.assertIs(component["includeAll"], True)
        self.assertEqual(".*", component["allValue"])
        self.assertEqual("All", component["current"]["text"])
        self.assertEqual("$__all", component["current"]["value"])
        self.assertIsInstance(component.get("query"), str)
        component_candidates = {
            candidate.strip() for candidate in component["query"].split(",")
        }
        self.assertTrue(
            {"backend", "agent-service", "worker"}.issubset(component_candidates)
        )

        uri = variables["uri"]
        self.assertEqual("query", uri["type"])
        self.assertEqual(".*", uri["allValue"])
        self.assertEqual(".*", uri["current"]["value"])
        self.assertIn("http_server_requests_seconds_count", uri["definition"])
        self.assertIn('uri!~"/actuator.*"', uri["definition"])

        expected_grid = {
            1: (0, 0, 8, 8),
            2: (8, 0, 4, 4),
            3: (12, 0, 4, 4),
            4: (16, 0, 4, 4),
            5: (20, 0, 4, 4),
            6: (8, 4, 4, 4),
            7: (12, 4, 4, 4),
            8: (16, 4, 4, 4),
            9: (20, 4, 4, 4),
            10: (0, 8, 16, 8),
            11: (16, 8, 8, 4),
            12: (16, 12, 8, 4),
            13: (0, 16, 3, 4),
            14: (3, 16, 3, 4),
            15: (6, 16, 3, 4),
            16: (9, 16, 3, 4),
            17: (12, 16, 3, 4),
            18: (15, 16, 3, 4),
            19: (18, 16, 3, 4),
            20: (21, 16, 3, 4),
        }
        for panel_id, expected in expected_grid.items():
            grid = self.panel(dashboard, panel_id)["gridPos"]
            self.assertEqual(
                expected,
                (grid["x"], grid["y"], grid["w"], grid["h"]),
                f"unexpected grid position for panel {panel_id}",
            )

        panel_1 = self.panel(dashboard, 1)
        panel_1_description = panel_1.get("description", "")
        self.assertIn("明确 down 为故障", panel_1_description)
        self.assertIn("采集或序列缺失为无数据", panel_1_description)
        self.assertNotIn("依赖缺失按故障处理", panel_1_description)
        panel_1_prometheus_targets = [
            target
            for target in panel_1.get("targets", [])
            if isinstance(target.get("datasource"), dict)
            and target["datasource"].get("uid") == "prometheus"
        ]
        self.assertEqual(9, len(panel_1_prometheus_targets))
        self.assertEqual(
            set("ABCDEFGHI"),
            {target.get("refId") for target in panel_1_prometheus_targets},
        )
        self.assertTrue(
            all(
                target.get("instant") is True
                for target in panel_1_prometheus_targets
            ),
            "all panel 1 Prometheus targets must be instant queries",
        )

        panel_1_expression_targets = [
            target
            for target in panel_1.get("targets", [])
            if isinstance(target.get("datasource"), dict)
            and target["datasource"].get("uid") == "__expr__"
        ]
        self.assertEqual(1, len(panel_1_expression_targets))
        panel_1_expression_target = panel_1_expression_targets[0]
        self.assertEqual("math", panel_1_expression_target.get("type"))
        self.assertEqual("J", panel_1_expression_target.get("refId"))
        self.assertEqual("总体状态", panel_1_expression_target.get("legendFormat"))
        compact_math_expression = "".join(
            panel_1_expression_target.get("expression", "").split()
        )
        for ref_id in "ABCDEFGHI":
            for status in (3, 2, 1):
                self.assertIn(f"${ref_id}=={status}", compact_math_expression)
        self.assertNotRegex(compact_math_expression, r"\$[A-I]\+|\+\$[A-I]")

        panel_1_expressions = [
            target.get("expr", "") for target in panel_1_prometheus_targets
        ]
        self.assertEqual(9, len(set(panel_1_expressions)))
        panel_1_state_targets = [
            target
            for target in panel_1_prometheus_targets
            if "histogram_quantile" in target.get("expr", "")
        ]
        self.assertEqual(1, len(panel_1_state_targets))
        compact_p95_value = (
            "max(histogram_quantile(0.95,sumby(le,method,uri)"
            "(rate(http_server_requests_seconds_bucket{job=\"backend-actuator\","
            "uri!~\"/actuator.*\",uri=~\"${uri:regex}\"}[$__rate_interval]))))"
        )
        compact_request_gate = (
            "andon()(sum(rate(http_server_requests_seconds_count{"
            "job=\"backend-actuator\",uri!~\"/actuator.*\","
            "uri=~\"${uri:regex}\"}[$__rate_interval]))>0)"
        )
        compact_gated_p95_value = compact_p95_value + compact_request_gate
        for target in panel_1_state_targets:
            compact_expression = "".join(target["expr"].split())
            self.assertRegex(
                compact_expression,
                rf"\({re.escape(compact_gated_p95_value)}\)(?:>=|<=|>|<)bool",
                f'panel 1 target {target.get("refId")} must gate P95 on traffic',
            )

        panel_1_chain_targets = [
            target
            for target in panel_1_prometheus_targets
            if target.get("legendFormat") == "关键链路"
        ]
        self.assertEqual(1, len(panel_1_chain_targets))
        chain_expression = panel_1_chain_targets[0].get("expr", "")
        compact_chain_expression = "".join(chain_expression.split())
        for fragment in (
            "${component:regex}",
            "prometheus_sd_discovered_targets",
            "backend-actuator|agent-service|worker",
            "cadvisor|alloy|loki",
            'probe_success{job="blackbox-http-public"}',
        ):
            self.assertIn(fragment, chain_expression)
        self.assertNotIn(
            'min(up{job="backend-actuator"}) or on() vector(0)',
            chain_expression,
        )
        expected_target_selector = (
            'ai_monitoring_expected_target{component=~"${component:regex}"}'
        )
        self.assertGreaterEqual(chain_expression.count(expected_target_selector), 2)
        expected_count_with_fallback = (
            f"(count({expected_target_selector})oron()vector(0))"
        )
        discovered_count_with_fallback = (
            "(count(prometheus_sd_discovered_targets{"
            'config=~"backend-actuator|agent-service|worker"})'
            "oron()vector(0))"
        )
        up_count_with_fallback = (
            '(count(up{job=~"backend-actuator|agent-service|worker"})'
            "oron()vector(0))"
        )
        self.assertIn(
            f"{discovered_count_with_fallback}<bool"
            f"{expected_count_with_fallback}",
            compact_chain_expression,
        )
        self.assertIn(
            f"{up_count_with_fallback}<bool{expected_count_with_fallback}",
            compact_chain_expression,
        )
        self.assertRegex(compact_chain_expression, r"==bool0\).*?\*3")
        self.assertRegex(compact_chain_expression, r"<bool4\).*?\*1")

        panel_5_p95_targets = [
            target
            for target in self.panel(dashboard, 5).get("targets", [])
            if "histogram_quantile(0.95" in target.get("expr", "")
        ]
        self.assertEqual(
            1,
            len(panel_5_p95_targets),
            "panel 5 must contain exactly one P95 histogram target",
        )
        panel_5_p95_target = panel_5_p95_targets[0]
        self.assertEqual("全局最高 P95", panel_5_p95_target.get("legendFormat"))
        panel_5_p95_expression = panel_5_p95_target["expr"]
        compact_panel_5_p95_expression = "".join(panel_5_p95_expression.split())
        self.assertTrue(
            compact_panel_5_p95_expression.startswith(
                "max(histogram_quantile(0.95,"
            ),
            "panel 5 P95 target must reduce all URI series to one global value",
        )
        self.assertTrue(
            compact_panel_5_p95_expression.endswith(compact_request_gate),
            "panel 5 P95 target must return no data when request traffic is zero",
        )
        self.assertIn("sum by (le, method, uri)", panel_5_p95_expression)
        self.assertIn("rate(", panel_5_p95_expression)
        self.assertIn("http_server_requests_seconds_bucket", panel_5_p95_expression)
        self.assertIn("[$__rate_interval]", panel_5_p95_expression)
        self.assertIn('job="backend-actuator"', panel_5_p95_expression)
        self.assertIn('uri!~"/actuator.*"', panel_5_p95_expression)
        self.assertIn('uri=~"${uri:regex}"', panel_5_p95_expression)
        compact_panel_5_p95_body = compact_panel_5_p95_expression[
            : -len(compact_request_gate)
        ]
        self.assertNotIn("_sum", compact_panel_5_p95_body)
        self.assertNotIn("_count", compact_panel_5_p95_body)

        panel_10_p95_targets = [
            target
            for target in self.panel(dashboard, 10).get("targets", [])
            if target.get("refId") == "C"
        ]
        self.assertEqual(1, len(panel_10_p95_targets))
        compact_panel_10_p95_expression = "".join(
            panel_10_p95_targets[0].get("expr", "").split()
        )
        self.assertTrue(
            compact_panel_10_p95_expression.startswith(
                "max(histogram_quantile(0.95,"
            ),
            "panel 10 refId C must reduce all URI series to one global P95 value",
        )

        self.assertNotIn('"id": "byQuery"', dashboard_text)
        expected_frame_overrides = {
            10: {"A", "B", "C"},
            19: {"A", "B"},
        }
        for panel_id, expected_ref_ids in expected_frame_overrides.items():
            overrides = self.panel(dashboard, panel_id)["fieldConfig"]["overrides"]
            self.assertEqual(expected_ref_ids, {
                override["matcher"]["options"] for override in overrides
            })
            self.assertTrue(
                all(
                    override["matcher"]["id"] == "byFrameRefID"
                    for override in overrides
                ),
                f"panel {panel_id} must use Grafana 11.4 frame matchers",
            )

        panel_9_expressions = self.expressions(
            {"panels": [self.panel(dashboard, 9)]}
        )
        compact_panel_9 = "".join(panel_9_expressions.split())
        self.assertRegex(
            compact_panel_9,
            r"tomcat_threads_busy_threads(?:\{[^}]*\})?/"
            r"clamp_min\(tomcat_threads_config_max_threads(?:\{[^}]*\})?,1\)\*100",
        )

        panel_20_expressions = self.expressions(
            {"panels": [self.panel(dashboard, 20)]}
        )
        compact_panel_20 = "".join(panel_20_expressions.split())
        self.assertRegex(
            compact_panel_20,
            r"100\*\(1-.*node_filesystem_avail_bytes.*"
            r"/.*node_filesystem_size_bytes",
        )

        for query_fragment in (
            'up{job="loki"}',
            'up{job="alloy"}',
            'up{job="cadvisor"}',
            "${component:regex}",
            "${uri:regex}",
        ):
            self.assertIn(query_fragment, expressions)
        self.assertNotIn("clamp_min(sum(increase", expressions)

        def collect_key_values(value: object, target_key: str) -> list:
            found = []
            if isinstance(value, dict):
                for key, child in value.items():
                    if key == target_key:
                        found.append(child)
                    found.extend(collect_key_values(child, target_key))
            elif isinstance(value, list):
                for child in value:
                    found.extend(collect_key_values(child, target_key))
            return found

        for panel in dashboard["panels"]:
            no_values = collect_key_values(panel.get("fieldConfig", {}), "noValue")
            for no_value in no_values:
                self.assertNotIn(
                    no_value,
                    ("0", 0, "正常"),
                    f'panel {panel["id"]} must not map no data to normal',
                )

        value_mappings = [
            mapping["options"]
            for mapping in self.panel(dashboard, 1).get("fieldConfig", {})
            .get("defaults", {})
            .get("mappings", [])
            if mapping.get("type") == "value" and "options" in mapping
        ]
        expected_statuses = {
            "0": "正常",
            "1": "无数据",
            "2": "风险",
            "3": "故障",
        }
        self.assertTrue(
            any(
                all(
                    options.get(value, {}).get("text") == text
                    for value, text in expected_statuses.items()
                )
                for options in value_mappings
            ),
            "status value mapping must cover normal, no-data, risk, and failure",
        )
        self.assertIn(
            '"text": "无数据"',
            json.dumps(self.panel(dashboard, 1), ensure_ascii=False),
        )

        dashboard_uids = (
            "ai-tool-market-overview",
            "ai-tool-market-reliability-cn",
            "production-container-logs-cn",
            "auth-security-geomap-cn",
        )
        allowed_dashboard_paths = {f"/d/{uid}" for uid in dashboard_uids}
        dashboard_links = dashboard["links"]
        self.assertEqual(4, len(dashboard_links))
        self.assertTrue(all(isinstance(link, dict) for link in dashboard_links))
        self.assertEqual(
            allowed_dashboard_paths,
            {link.get("url") for link in dashboard_links},
        )
        self.assertTrue(all(link.get("keepTime") is True for link in dashboard_links))
        self.assertTrue(
            all(link.get("includeVars") is True for link in dashboard_links)
        )

        field_links = []

        def collect_field_links(value: object) -> None:
            if isinstance(value, dict):
                if value.get("id") == "links" and isinstance(value.get("value"), list):
                    field_links.extend(value["value"])
                for key, child in value.items():
                    if key == "links" and isinstance(child, list):
                        field_links.extend(child)
                    elif key != "value" or value.get("id") != "links":
                        collect_field_links(child)
            elif isinstance(value, list):
                for child in value:
                    collect_field_links(child)

        for panel in dashboard["panels"]:
            collect_field_links(panel.get("fieldConfig", {}))

        field_links_text = json.dumps(field_links, ensure_ascii=False)
        self.assertIn("${__url_time_range}", field_links_text)
        for link in field_links:
            self.assertIsInstance(link, dict)
            url = link.get("url")
            self.assertIsInstance(url, str)
            self.assertIn("${__url_time_range}", url)
            self.assertIn(url.split("?", 1)[0], allowed_dashboard_paths)

        panel_urls = [
            url
            for panel in dashboard["panels"]
            for url in collect_key_values(panel, "url")
            if isinstance(url, str) and url.strip()
        ]
        for url in panel_urls:
            self.assertIn("${__url_time_range}", url)
            url_path = url.split("#", 1)[0].split("?", 1)[0]
            self.assertIn(url_path, allowed_dashboard_paths)

        for forbidden in (
            "/api/admin/",
            "/admin/monitoring",
            "/api/alert",
            "restart",
            "scale",
            "silence",
            "封禁",
            "解封",
            "alerting",
        ):
            self.assertNotIn(forbidden, dashboard_text.lower())

    def test_monitoring_readme_documents_ops_command_center(self) -> None:
        readme = self.read("deploy/monitoring/README.md")
        dashboard_section_match = re.search(
            r"(?ms)^## 生产运维驾驶舱\s*\r?\n(?P<section>.*?)(?=^## |\Z)",
            readme,
        )
        self.assertIsNotNone(
            dashboard_section_match,
            "README must contain the production operations command center section",
        )
        section = dashboard_section_match.group("section")
        normalized_section = " ".join(section.split())
        normalized_readme = " ".join(readme.split())

        acceptance_section_match = re.search(
            r"(?ms)^## 上线后只读验收\s*\r?\n(?P<section>.*?)(?=^## |\Z)",
            readme,
        )
        self.assertIsNotNone(
            acceptance_section_match,
            "README must contain the post-release read-only acceptance section",
        )
        normalized_acceptance_section = " ".join(
            acceptance_section_match.group("section").split()
        )

        for expected in (
            "驾驶舱默认时间范围为最近 1 小时，刷新间隔为 15 秒。",
            "- `正常`：当前有有效指标，且指标处于正常阈值内。",
            "- `风险`：当前有有效指标，但已进入需要关注的风险阈值。",
            "- `故障`：当前有有效指标，且已达到故障阈值或关键依赖不可用。",
            "- `无数据`：所选时段没有请求或指标缺失；该状态不映射为绿色，也不等同于数值 0 或正常。",
            "核心 API 与长耗时 AI 任务分开观察，避免两类不同耗时特征相互稀释。",
            "API P95 来自服务端 `HTTP histogram`，不使用平均耗时，也不使用 Blackbox 探活数据替代。",
            "驾驶舱和下钻看板只用于观察，不提供重启、扩容、封禁、解封或告警编辑操作。",
            "IP 风控仅允许在 `/admin/monitoring` 由人工操作。",
        ):
            self.assertIn(expected, normalized_section)

        for dashboard_name in (
            "基础设施总览",
            "生产可靠性总览",
            "生产容器日志",
            "认证安全地图态势",
        ):
            self.assertIn(dashboard_name, normalized_section)
        self.assertIn(
            "四个详细看板的下钻链接均保留当前时间范围",
            normalized_section,
        )

        for expected in (
            "ops-command-center-cn",
            "分层展示黄金四指标",
            'up{job="loki"}',
            'up{job="alloy"}',
            'up{job="cadvisor"}',
            "当前不引入 OpenTelemetry Collector 和 Tempo。",
        ):
            self.assertIn(expected, normalized_readme)
        self.assertNotIn(
            "当前不引入 OpenTelemetry Collector 和 Tempo。",
            normalized_section,
        )

        self.assertIn(
            "以上验收仅允许只读查看，不得停止容器、制造错误、修改凭据、证书、SSH/CD 配置或 IP 状态。",
            normalized_acceptance_section,
        )

        for forbidden in (
            "已引入 OpenTelemetry",
            "已引入 Tempo",
            "自动封禁",
            "Grafana 执行封禁",
            "允许修改凭据",
            "允许修改证书",
            "允许修改 SSH/CD",
        ):
            self.assertNotIn(forbidden, normalized_readme)

    def test_prometheus_scrapes_loki_self_metrics(self) -> None:
        prometheus = self.read("deploy/monitoring/prometheus/prometheus.yml")
        loki_job_headers = re.findall(
            r'''^  - job_name:[ \t]*(?P<quote>['"]?)loki(?P=quote)[ \t]*$''',
            prometheus,
            flags=re.MULTILINE,
        )
        self.assertEqual(
            1,
            len(loki_job_headers),
            "Prometheus must define exactly one Loki scrape job",
        )
        self.assertRegex(
            prometheus,
            r"(?m)^  - job_name: loki[ \t]*\r?\n"
            r"    metrics_path: /metrics[ \t]*\r?\n"
            r"    static_configs:[ \t]*\r?\n"
            r'      - targets: \["loki:3100"\][ \t]*\r?\n'
            r"        labels:[ \t]*\r?\n"
            r"          service: loki[ \t]*$",
        )

    def test_ops_command_center_declares_expected_core_targets(self) -> None:
        rules_path = (
            ROOT
            / "deploy/monitoring/prometheus/rules/ops-command-center.yml"
        )
        self.assertTrue(
            rules_path.exists(),
            "ops command center expected-target rules file missing",
        )
        rules = rules_path.read_text(encoding="utf-8")
        self.assertNotIn("alert:", rules)
        all_record_keys = re.findall(
            r"(?m)^[ \t]*(?:-[ \t]*)?record[ \t]*:",
            rules,
        )
        self.assertEqual(3, len(all_record_keys))
        record_blocks = re.findall(
            r"(?ms)^[ \t]*-[ \t]*record:[ \t]*"
            r"ai_monitoring_expected_target[ \t]*$"
            r".*?(?=^[ \t]*-[ \t]*(?:record|alert):|\Z)",
            rules,
        )
        self.assertEqual(3, len(record_blocks))
        actual_target_pairs = set()
        for block in record_blocks:
            expressions = re.findall(
                r"(?m)^[ \t]+expr:[ \t]*(\S(?:.*\S)?)[ \t]*$",
                block,
            )
            self.assertEqual(["vector(1)"], expressions)
            components = re.findall(
                r"(?m)^[ \t]+component:[ \t]*(\S+)[ \t]*$",
                block,
            )
            jobs = re.findall(
                r"(?m)^[ \t]+job:[ \t]*(\S+)[ \t]*$",
                block,
            )
            self.assertEqual(1, len(components))
            self.assertEqual(1, len(jobs))
            actual_target_pairs.add((components[0], jobs[0]))
        self.assertEqual(
            {
                ("backend", "backend-actuator"),
                ("agent-service", "agent-service"),
                ("worker", "worker"),
            },
            actual_target_pairs,
        )

        prometheus = self.read("deploy/monitoring/prometheus/prometheus.yml")
        self.assertRegex(
            prometheus,
            r"(?m)^rule_files:[ \t]*\r?\n"
            r"[ \t]+-[ \t]+/etc/prometheus/rules/\*\.yml[ \t]*$",
        )
        compose = self.read("deploy/docker-compose.monitoring.yml")
        self.assertRegex(
            compose,
            r"(?m)^[ \t]+-[ \t]+\./monitoring/prometheus/rules:"
            r"/etc/prometheus/rules:ro[ \t]*$",
        )

    def test_container_logs_dashboard_supports_all_filters_and_empty_service(self) -> None:
        dashboard = json.loads(
            self.read(
                "deploy/monitoring/grafana/dashboards/production-container-logs-cn.json"
            )
        )
        expressions = self.expressions(dashboard)
        variables = {item["name"]: item for item in dashboard["templating"]["list"]}
        self.assertTrue(
            {"container", "service", "stream", "keyword", "traceId"}.issubset(
                variables
            )
        )
        self.assertEqual(".+", variables["container"]["allValue"])
        self.assertEqual(".*", variables["service"]["allValue"])
        self.assertEqual(".*", variables["stream"]["allValue"])
        self.assertIn('service=~"${service:regex}"', expressions)
        self.assertIn("count_over_time", expressions)
        self.assertIn("sum by (container) (rate", expressions)
        self.assertIn("(?i)(error|exception|failed|timeout|warn)", expressions)
        self.assertIn('|= ${traceId:doublequote}', expressions)
        self.assertIn('|~ ${keyword:doublequote}', expressions)
        self.assertNotIn("${traceId:raw}", expressions)
        self.assertNotIn("${keyword:raw}", expressions)
        rate_panels = [
            panel
            for panel in dashboard["panels"]
            if "rate(" in self.expressions({"panels": [panel]})
        ]
        self.assertTrue(rate_panels)
        self.assertTrue(
            all(panel["fieldConfig"]["defaults"]["unit"] == "cps" for panel in rate_panels)
        )
        self.assertTrue(any(panel.get("type") == "logs" for panel in dashboard["panels"]))

    def test_geomap_is_2d_aggregated_and_does_not_expose_ip(self) -> None:
        dashboard = json.loads(
            self.read(
                "deploy/monitoring/grafana/dashboards/auth-security-geomap-cn.json"
            )
        )
        variables = {item["name"] for item in dashboard["templating"]["list"]}
        self.assertTrue({"eventType", "result", "country", "region"}.issubset(variables))
        variable_items = {
            item["name"]: item for item in dashboard["templating"]["list"]
        }
        self.assertIn("${country:sqlstring}", variable_items["region"]["query"])
        dashboard_text = json.dumps(dashboard, ensure_ascii=False)
        for unsafe in ("'$eventType'", "'$result'", "'$country'", "'$region'"):
            self.assertNotIn(unsafe, dashboard_text)
        geomap = next(panel for panel in dashboard["panels"] if panel["type"] == "geomap")
        self.assertEqual(24, geomap["gridPos"]["w"])
        sql = geomap["targets"][0]["rawSql"]
        for field in (
            "latitude",
            "longitude",
            "country",
            "region",
            "city",
            "total_count",
            "failed_count",
            "failure_rate",
        ):
            self.assertIn(field, sql)
        self.assertNotIn("ip_address", sql)
        geomap_text = json.dumps(geomap, ensure_ascii=False)
        self.assertIn('"field": "total_count"', geomap_text)
        self.assertIn('"field": "failure_rate"', geomap_text)
        self.assertIn(
            "/admin/monitoring?ip=${__data.fields.ip_address:percentencode}",
            dashboard_text,
        )
        sql_panels = [
            panel
            for panel in dashboard["panels"]
            if panel.get("targets") and panel["targets"][0].get("rawSql")
        ]
        self.assertGreaterEqual(len(sql_panels), 6)
        for panel in sql_panels:
            panel_sql = panel["targets"][0]["rawSql"]
            for variable in ("eventType", "result", "country", "region"):
                self.assertIn("${" + variable + ":sqlstring}", panel_sql)
        for panel_id in (2, 3):
            panel = next(panel for panel in dashboard["panels"] if panel["id"] == panel_id)
            self.assertEqual(
                {"type": "mysql", "uid": "mysql-auth-audit"},
                panel["datasource"],
            )


if __name__ == "__main__":
    unittest.main()
