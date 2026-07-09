#!/usr/bin/env bash
# Verify local production monitoring endpoints after docker compose up.
set -euo pipefail

echo "Checking Grafana health ..."
curl -fsS http://127.0.0.1:${GRAFANA_PORT:-3001}/api/health >/dev/null
echo "  grafana: ok"

echo "Checking Prometheus readiness ..."
curl -fsS http://127.0.0.1:${PROMETHEUS_PORT:-9091}/-/ready >/dev/null
echo "  prometheus: ok"

echo "Checking monitoring containers ..."
docker ps --format '{{.Names}}' | grep -E 'ai-supermarket-(grafana|prometheus|loki|promtail|node-exporter|cadvisor|blackbox-exporter)' >/dev/null
echo "  containers: present"
