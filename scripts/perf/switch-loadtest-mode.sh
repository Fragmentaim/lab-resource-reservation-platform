#!/usr/bin/env bash
set -euo pipefail

MODE="${1:?usage: switch-loadtest-mode.sh optimized|sql}"
ENV_FILE="${ENV_FILE:-/etc/lab-booking/loadtest.env}"
case "$MODE" in
  optimized) enabled=true ;;
  sql) enabled=false ;;
  *) echo "mode must be optimized or sql" >&2; exit 2 ;;
esac

tmp="$(mktemp)"
trap 'rm -f "$tmp"' EXIT
grep -vE '^(LOADTEST_OPTIMIZED_ENABLED|LOADTEST_MQ_ENABLED)=' "$ENV_FILE" > "$tmp"
printf 'LOADTEST_OPTIMIZED_ENABLED=%s\nLOADTEST_MQ_ENABLED=%s\n' "$enabled" "$enabled" >> "$tmp"
install -m 600 "$tmp" "$ENV_FILE"
systemctl restart lab-booking-loadtest.service

for _ in $(seq 1 60); do
  if systemctl is-active --quiet lab-booking-loadtest.service \
    && curl -s --connect-timeout 1 --max-time 2 -o /dev/null "http://172.18.225.136:8082/"; then
    echo "Load-test backend is ready in $MODE mode."
    exit 0
  fi
  sleep 1
done

journalctl -u lab-booking-loadtest.service -n 80 --no-pager >&2
exit 1
