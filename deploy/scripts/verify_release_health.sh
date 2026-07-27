#!/usr/bin/env bash
set -euo pipefail

MAX_ATTEMPTS="${MAX_ATTEMPTS:-18}"
RETRY_SECONDS="${RETRY_SECONDS:-5}"
REQUIRED_CONSECUTIVE_SUCCESSES="${REQUIRED_CONSECUTIVE_SUCCESSES:-3}"
PUBLIC_HOST="${PUBLIC_HOST:-wlcloudai.com}"
REQUIRE_MONITORING="${REQUIRE_MONITORING:-1}"
METRIC_MAX_AGE_SECONDS="${METRIC_MAX_AGE_SECONDS:-180}"
BACKEND_EXPECTED_CONTAINER_ID="${BACKEND_EXPECTED_CONTAINER_ID:-}"
BACKEND_RESTART_BASELINE="${BACKEND_RESTART_BASELINE:-}"

if [[ "$REQUIRE_MONITORING" != "0" && "$REQUIRE_MONITORING" != "1" ]]; then
  echo "ERROR: REQUIRE_MONITORING must be 0 or 1" >&2
  exit 2
fi
if ! [[ "$MAX_ATTEMPTS" =~ ^[1-9][0-9]*$ ]]; then
  echo "ERROR: MAX_ATTEMPTS must be a positive integer" >&2
  exit 2
fi
if ! [[ "$REQUIRED_CONSECUTIVE_SUCCESSES" =~ ^[1-9][0-9]*$ ]] \
    || (( REQUIRED_CONSECUTIVE_SUCCESSES > MAX_ATTEMPTS )); then
  echo "ERROR: REQUIRED_CONSECUTIVE_SUCCESSES must be a positive integer no greater than MAX_ATTEMPTS" >&2
  exit 2
fi

container_status() {
  docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' "$1" 2>/dev/null || echo missing
}

container_id() {
  docker inspect --format '{{.Id}}' "$1" 2>/dev/null || echo missing
}

container_restart_count() {
  docker inspect --format '{{.RestartCount}}' "$1" 2>/dev/null || echo missing
}

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

http_status() {
  local url="$1"
  local code
  shift
  if ! code="$(curl --silent --show-error --location --max-time 15 --output /dev/null \
      --write-out '%{http_code}' "$@" "$url" 2>/dev/null)"; then
    code=000
  fi
  printf '%s' "$code"
}

is_container_ready() {
  [[ "$1" == "healthy" || "$1" == "running" ]]
}

is_http_ready() {
  [[ "$1" =~ ^(2|3)[0-9][0-9]$ ]]
}

json_response_has_positive_value() {
  local response="$1"
  python3 - "$response" <<'PY'
import json
import math
import sys

try:
    payload = json.loads(sys.argv[1])
    results = payload["data"]["result"] if payload.get("status") == "success" else []
    for result in results:
        value = float(result["value"][1])
        if math.isfinite(value) and value > 0:
            raise SystemExit(0)
except (IndexError, KeyError, OverflowError, TypeError, ValueError, json.JSONDecodeError):
    pass
raise SystemExit(1)
PY
}

prometheus_query_ready() {
  local query="$1"
  local response
  if ! response="$(curl --silent --show-error --max-time 15 --get \
      --data-urlencode "query=$query" \
      "http://127.0.0.1:${PROMETHEUS_PORT}/api/v1/query" 2>/dev/null)"; then
    return 1
  fi
  json_response_has_positive_value "$response"
}

loki_query_ready() {
  local query="$1"
  local response
  if ! response="$(curl --silent --show-error --max-time 15 --get \
      --data-urlencode "query=$query" \
      "http://127.0.0.1:${LOKI_PORT}/loki/api/v1/query" 2>/dev/null)"; then
    return 1
  fi
  json_response_has_positive_value "$response"
}

