#!/usr/bin/env python3
"""Run the production read-only k6 test with remote Prometheus guardrails."""

from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import shutil
import signal
import subprocess
import sys
import time
from dataclasses import dataclass
from datetime import datetime, timezone
import re
from typing import Any
from urllib.parse import quote


REPO_ROOT = Path(__file__).resolve().parents[2]
K6_SCRIPT = "tests/load/k6-production-readonly.js"
RESULTS_DIR = REPO_ROOT / "tests" / "load" / "results"
K6_IMAGE = "grafana/k6:0.54.0"
POLL_INTERVAL_SECONDS = 15
PROMETHEUS_URL = "http://127.0.0.1:9091"
DEFAULT_REPORT_WINDOW = "15m"
WINDOW_PATTERN = re.compile(r"^[1-9][0-9]*(?:s|m|h|d|w|y)$")

REQUIRED_TARGETS = (
    "backend-actuator",
    "node-exporter",
    "cadvisor",
    "alloy",
    "loki",
)

QUERY_TARGETS = (
    'up{job=~"backend-actuator|node-exporter|cadvisor|alloy|loki"}'
)
QUERY_HTTP_HISTOGRAM_COUNT = (
    'count(http_server_requests_seconds_bucket{job="backend-actuator",'
    'uri!~"/actuator.*"})'
)
QUERY_NODE_CPU_COUNT = 'count(node_cpu_seconds_total{job="node-exporter"})'
QUERY_TOMCAT_BUSY_COUNT = (
    'count(tomcat_threads_busy_threads{job="backend-actuator"})'
)
QUERY_TOMCAT_MAX_COUNT = (
    'count(tomcat_threads_config_max_threads{job="backend-actuator"})'
)
QUERY_5XX_PERCENT = (
    '100 * (sum(rate(http_server_requests_seconds_count{job="backend-actuator",'
    'uri!~"/actuator.*",status=~"5.."}[1m])) or vector(0)) '
    '/ clamp_min(sum(rate(http_server_requests_seconds_count{job="backend-actuator",'
    'uri!~"/actuator.*"}[1m])), 1e-9)'
)
QUERY_P95_SECONDS = (
    'histogram_quantile(0.95, sum by (le) '
    '(rate(http_server_requests_seconds_bucket{job="backend-actuator",'
    'uri!~"/actuator.*"}[1m])))'
)
QUERY_CPU_PERCENT = (
    '100 * (1 - avg(rate(node_cpu_seconds_total{job="node-exporter",'
    'mode="idle"}[1m])))'
)
QUERY_TOMCAT_PERCENT = (
    '100 * max(tomcat_threads_busy_threads{job="backend-actuator"}) '
    '/ clamp_min(max(tomcat_threads_config_max_threads{job="backend-actuator"}), 1)'
)


class RunnerError(RuntimeError):
    """An expected, safely reportable runner failure."""


@dataclass(frozen=True)
class RuntimeSecrets:
    access_token: str
    ssh_host: str
    ssh_user: str
    ssh_password: str
    ssh_port: int = 22


@dataclass(frozen=True)
class MetricsSnapshot:
    five_xx_percent: float
    p95_seconds: float
    cpu_percent: float
    tomcat_percent: float


@dataclass
class GuardState:
    p95_consecutive_breaches: int = 0


@dataclass(frozen=True)
class RouteReport:
    method: str
    uri: str
    peak_qps: float
    average_qps: float
    max_p95_ms: float
    five_xx_increase: float


