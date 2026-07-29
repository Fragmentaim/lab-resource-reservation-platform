#!/usr/bin/env bash
set -euo pipefail

SLOT_ID="${1:?usage: verify-hot-reservation-pts.sh SLOT_ID EXPECTED_QUOTA}"
EXPECTED_QUOTA="${2:?usage: verify-hot-reservation-pts.sh SLOT_ID EXPECTED_QUOTA}"
ENV_FILE="${ENV_FILE:-/etc/lab-booking/loadtest.env}"

[[ "$SLOT_ID" =~ ^[1-9][0-9]*$ && "$EXPECTED_QUOTA" =~ ^[1-9][0-9]*$ ]] || { echo "slot id and quota must be positive integers" >&2; exit 2; }
set -a
# shellcheck disable=SC1090
source "$ENV_FILE"
set +a
# This verifier is intentionally hard-wired to the isolated database.
db_name="lab_booking_loadtest"
db_host="${LOADTEST_MYSQL_HOST:-127.0.0.1}"
db_port="${LOADTEST_MYSQL_PORT:-3306}"
redis_db="${LOADTEST_REDIS_DATABASE:-0}"
wait_seconds="${WAIT_SECONDS:-120}"

read_state() {
  MYSQL_PWD="$LOADTEST_DB_PASSWORD" mysql --protocol=TCP --host="$db_host" --port="$db_port" --user="$LOADTEST_DB_USERNAME" --database="$db_name" --batch --skip-column-names -e "
SELECT CONCAT(
  s.total_quota, '|', s.remain_quota, '|',
  (SELECT COUNT(*) FROM reservation r WHERE r.slot_id = s.id AND r.status = 'BOOKED'), '|',
  (SELECT COUNT(DISTINCT r.user_id) FROM reservation r WHERE r.slot_id = s.id AND r.status = 'BOOKED'), '|',
  (SELECT COUNT(*) FROM (SELECT user_id FROM reservation WHERE slot_id = s.id AND status = 'BOOKED' GROUP BY user_id HAVING COUNT(*) > 1) d), '|',
  (SELECT COUNT(*) FROM reservation_request q WHERE q.slot_id = s.id AND q.status = 'CONFIRMED'), '|',
  (SELECT COUNT(*) FROM reservation_request q WHERE q.slot_id = s.id AND q.status IN ('PROCESSING', 'REJECTED', 'EXPIRED'))
) FROM resource_slot s WHERE s.id = $SLOT_ID;"
}

deadline="$(( $(date +%s) + wait_seconds ))"
while true; do
  row="$(read_state)"
  IFS='|' read -r total remain booked distinct_users duplicate_users confirmed abnormal <<< "$row"
  redis_stock="$(redis-cli -n "$redis_db" GET "reservation:hot:v2:stock:$SLOT_ID")"
  redis_users="$(redis-cli -n "$redis_db" HLEN "reservation:hot:v2:users:$SLOT_ID")"
  if [[ "$total" == "$EXPECTED_QUOTA" && "$booked" == "$EXPECTED_QUOTA" \
    && "$confirmed" == "$EXPECTED_QUOTA" && "$remain" == "0" \
    && "$redis_stock" == "0" && "$redis_users" == "$EXPECTED_QUOTA" \
    && "$distinct_users" == "$booked" && "$duplicate_users" == "0" && "$abnormal" == "0" ]]; then
    break
  fi
  if (( $(date +%s) >= deadline )); then
    break
  fi
  sleep 2
done

printf 'slot=%s total=%s mysqlRemain=%s booked=%s confirmedRequests=%s abnormalRequests=%s distinctBookedUsers=%s duplicateBookedUsers=%s redisRemain=%s redisUsers=%s\n' \
  "$SLOT_ID" "$total" "$remain" "$booked" "$confirmed" "$abnormal" "$distinct_users" "$duplicate_users" "$redis_stock" "$redis_users"

if [[ "$total" != "$EXPECTED_QUOTA" || "$booked" != "$EXPECTED_QUOTA" || "$confirmed" != "$EXPECTED_QUOTA" \
  || "$remain" != "0" || "$redis_stock" != "0" || "$redis_users" != "$EXPECTED_QUOTA" \
  || "$distinct_users" != "$booked" || "$duplicate_users" != "0" || "$abnormal" != "0" ]]; then
  echo "Verification failed: quota/accounting/duplicate invariant is broken." >&2
  exit 1
fi
echo "Verification passed: no oversell, no duplicate booking, and Redis/MySQL accounting is consistent."