worker_media_runtime_ready() {
  if [[ "${MEDIA_VIDEO_PREVIEW_ENABLED:-true}" =~ ^(0|false|no|off)$ ]]; then
    return 0
  fi
  docker exec ai-supermarket-worker python -c \
    'import subprocess; from imageio_ffmpeg import get_ffmpeg_exe; subprocess.run([get_ffmpeg_exe(), "-version"], check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)' \
    >/dev/null 2>&1
}

verify_optional_media_delivery() {
  if [[ -z "${MEDIA_HEALTHCHECK_URL:-}" ]]; then
    echo "Media delivery check skipped: MEDIA_HEALTHCHECK_URL is not configured"
    return 0
  fi
  if ! python3 "$(dirname "$0")/verify_media_delivery.py" "$MEDIA_HEALTHCHECK_URL"; then
    echo "WARNING: media delivery CDN verification failed; release remains available through original media" >&2
  fi
}

dump_failure_diagnostics() {
  echo "--- monitoring gate state ---" >&2
  printf 'alloy=%s cadvisor=%s container-cpu=%s container-memory=%s loki-logs=%s\n' \
    "${alloy_metric:-unknown}" "${cadvisor_metric:-unknown}" \
    "${container_cpu_metric:-unknown}" "${container_memory_metric:-unknown}" \
    "${loki_logs:-unknown}" >&2
  echo "--- container status ---" >&2
  docker ps --format 'table {{.Names}}\t{{.Status}}' >&2 || true
  for container in \
      ai-supermarket-backend \
      ai-supermarket-worker \
      ai-supermarket-agent-service \
      ai-supermarket-admin-frontend \
      ai-supermarket-user-web \
      ai-supermarket-banana-slides \
      ai-supermarket-nginx \
      ai-supermarket-prometheus \
      ai-supermarket-grafana \
      ai-supermarket-loki \
      ai-supermarket-alloy \
      ai-supermarket-node-exporter \
      ai-supermarket-cadvisor \
      ai-supermarket-blackbox-exporter; do
    echo "--- $container logs (last 80 lines) ---" >&2
    docker logs --tail 80 "$container" >&2 2>&1 || true
  done
}

if [ -z "$BACKEND_EXPECTED_CONTAINER_ID" ]; then
  BACKEND_EXPECTED_CONTAINER_ID="$(container_id ai-supermarket-backend)"
fi
if [ -z "$BACKEND_RESTART_BASELINE" ]; then
  BACKEND_RESTART_BASELINE="$(container_restart_count ai-supermarket-backend)"
fi
if [ "$BACKEND_EXPECTED_CONTAINER_ID" = missing ] \
    || ! [[ "$BACKEND_RESTART_BASELINE" =~ ^[0-9]+$ ]]; then
  echo "ERROR: unable to establish backend container stability baseline" >&2
  dump_failure_diagnostics
  exit 1
fi

assert_backend_runtime_unchanged() {
  local current_container_id
  local current_restart_count
  current_container_id="$(container_id ai-supermarket-backend)"
  current_restart_count="$(container_restart_count ai-supermarket-backend)"
  if [ "$current_container_id" != "$BACKEND_EXPECTED_CONTAINER_ID" ] \
      || [ "$current_restart_count" != "$BACKEND_RESTART_BASELINE" ]; then
    echo "ERROR: backend container changed during release health verification: expected id=$BACKEND_EXPECTED_CONTAINER_ID restarts=$BACKEND_RESTART_BASELINE; observed id=$current_container_id restarts=$current_restart_count" >&2
    dump_failure_diagnostics
    exit 1
  fi
}

