#!/usr/bin/env bash
set -euo pipefail

SLOT_ID="${1:?usage: diagnose-hot-reservation-result.sh SLOT_ID}"
ENV_FILE="${ENV_FILE:-/etc/lab-booking/loadtest.env}"
set -a
# shellcheck disable=SC1090
source "$ENV_FILE"
set +a

mysql_exec() {
  MYSQL_PWD="$LOADTEST_DB_PASSWORD" mysql --protocol=TCP --host="${LOADTEST_MYSQL_HOST:-127.0.0.1}" \
    --port="${LOADTEST_MYSQL_PORT:-3306}" --user="$LOADTEST_DB_USERNAME" \
    --database=lab_booking_loadtest --batch --raw --skip-column-names "$@"
}

echo '=== REJECTED_REQUESTS ==='
mysql_exec -e "SELECT request_id,user_id,status,reservation_id,reject_code,reject_reason,created_at,completed_at
FROM reservation_request WHERE slot_id=$SLOT_ID AND status<>'CONFIRMED' ORDER BY id;"

echo '=== DUPLICATE_USERS_ACROSS_REQUESTS ==='
mysql_exec -e "SELECT user_id,COUNT(*),GROUP_CONCAT(CONCAT(request_id,':',status) ORDER BY id SEPARATOR ',')
FROM reservation_request WHERE slot_id=$SLOT_ID GROUP BY user_id HAVING COUNT(*)>1;"

echo '=== RESULT_OUTBOX ==='
mysql_exec -e "SELECT event_id,aggregate_id,event_type,status,retry_count,created_at,sent_at
FROM message_outbox WHERE aggregate_type='RESERVATION_REQUEST'
AND aggregate_id IN (SELECT request_id FROM reservation_request WHERE slot_id=$SLOT_ID)
ORDER BY id;" | tail -n 10

echo '=== REDIS_REQUEST_STATES_FOR_DB_ROWS ==='
redis_db="${LOADTEST_REDIS_DATABASE:-0}"
while IFS=$'\t' read -r request_id user_id status; do
  request_key="reservation:request:v2:$request_id"
  redis_status="$(redis-cli -n "$redis_db" HGET "$request_key" status)"
  owner="$(redis-cli -n "$redis_db" HGET "reservation:hot:v2:users:$SLOT_ID" "$user_id")"
  if [[ "$status" != CONFIRMED || "$redis_status" != CONFIRMED || "$owner" != "$request_id" ]]; then
    printf '%s\t%s\tmysql=%s\tredis=%s\towner=%s\n' "$request_id" "$user_id" "$status" "$redis_status" "$owner"
  fi
done < <(mysql_exec -e "SELECT request_id,user_id,status FROM reservation_request WHERE slot_id=$SLOT_ID ORDER BY id;")