def parse_args(argv: list[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Safely run the production read-only k6 load test."
    )
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--smoke", action="store_true", help="Run the 30-second smoke test")
    mode.add_argument("--full", action="store_true", help="Run the 12-minute staged test")
    mode.add_argument(
        "--preflight",
        action="store_true",
        help="Only verify SSH, Prometheus targets, and required metrics",
    )
    mode.add_argument(
        "--report",
        action="store_true",
        help="Create a read-only production metrics report without running k6",
    )
    parser.add_argument(
        "--window",
        default=DEFAULT_REPORT_WINDOW,
        help="Report lookback window, for example 15m or 1h (default: 15m)",
    )
    parser.add_argument(
        "--dry-run",
        action="store_true",
        help="Validate the local plan without SSH, Docker, or production traffic",
    )
    parser.add_argument(
        "--self-test",
        action="store_true",
        help=argparse.SUPPRESS,
    )
    args = parser.parse_args(argv)
    if not WINDOW_PATTERN.fullmatch(args.window):
        parser.error("--window must use a positive Prometheus duration such as 15m or 1h")
    if not args.dry_run and not args.self_test and not (
        args.smoke or args.full or args.preflight or args.report
    ):
        parser.error("choose --smoke, --full, --preflight, or --report")
    if args.dry_run and not (args.smoke or args.full or args.preflight or args.report):
        args.smoke = True
    return args


def read_runtime_secrets(require_access_token: bool) -> RuntimeSecrets:
    required = ["PROD_SSH_HOST", "PROD_SSH_USER", "PROD_SSH_PASSWORD"]
    if require_access_token:
        required.insert(0, "K6_ACCESS_TOKEN")
    missing = [name for name in required if not os.environ.get(name, "").strip()]
    if missing:
        raise RunnerError("missing required environment variables: " + ", ".join(missing))

    raw_port = os.environ.get("PROD_SSH_PORT", "22").strip()
    try:
        ssh_port = int(raw_port)
    except ValueError as exc:
        raise RunnerError("PROD_SSH_PORT must be an integer") from exc
    if not 1 <= ssh_port <= 65535:
        raise RunnerError("PROD_SSH_PORT must be between 1 and 65535")

    return RuntimeSecrets(
        access_token=os.environ.get("K6_ACCESS_TOKEN", "").strip(),
        ssh_host=os.environ["PROD_SSH_HOST"].strip(),
        ssh_user=os.environ["PROD_SSH_USER"].strip(),
        ssh_password=os.environ["PROD_SSH_PASSWORD"],
        ssh_port=ssh_port,
    )


def connect_ssh(secrets: RuntimeSecrets) -> Any:
    try:
        import paramiko
    except ImportError as exc:
        raise RunnerError("paramiko is required: pip install paramiko") from exc

    client = paramiko.SSHClient()
    client.load_system_host_keys()
    client.set_missing_host_key_policy(paramiko.RejectPolicy())
    try:
        client.connect(
            hostname=secrets.ssh_host,
            port=secrets.ssh_port,
            username=secrets.ssh_user,
            password=secrets.ssh_password,
            look_for_keys=False,
            allow_agent=False,
            timeout=10,
            auth_timeout=10,
            banner_timeout=10,
        )
    except Exception as exc:
        client.close()
        raise RunnerError(
            "SSH connection failed; verify credentials and the host entry in known_hosts"
        ) from exc
    return client


class RemotePrometheus:
    def __init__(self, ssh_client: Any) -> None:
        self.ssh_client = ssh_client

    def query(self, expression: str) -> list[dict[str, Any]]:
        encoded_query = quote(expression, safe="")
        command = (
            "curl --silent --show-error --fail --max-time 10 "
            f"'{PROMETHEUS_URL}/api/v1/query?query={encoded_query}'"
        )
        try:
            _, stdout, _ = self.ssh_client.exec_command(command, timeout=15)
            payload = stdout.read().decode("utf-8", errors="replace")
            exit_status = stdout.channel.recv_exit_status()
        except Exception as exc:
            raise RunnerError("remote Prometheus query transport failed") from exc
        if exit_status != 0:
            raise RunnerError(
                f"remote Prometheus query failed with exit status {exit_status}"
            )
        try:
            document = json.loads(payload)
            if document.get("status") != "success":
                raise ValueError("Prometheus status was not success")
            result = document["data"]["result"]
            if not isinstance(result, list):
                raise ValueError("Prometheus result was not a vector")
            return result
        except (KeyError, TypeError, ValueError, json.JSONDecodeError) as exc:
            raise RunnerError("remote Prometheus returned an invalid response") from exc

    def scalar(self, expression: str, *, empty_value: float | None = None) -> float:
        result = self.query(expression)
        if not result:
            if empty_value is not None:
                return empty_value
            raise RunnerError("required Prometheus metric returned no samples")
        try:
            value = float(result[0]["value"][1])
        except (KeyError, IndexError, TypeError, ValueError) as exc:
            raise RunnerError("Prometheus scalar result was invalid") from exc
        if value != value or value in (float("inf"), float("-inf")):
            raise RunnerError("Prometheus scalar result was not finite")
        return value


def preflight(prometheus: RemotePrometheus) -> None:
    target_results = prometheus.query(QUERY_TARGETS)
    target_values: dict[str, list[float]] = {job: [] for job in REQUIRED_TARGETS}
    for sample in target_results:
        job = sample.get("metric", {}).get("job")
        if job not in target_values:
            continue
        try:
            target_values[job].append(float(sample["value"][1]))
        except (KeyError, IndexError, TypeError, ValueError) as exc:
            raise RunnerError(f"invalid up metric for target {job}") from exc

    unhealthy = [
        job
        for job, values in target_values.items()
        if not values or any(value != 1.0 for value in values)
    ]
    if unhealthy:
        raise RunnerError("required Prometheus targets are missing or down: " + ", ".join(unhealthy))

    required_metrics = {
        "HTTP histogram": QUERY_HTTP_HISTOGRAM_COUNT,
        "node CPU": QUERY_NODE_CPU_COUNT,
        "Tomcat busy threads": QUERY_TOMCAT_BUSY_COUNT,
        "Tomcat max threads": QUERY_TOMCAT_MAX_COUNT,
    }
    missing_metrics = [
        name
        for name, query in required_metrics.items()
        if prometheus.scalar(query, empty_value=0.0) <= 0
    ]
    if missing_metrics:
        raise RunnerError("required Prometheus metrics are missing: " + ", ".join(missing_metrics))


def collect_metrics(prometheus: RemotePrometheus) -> MetricsSnapshot:
    return MetricsSnapshot(
        five_xx_percent=prometheus.scalar(QUERY_5XX_PERCENT, empty_value=0.0),
        p95_seconds=prometheus.scalar(QUERY_P95_SECONDS, empty_value=0.0),
        cpu_percent=prometheus.scalar(QUERY_CPU_PERCENT),
        tomcat_percent=prometheus.scalar(QUERY_TOMCAT_PERCENT),
    )


def report_queries(window: str) -> dict[str, str]:
    if not WINDOW_PATTERN.fullmatch(window):
        raise RunnerError("invalid report window")
    request_count = (
        'http_server_requests_seconds_count{job="backend-actuator",'
        'uri!~"/actuator.*"}'
    )
    request_buckets = (
        'http_server_requests_seconds_bucket{job="backend-actuator",'
        'uri!~"/actuator.*"}'
    )
    route_qps = f"sum by (method, uri) (rate({request_count}[1m]))"
    route_p95 = (
        "histogram_quantile(0.95, sum by (le, method, uri) "
        f"(rate({request_buckets}[1m])))"
    )
    cpu = (
        '100 * (1 - avg(rate(node_cpu_seconds_total{job="node-exporter",'
        'mode="idle"}[1m])))'
    )
    tomcat = (
        '100 * max(tomcat_threads_busy_threads{job="backend-actuator"}) '
        '/ clamp_min(max(tomcat_threads_config_max_threads{job="backend-actuator"}), 1)'
    )
    return {
        "peak_qps": f"max_over_time(({route_qps})[{window}:15s])",
        "average_qps": f"avg_over_time(({route_qps})[{window}:15s])",
        "max_p95_seconds": f"max_over_time(({route_p95})[{window}:15s])",
        "five_xx_increase": (
            "sum by (method, uri) (increase("
            'http_server_requests_seconds_count{job="backend-actuator",'
            f'uri!~"/actuator.*",status=~"5.."}}[{window}]))'
        ),
        "peak_cpu_percent": f"max_over_time(({cpu})[{window}:15s])",
        "peak_tomcat_percent": f"max_over_time(({tomcat})[{window}:15s])",
    }


def route_values(results: list[dict[str, Any]]) -> dict[tuple[str, str], float]:
    values: dict[tuple[str, str], float] = {}
    for sample in results:
        labels = sample.get("metric", {})
        method = str(labels.get("method", "UNKNOWN"))
        uri = str(labels.get("uri", "UNKNOWN"))
        try:
            value = float(sample["value"][1])
        except (KeyError, IndexError, TypeError, ValueError) as exc:
            raise RunnerError("invalid per-route Prometheus sample") from exc
        if value != value or value in (float("inf"), float("-inf")):
            continue
        values[(method, uri)] = value
    return values


def build_route_reports(
    peak_qps: dict[tuple[str, str], float],
    average_qps: dict[tuple[str, str], float],
    max_p95_seconds: dict[tuple[str, str], float],
    five_xx_increase: dict[tuple[str, str], float],
) -> list[RouteReport]:
    routes = set().union(peak_qps, average_qps, max_p95_seconds, five_xx_increase)
    reports = [
        RouteReport(
            method=method,
            uri=uri,
            peak_qps=peak_qps.get((method, uri), 0.0),
            average_qps=average_qps.get((method, uri), 0.0),
            max_p95_ms=max_p95_seconds.get((method, uri), 0.0) * 1000.0,
            five_xx_increase=five_xx_increase.get((method, uri), 0.0),
        )
        for method, uri in routes
        if peak_qps.get((method, uri), 0.0) > 0.001
        or average_qps.get((method, uri), 0.0) > 0.001
        or five_xx_increase.get((method, uri), 0.0) > 0.0
    ]
    return sorted(reports, key=lambda item: (-item.peak_qps, item.method, item.uri))


def generate_report(prometheus: RemotePrometheus, window: str) -> Path:
    queries = report_queries(window)
    routes = build_route_reports(
        route_values(prometheus.query(queries["peak_qps"])),
        route_values(prometheus.query(queries["average_qps"])),
        route_values(prometheus.query(queries["max_p95_seconds"])),
        route_values(prometheus.query(queries["five_xx_increase"])),
    )
    peak_cpu = prometheus.scalar(queries["peak_cpu_percent"])
    peak_tomcat = prometheus.scalar(queries["peak_tomcat_percent"])
    generated_at = datetime.now(timezone.utc)
    document = {
        "generatedAt": generated_at.isoformat(),
        "window": window,
        "routes": [
            {
                "method": route.method,
                "uri": route.uri,
                "peakQps": round(route.peak_qps, 6),
                "averageQps": round(route.average_qps, 6),
                "maxP95Ms": round(route.max_p95_ms, 3),
                "fiveXxIncrease": round(route.five_xx_increase, 3),
            }
            for route in routes
        ],
        "global": {
            "peakCpuPercent": round(peak_cpu, 3),
            "peakTomcatPercent": round(peak_tomcat, 3),
        },
    }
    RESULTS_DIR.mkdir(parents=True, exist_ok=True)
    timestamp = generated_at.strftime("%Y%m%dT%H%M%SZ")
    output_path = RESULTS_DIR / f"production-report-{timestamp}.json"
    output_path.write_text(
        json.dumps(document, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    print_report_table(routes, peak_cpu, peak_tomcat, window)
    print(f"JSON report: {output_path.relative_to(REPO_ROOT)}")
    return output_path


def print_report_table(
    routes: list[RouteReport], peak_cpu: float, peak_tomcat: float, window: str
) -> None:
    print(f"production metrics report ({window})")
    print(f"peak CPU={peak_cpu:.2f}%  peak Tomcat={peak_tomcat:.2f}%")
    header = f"{'METHOD':<7} {'URI':<56} {'PEAK QPS':>10} {'AVG QPS':>10} {'MAX P95':>10} {'5XX':>8}"
    print(header)
    print("-" * len(header))
    for route in routes:
        uri = route.uri if len(route.uri) <= 56 else route.uri[:53] + "..."
        print(
            f"{route.method:<7} {uri:<56} {route.peak_qps:>10.2f} "
            f"{route.average_qps:>10.2f} {route.max_p95_ms:>9.1f}ms "
            f"{route.five_xx_increase:>8.1f}"
        )


def evaluate_guardrails(snapshot: MetricsSnapshot, state: GuardState) -> str | None:
    if snapshot.p95_seconds > 2.0:
        state.p95_consecutive_breaches += 1
    else:
        state.p95_consecutive_breaches = 0

    if snapshot.five_xx_percent > 1.0:
        return f"5xx rate {snapshot.five_xx_percent:.2f}% exceeded 1%"
    if state.p95_consecutive_breaches >= 4:
        return "P95 exceeded 2s for four consecutive polls"
    if snapshot.cpu_percent > 85.0:
        return f"host CPU {snapshot.cpu_percent:.2f}% exceeded 85%"
    if snapshot.tomcat_percent > 80.0:
        return f"Tomcat thread utilization {snapshot.tomcat_percent:.2f}% exceeded 80%"
    return None


def build_k6_command(smoke: bool) -> list[str]:
    volume = f"{REPO_ROOT}:/work:rw"
    command = [
        "docker",
        "run",
        "--rm",
        "--env",
        "K6_ACCESS_TOKEN",
        "--volume",
        volume,
        "--workdir",
        "/work",
    ]
    if smoke:
        command.extend(["--env", "SMOKE=1"])
    command.extend([K6_IMAGE, "run", K6_SCRIPT])
    return command


def start_k6(command: list[str]) -> subprocess.Popen[Any]:
    if shutil.which("docker") is None:
        raise RunnerError("docker was not found on PATH")
    RESULTS_DIR.mkdir(parents=True, exist_ok=True)
    child_environment = os.environ.copy()
    for name in tuple(child_environment):
        if name.startswith("PROD_SSH_"):
            child_environment.pop(name, None)
    kwargs: dict[str, Any] = {
        "cwd": REPO_ROOT,
        "env": child_environment,
    }
    if os.name == "nt":
        kwargs["creationflags"] = subprocess.CREATE_NEW_PROCESS_GROUP
    else:
        kwargs["start_new_session"] = True
    try:
        return subprocess.Popen(command, **kwargs)
    except OSError as exc:
        raise RunnerError("failed to start dockerized k6") from exc


def stop_k6(process: subprocess.Popen[Any]) -> None:
    if process.poll() is not None:
        return
    try:
        if os.name == "nt":
            process.send_signal(signal.CTRL_BREAK_EVENT)
        else:
            os.killpg(process.pid, signal.SIGINT)
        process.wait(timeout=15)
        return
    except (OSError, subprocess.TimeoutExpired):
        pass
    process.terminate()
    try:
        process.wait(timeout=10)
    except subprocess.TimeoutExpired:
        process.kill()
        process.wait(timeout=5)


def monitor_k6(process: subprocess.Popen[Any], prometheus: RemotePrometheus) -> int:
    state = GuardState()
    while True:
        try:
            return process.wait(timeout=POLL_INTERVAL_SECONDS)
        except subprocess.TimeoutExpired:
            pass

        try:
            snapshot = collect_metrics(prometheus)
        except RunnerError:
            stop_k6(process)
            raise

        print(
            "monitor: "
            f"5xx={snapshot.five_xx_percent:.2f}% "
            f"P95={snapshot.p95_seconds:.3f}s "
            f"CPU={snapshot.cpu_percent:.2f}% "
            f"Tomcat={snapshot.tomcat_percent:.2f}%"
        )
        breach = evaluate_guardrails(snapshot, state)
        if breach:
            print(f"guardrail breached: {breach}", file=sys.stderr)
            stop_k6(process)
            return 3


def run_self_test() -> None:
    state = GuardState()
    high_p95 = MetricsSnapshot(0.0, 2.01, 10.0, 10.0)
    for _ in range(3):
        assert evaluate_guardrails(high_p95, state) is None
    assert evaluate_guardrails(high_p95, state) is not None
    assert evaluate_guardrails(MetricsSnapshot(1.01, 0.1, 10.0, 10.0), GuardState())
    assert evaluate_guardrails(MetricsSnapshot(0.0, 0.1, 85.01, 10.0), GuardState())
    assert evaluate_guardrails(MetricsSnapshot(0.0, 0.1, 10.0, 80.01), GuardState())
    command = build_k6_command(smoke=True)
    assert command.count("K6_ACCESS_TOKEN") == 1
    assert all(os.environ.get(name, "") not in " ".join(command) for name in (
        "K6_ACCESS_TOKEN",
        "PROD_SSH_PASSWORD",
    ) if os.environ.get(name, ""))
    queries = report_queries("15m")
    assert "[15m:15s]" in queries["peak_qps"]
    assert "sum by (method, uri)" in queries["average_qps"]
    reports = build_route_reports(
        {("GET", "/api/v1/tools"): 12.0},
        {("GET", "/api/v1/tools"): 8.0},
        {("GET", "/api/v1/tools"): 0.25},
        {("GET", "/api/v1/tools"): 2.0},
    )
    assert reports[0].max_p95_ms == 250.0
    try:
        report_queries("15 minutes")
    except RunnerError:
        pass
    else:
        raise AssertionError("invalid report window was accepted")
    print("self-test passed")


def main(argv: list[str] | None = None) -> int:
    args = parse_args(argv if argv is not None else sys.argv[1:])
    if args.self_test:
        run_self_test()
        return 0

    if args.dry_run:
        selected_mode = (
            "report"
            if args.report
            else ("preflight" if args.preflight else ("full" if args.full else "smoke"))
        )
        print(f"dry-run passed: mode={selected_mode}, targets={','.join(REQUIRED_TARGETS)}")
        if args.report:
            print(f"report queries validated: window={args.window}")
        elif not args.preflight:
            print("dockerized k6 command validated; secrets are passed by environment name only")
        return 0

    secrets = read_runtime_secrets(require_access_token=not (args.preflight or args.report))
    ssh_client = connect_ssh(secrets)
    process: subprocess.Popen[Any] | None = None
    try:
        prometheus = RemotePrometheus(ssh_client)
        preflight(prometheus)
        print("preflight passed: all required targets and metrics are available")
        if args.preflight:
            return 0
        if args.report:
            generate_report(prometheus, args.window)
            return 0

        command = build_k6_command(smoke=args.smoke)
        process = start_k6(command)
        return monitor_k6(process, prometheus)
    except KeyboardInterrupt:
        if process is not None:
            stop_k6(process)
        print("load test interrupted", file=sys.stderr)
        return 130
    finally:
        if process is not None and process.poll() is None:
            stop_k6(process)
        ssh_client.close()


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except RunnerError as exc:
        print(f"error: {exc}", file=sys.stderr)
        raise SystemExit(2)