echo "Backend stability baseline: container=$BACKEND_EXPECTED_CONTAINER_ID restart-count=$BACKEND_RESTART_BASELINE"
consecutive_successes=0
for attempt in $(seq 1 "$MAX_ATTEMPTS"); do
  assert_backend_runtime_unchanged
  nginx="$(container_status ai-supermarket-nginx)"
  user_web="$(container_status ai-supermarket-user-web)"
  backend="$(container_status ai-supermarket-backend)"
  worker="$(container_status ai-supermarket-worker)"
  agent="$(container_status ai-supermarket-agent-service)"
  banana="$(container_status ai-supermarket-banana-slides)"
  public_code="$(http_status "https://$PUBLIC_HOST/" --resolve "$PUBLIC_HOST:443:127.0.0.1")"
  api_code="$(http_status http://127.0.0.1:8080/api/health)"
  backend_readiness_code="$(http_status http://127.0.0.1:8080/actuator/health/readiness)"
  admin_code="$(http_status http://127.0.0.1:5174/admin)"
  agent_code="$(http_status http://127.0.0.1:8090/health)"
  ppt_capabilities_code="$(http_status http://127.0.0.1:8080/api/v2/ppt/capabilities)"
  if docker exec ai-supermarket-backend sh -c \
      "python -c \"import urllib.request; urllib.request.urlopen('http://banana-slides:5000/health', timeout=5).read()\"" \
      >/dev/null 2>&1; then
    ppt_engine_internal=ok
  else
    ppt_engine_internal=failed
  fi

  monitoring_ready=true
  grafana=skipped
  prometheus=skipped
  loki=skipped
  alloy=skipped
  node_exporter=skipped
  cadvisor=skipped
  blackbox=skipped
  grafana_code=skip
  prometheus_code=skip
  loki_code=skip
  alloy_code=skip
  alloy_metric=skip
  cadvisor_metric=skip
  container_cpu_metric=skip
  container_memory_metric=skip
  loki_logs=skip

  if [ "$REQUIRE_MONITORING" = "1" ]; then
    monitoring_ready=false
    grafana="$(container_status ai-supermarket-grafana)"
    prometheus="$(container_status ai-supermarket-prometheus)"
    loki="$(container_status ai-supermarket-loki)"
    alloy="$(container_status ai-supermarket-alloy)"
    node_exporter="$(container_status ai-supermarket-node-exporter)"
    cadvisor="$(container_status ai-supermarket-cadvisor)"
    blackbox="$(container_status ai-supermarket-blackbox-exporter)"
    GRAFANA_PORT="$(published_port ai-supermarket-grafana 3000 "${GRAFANA_PORT:-3001}")"
    PROMETHEUS_PORT="$(published_port ai-supermarket-prometheus 9090 "${PROMETHEUS_PORT:-9091}")"
    LOKI_PORT="$(published_port ai-supermarket-loki 3100 "${LOKI_PORT:-3100}")"
    ALLOY_PORT="$(published_port ai-supermarket-alloy 12345 "${ALLOY_PORT:-12345}")"
    grafana_code="$(http_status "http://127.0.0.1:${GRAFANA_PORT}/api/health")"
    prometheus_code="$(http_status "http://127.0.0.1:${PROMETHEUS_PORT}/-/ready")"
    loki_code="$(http_status "http://127.0.0.1:${LOKI_PORT}/ready")"
    alloy_code="$(http_status "http://127.0.0.1:${ALLOY_PORT}/metrics")"

    alloy_query='(up{job="alloy"} == 1) and (time() - timestamp(up{job="alloy"}) < '"$METRIC_MAX_AGE_SECONDS"')'
    cadvisor_query='(up{job="cadvisor"} == 1) and (time() - timestamp(up{job="cadvisor"}) < '"$METRIC_MAX_AGE_SECONDS"')'
    container_cpu_query='count(time() - timestamp(container_cpu_usage_seconds_total{job="cadvisor",name=~"ai-supermarket-.*"}) < '"$METRIC_MAX_AGE_SECONDS"')'
    container_memory_query='count(time() - timestamp(container_memory_working_set_bytes{job="cadvisor",name=~"ai-supermarket-.*"}) < '"$METRIC_MAX_AGE_SECONDS"')'
    loki_logs_query='sum(count_over_time({container=~".+"}[15m]))'
    prometheus_query_ready "$alloy_query" && alloy_metric=ok || alloy_metric=missing
    prometheus_query_ready "$cadvisor_query" && cadvisor_metric=ok || cadvisor_metric=missing
    prometheus_query_ready "$container_cpu_query" && container_cpu_metric=ok || container_cpu_metric=missing
    prometheus_query_ready "$container_memory_query" && container_memory_metric=ok || container_memory_metric=missing
    loki_query_ready "$loki_logs_query" && loki_logs=ok || loki_logs=missing

    if is_container_ready "$grafana" \
        && is_container_ready "$prometheus" \
        && is_container_ready "$loki" \
        && is_container_ready "$alloy" \
        && is_container_ready "$node_exporter" \
        && is_container_ready "$cadvisor" \
        && is_container_ready "$blackbox" \
        && is_http_ready "$grafana_code" \
        && is_http_ready "$prometheus_code" \
        && is_http_ready "$loki_code" \
        && is_http_ready "$alloy_code" \
        && [ "$alloy_metric" = ok ] \
        && [ "$cadvisor_metric" = ok ] \
        && [ "$container_cpu_metric" = ok ] \
        && [ "$container_memory_metric" = ok ] \
        && [ "$loki_logs" = ok ]; then
      monitoring_ready=true
    fi
  fi

  printf 'Release health attempt %s/%s: app nginx=%s user-web=%s backend=%s worker=%s agent=%s banana=%s; http public=%s api=%s backend-readiness=%s admin=%s agent=%s ppt-capabilities=%s ppt-engine=%s\n' \
    "$attempt" "$MAX_ATTEMPTS" "$nginx" "$user_web" "$backend" "$worker" "$agent" "$banana" \
    "$public_code" "$api_code" "$backend_readiness_code" "$admin_code" "$agent_code" "$ppt_capabilities_code" "$ppt_engine_internal"
  printf '  monitoring required=%s containers grafana=%s prometheus=%s loki=%s alloy=%s node-exporter=%s cadvisor=%s blackbox=%s; http grafana=%s prometheus=%s loki=%s alloy=%s; metrics alloy=%s cadvisor=%s container-cpu=%s container-memory=%s loki-logs=%s\n' \
    "$REQUIRE_MONITORING" "$grafana" "$prometheus" "$loki" "$alloy" "$node_exporter" "$cadvisor" "$blackbox" \
    "$grafana_code" "$prometheus_code" "$loki_code" "$alloy_code" "$alloy_metric" "$cadvisor_metric" \
    "$container_cpu_metric" "$container_memory_metric" "$loki_logs"

  if is_container_ready "$nginx" \
      && is_container_ready "$user_web" \
      && is_container_ready "$backend" \
      && is_container_ready "$worker" \
      && is_container_ready "$agent" \
      && is_container_ready "$banana" \
      && is_http_ready "$public_code" \
      && is_http_ready "$api_code" \
      && [ "$backend_readiness_code" = "200" ] \
      && is_http_ready "$admin_code" \
      && is_http_ready "$agent_code" \
      && [[ "$ppt_capabilities_code" =~ ^(200|401)$ ]] \
      && [ "$ppt_engine_internal" = ok ] \
      && [ "$monitoring_ready" = true ] \
      && worker_media_runtime_ready; then
    assert_backend_runtime_unchanged
    consecutive_successes=$((consecutive_successes + 1))
  else
    consecutive_successes=0
  fi
  printf '  stability consecutive-successes=%s/%s backend-restarts=%s\n' \
    "$consecutive_successes" "$REQUIRED_CONSECUTIVE_SUCCESSES" "$BACKEND_RESTART_BASELINE"
  if (( consecutive_successes >= REQUIRED_CONSECUTIVE_SUCCESSES )); then
    verify_optional_media_delivery
    assert_backend_runtime_unchanged
    echo "Release health verification passed after $consecutive_successes consecutive successful attempts"
    exit 0
  fi
  if (( attempt < MAX_ATTEMPTS )); then
    sleep "$RETRY_SECONDS"
  fi
done

echo "ERROR: release health verification failed" >&2
dump_failure_diagnostics
exit 1
