#!/usr/bin/env bash
set -euo pipefail

OUT_FILE="${1:-/opt/lab-booking/loadtest/ecs-metrics-$(date +%Y%m%d%H%M%S).csv}"
INTERVAL_SECONDS="${INTERVAL_SECONDS:-1}"
ENV_FILE="${ENV_FILE:-/etc/lab-booking/loadtest.env}"
if [[ -r "$ENV_FILE" ]]; then
  set -a
  # shellcheck disable=SC1090
  source "$ENV_FILE"
  set +a
fi
REDIS_DB="${LOADTEST_REDIS_DATABASE:-0}"
SERVICE_NAME="${LOADTEST_SERVICE_NAME:-lab-booking-loadtest.service}"
mkdir -p "$(dirname "$OUT_FILE")"
echo 'timestamp,load1,mem_available_kb,java_cpu_pct,java_rss_kb,established_8082,redis_ops_per_sec' > "$OUT_FILE"
trap 'exit 0' INT TERM
while true; do
  ts="$(date -Is)"
  load1="$(awk '{print $1}' /proc/loadavg)"
  mem_available="$(awk '/MemAvailable/ {print $2}' /proc/meminfo)"
  service_pid="$(systemctl show "$SERVICE_NAME" -p MainPID --value 2>/dev/null || true)"
  if [[ "$service_pid" =~ ^[1-9][0-9]*$ ]]; then
    java_stats="$(ps -p "$service_pid" -o %cpu=,rss= | awk 'NR==1 {print $1 "," $2}')"
  else
    java_stats="0,0"
  fi
  established="$(ss -nt state established '( sport = :8082 )' | tail -n +2 | wc -l)"
  redis_ops="$(redis-cli -n "$REDIS_DB" INFO stats 2>/dev/null | awk -F: '/instantaneous_ops_per_sec/ {gsub(/\r/, "", $2); print $2; exit}')"
  echo "$ts,$load1,$mem_available,${java_stats:-0,0},$established,${redis_ops:-0}" >> "$OUT_FILE"
  sleep "$INTERVAL_SECONDS"
done
