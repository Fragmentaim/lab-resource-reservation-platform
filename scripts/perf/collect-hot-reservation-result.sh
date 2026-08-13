#!/usr/bin/env bash
set -euo pipefail

SLOT_ID="${1:?usage: collect-hot-reservation-result.sh SLOT_ID EXPECTED_QUOTA}"
EXPECTED_QUOTA="${2:?usage: collect-hot-reservation-result.sh SLOT_ID EXPECTED_QUOTA}"
ENV_FILE="${ENV_FILE:-/etc/lab-booking/loadtest.env}"
METRICS_FILE="${METRICS_FILE:-/opt/lab-booking/loadtest/ecs-metrics-b-20260813.csv}"
[[ "$SLOT_ID" =~ ^[1-9][0-9]*$ && "$EXPECTED_QUOTA" =~ ^[1-9][0-9]*$ ]] || exit 2

set -a
# shellcheck disable=SC1090
source "$ENV_FILE"
set +a

mysql_exec() {
  MYSQL_PWD="$LOADTEST_DB_PASSWORD" mysql --protocol=TCP --host="${LOADTEST_MYSQL_HOST:-127.0.0.1}" \
    --port="${LOADTEST_MYSQL_PORT:-3306}" --user="$LOADTEST_DB_USERNAME" \
    --database=lab_booking_loadtest --batch --skip-column-names "$@"
}

echo '=== FINAL_DB ==='
mysql_exec -e "
SELECT s.total_quota, s.remain_quota,
  (SELECT COUNT(*) FROM reservation r WHERE r.slot_id=s.id AND r.status='BOOKED') AS booked,
  (SELECT COUNT(DISTINCT r.user_id) FROM reservation r WHERE r.slot_id=s.id AND r.status='BOOKED') AS distinct_users,
  (SELECT COUNT(*) FROM reservation_request q WHERE q.slot_id=s.id AND q.status='CONFIRMED') AS confirmed,
  (SELECT COUNT(*) FROM reservation_request q WHERE q.slot_id=s.id AND q.status='PROCESSING') AS processing,
  (SELECT COUNT(*) FROM reservation_request q WHERE q.slot_id=s.id AND q.status='REJECTED') AS rejected,
  (SELECT COUNT(*) FROM reservation_request q WHERE q.slot_id=s.id AND q.status='EXPIRED') AS expired
FROM resource_slot s WHERE s.id=$SLOT_ID;
SELECT status, COUNT(*) FROM reservation_request WHERE slot_id=$SLOT_ID GROUP BY status;
SELECT MIN(created_at), MAX(created_at), MIN(completed_at), MAX(completed_at),
  ROUND(TIMESTAMPDIFF(MICROSECOND, MIN(created_at), MAX(completed_at)) / 1000)
FROM reservation_request WHERE slot_id=$SLOT_ID AND status='CONFIRMED';
SELECT status, COUNT(*) FROM message_outbox
WHERE aggregate_type='RESERVATION_REQUEST'
  AND aggregate_id IN (SELECT request_id FROM reservation_request WHERE slot_id=$SLOT_ID)
GROUP BY status;"

echo '=== REDIS ==='
echo "stock=$(redis-cli -n "${LOADTEST_REDIS_DATABASE:-0}" GET "reservation:hot:v2:stock:$SLOT_ID")"
echo "users=$(redis-cli -n "${LOADTEST_REDIS_DATABASE:-0}" HLEN "reservation:hot:v2:users:$SLOT_ID")"

echo '=== INVARIANTS ==='
WAIT_SECONDS=5 "$(dirname "$0")/verify-hot-reservation-pts.sh" "$SLOT_ID" "$EXPECTED_QUOTA" || true

echo '=== ECS_METRICS ==='
python3 - "$METRICS_FILE" <<'PY'
import csv, os, sys
p=sys.argv[1]
rows=[]
if os.path.exists(p):
    with open(p, encoding='utf-8') as f:
        for r in csv.DictReader(f):
            try:
                rows.append({
                    'load': float(r['load1']),
                    'mem': int(r['mem_available_kb']),
                    'cpu': float(r['java_cpu_pct'] or 0),
                    'rss': int(r['java_rss_kb'] or 0),
                    'conn': int(r['established_8082'] or 0),
                    'redis': int(r['redis_ops_per_sec'] or 0),
                })
            except (KeyError, TypeError, ValueError):
                pass
print(f'samples={len(rows)}')
if rows:
    for key in ('load', 'cpu', 'rss', 'conn', 'redis'):
        values=sorted(row[key] for row in rows)
        p95=values[min(len(values)-1, int(len(values)*0.95))]
        print(f'{key}: max={max(values)} p95={p95}')
    print(f'mem_available_min_kb={min(row["mem"] for row in rows)}')
PY

echo '=== SERVICES ==='
systemctl is-active lab-booking-loadtest rocketmq-broker mysql redis-server
