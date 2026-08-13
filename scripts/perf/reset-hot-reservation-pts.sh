#!/usr/bin/env bash
set -euo pipefail

SLOT_ID="${1:?usage: reset-hot-reservation-pts.sh SLOT_ID}"
ENV_FILE="${ENV_FILE:-/etc/lab-booking/loadtest.env}"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
[[ "$SLOT_ID" =~ ^[1-9][0-9]*$ ]] || { echo "slot id must be a positive integer" >&2; exit 2; }

set -a
# shellcheck disable=SC1090
source "$ENV_FILE"
set +a

db_name="lab_booking_loadtest"
db_host="${LOADTEST_MYSQL_HOST:-127.0.0.1}"
db_port="${LOADTEST_MYSQL_PORT:-3306}"
redis_db="${LOADTEST_REDIS_DATABASE:-0}"
mysql_exec() {
  MYSQL_PWD="$LOADTEST_DB_PASSWORD" mysql --protocol=TCP --host="$db_host" --port="$db_port" \
    --user="$LOADTEST_DB_USERNAME" --database="$db_name" --batch --skip-column-names "$@"
}

slot_row="$(mysql_exec -e "SELECT CONCAT(r.resource_code, '|', s.slot_type, '|', s.total_quota, '|', s.remain_quota) FROM resource_slot s JOIN resource r ON r.id = s.resource_id WHERE s.id = $SLOT_ID;")"
IFS='|' read -r resource_code slot_type total_quota remain_quota <<< "$slot_row"
if [[ "$resource_code" != PTS-HOT-* || "$slot_type" != "HOT" || ! "$total_quota" =~ ^[1-9][0-9]*$ ]]; then
  echo "Safety stop: slot $SLOT_ID is not a PTS HOT test slot." >&2
  exit 1
fi

before_booked="$(mysql_exec -e "SELECT COUNT(*) FROM reservation WHERE slot_id = $SLOT_ID AND status = 'BOOKED';")"
before_requests="$(mysql_exec -e "SELECT COUNT(*) FROM reservation_request WHERE slot_id = $SLOT_ID;")"

# 请求账本只覆盖真正进入 MQ 的请求；售罄前后复用过的 CSV 也可能在 Redis
# 留下终态 request hash，因此重置时同时收集账本和当前压测 CSV 中的 requestId。
request_ids_file="$(mktemp)"
trap 'rm -f "$request_ids_file"' EXIT
mysql_exec -e "SELECT request_id FROM reservation_request WHERE slot_id = $SLOT_ID;" > "$request_ids_file"
for csv_file in "$SCRIPT_DIR"/*reservation*tokens.csv; do
  [[ -f "$csv_file" ]] || continue
  awk -F',' -v slot="$SLOT_ID" 'NR > 1 && $3 == slot && $4 != "" { gsub(/\r/, "", $4); print $4 }' \
    "$csv_file" >> "$request_ids_file"
done

deleted_request_keys=0
while IFS= read -r request_id; do
  [[ -z "$request_id" ]] && continue
  deleted="$(redis-cli -n "$redis_db" DEL "reservation:request:v2:$request_id")"
  deleted_request_keys=$((deleted_request_keys + deleted))
done < <(sort -u "$request_ids_file")

mysql_exec -e "
START TRANSACTION;
DELETE n
  FROM user_notification n
  JOIN reservation_request q
    ON n.event_id = CONCAT('RESERVATION_RESULT:RESERVATION_REQUEST:', q.request_id)
 WHERE q.slot_id = $SLOT_ID;
DELETE n
  FROM user_notification n
  JOIN reservation r ON n.related_reservation_id = r.id
 WHERE r.slot_id = $SLOT_ID;
DELETE n
  FROM user_notification n
  JOIN reservation_reminder_task t ON n.reminder_task_id = t.id
 WHERE t.slot_id = $SLOT_ID;
DELETE mo
  FROM message_outbox mo
  JOIN reservation_request q
    ON mo.aggregate_type = 'RESERVATION_REQUEST' AND mo.aggregate_id = q.request_id
 WHERE q.slot_id = $SLOT_ID;
DELETE mo
  FROM message_outbox mo
  JOIN reservation r ON mo.aggregate_type = 'RESERVATION' AND CAST(mo.aggregate_id AS UNSIGNED) = r.id
 WHERE r.slot_id = $SLOT_ID;
DELETE FROM reservation_reminder_task WHERE slot_id = $SLOT_ID;
DELETE FROM reservation_request WHERE slot_id = $SLOT_ID;
DELETE FROM reservation WHERE slot_id = $SLOT_ID;
UPDATE resource_slot SET remain_quota = total_quota, updated_at = NOW() WHERE id = $SLOT_ID;
COMMIT;"

"$SCRIPT_DIR/preheat-hot-reservation-redis.sh" "$SLOT_ID"
echo "Reset complete: slot=$SLOT_ID removedBooked=$before_booked removedRequests=$before_requests deletedRequestKeys=$deleted_request_keys restoredQuota=$total_quota"
