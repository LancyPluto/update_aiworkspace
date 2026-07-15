#!/usr/bin/env bash
# Verify local production monitoring endpoints after docker compose up.
set -euo pipefail

is_valid_port() {
  [[ "$1" =~ ^[0-9]{1,5}$ ]] && (( 10#$1 >= 1 && 10#$1 <= 65535 ))
}

published_port() {
  local container="$1"
  local container_port="$2"
  local fallback="$3"
  local binding
  local port

  while IFS= read -r binding; do
    case "$binding" in
      127.0.0.1:*|\[::1\]:*) port="${binding##*:}" ;;
      *) continue ;;
    esac
    if is_valid_port "$port"; then
      printf '%s' "$port"
      return 0
    fi
  done < <(docker port "$container" "${container_port}/tcp" 2>/dev/null || true)

  if is_valid_port "$fallback"; then
    printf '%s' "$fallback"
    return 0
  fi
  echo "ERROR: no valid loopback port for $container:$container_port" >&2
  return 1
}

echo "Checking monitoring containers ..."
for container in \
    ai-supermarket-prometheus \
    ai-supermarket-grafana \
    ai-supermarket-loki \
    ai-supermarket-alloy \
    ai-supermarket-node-exporter \
    ai-supermarket-cadvisor \
    ai-supermarket-blackbox-exporter; do
  status="$(docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' "$container" 2>/dev/null || true)"
  if [[ "$status" != "healthy" && "$status" != "running" ]]; then
    echo "ERROR: $container is not ready (status=${status:-missing})" >&2
    exit 1
  fi
  echo "  $container: $status"
done

GRAFANA_PORT="$(published_port ai-supermarket-grafana 3000 "${GRAFANA_PORT:-3001}")"
PROMETHEUS_PORT="$(published_port ai-supermarket-prometheus 9090 "${PROMETHEUS_PORT:-9091}")"
LOKI_PORT="$(published_port ai-supermarket-loki 3100 "${LOKI_PORT:-3100}")"
ALLOY_PORT="$(published_port ai-supermarket-alloy 12345 "${ALLOY_PORT:-12345}")"

echo "Checking Grafana health ..."
curl -fsS "http://127.0.0.1:${GRAFANA_PORT}/api/health" >/dev/null
echo "  grafana: ok"

echo "Checking Prometheus readiness ..."
curl -fsS "http://127.0.0.1:${PROMETHEUS_PORT}/-/ready" >/dev/null
echo "  prometheus: ok"

echo "Checking Loki readiness ..."
curl -fsS "http://127.0.0.1:${LOKI_PORT}/ready" >/dev/null
echo "  loki: ok"

echo "Checking Alloy metrics ..."
curl -fsS "http://127.0.0.1:${ALLOY_PORT}/metrics" >/dev/null
echo "  alloy: ok"
