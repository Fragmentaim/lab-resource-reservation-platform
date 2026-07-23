#!/usr/bin/env bash
set -euo pipefail

SLOT_ID="${1:?usage: preheat-hot-reservation-redis.sh SLOT_ID}"
ENV_FILE="${ENV_FILE:-/etc/lab-booking/loadtest.env}"
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

slot_row="$(mysql_exec -e "SELECT CONCAT(resource_id, '|', slot_type, '|', status, '|', remain_quota, '|', COALESCE(UNIX_TIMESTAMP(open_time) * 1000, -1), '|', COALESCE(UNIX_TIMESTAMP(end_datetime) * 1000, -1)) FROM resource_slot WHERE id = $SLOT_ID;")"
IFS='|' read -r resource_id slot_type status remain_quota open_at_millis end_at_millis <<< "$slot_row"
if [[ "$slot_type" != "HOT" || "$status" != "OPEN" || ! "$resource_id" =~ ^[1-9][0-9]*$ || ! "$remain_quota" =~ ^[0-9]+$ || ! "$open_at_millis" =~ ^[0-9]+$ || ! "$end_at_millis" =~ ^[0-9]+$ ]]; then
  echo "Slot $SLOT_ID is not an open HOT slot." >&2
  exit 1
fi

stock_key="reservation:hot:stock:$SLOT_ID"
users_key="reservation:hot:users:$SLOT_ID"
loaded_key="reservation:hot:loaded:$SLOT_ID"
snapshot_key="reservation:hot:snapshot:$SLOT_ID"
redis-cli -n "$redis_db" DEL "$stock_key" "$users_key" "$loaded_key" "$snapshot_key" >/dev/null
redis-cli -n "$redis_db" HSET "$snapshot_key" \
  resourceId "$resource_id" \
  status "$status" \
  openAtMillis "$open_at_millis" \
  endAtMillis "$end_at_millis" >/dev/null
redis-cli -n "$redis_db" SET "$stock_key" "$remain_quota" >/dev/null
while IFS= read -r user_id; do
  [[ -n "$user_id" ]] && redis-cli -n "$redis_db" SADD "$users_key" "$user_id" >/dev/null
done < <(mysql_exec -e "SELECT user_id FROM reservation WHERE slot_id = $SLOT_ID AND status = 'BOOKED';")
redis-cli -n "$redis_db" SET "$loaded_key" 1 >/dev/null

echo "Redis cache and HOT-slot snapshot preheated for slot=$SLOT_ID, redisDb=$redis_db, remainingQuota=$remain_quota"
