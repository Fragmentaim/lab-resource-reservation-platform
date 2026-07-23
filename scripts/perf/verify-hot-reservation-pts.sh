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

row="$(MYSQL_PWD="$LOADTEST_DB_PASSWORD" mysql --protocol=TCP --host="$db_host" --port="$db_port" --user="$LOADTEST_DB_USERNAME" --database="$db_name" --batch --skip-column-names -e "
SELECT CONCAT(
  s.total_quota, '|', s.remain_quota, '|',
  (SELECT COUNT(*) FROM reservation r WHERE r.slot_id = s.id AND r.status = 'BOOKED'), '|',
  (SELECT COUNT(DISTINCT r.user_id) FROM reservation r WHERE r.slot_id = s.id AND r.status = 'BOOKED'), '|',
  (SELECT COUNT(*) FROM (SELECT user_id FROM reservation WHERE slot_id = s.id AND status = 'BOOKED' GROUP BY user_id HAVING COUNT(*) > 1) d), '|',
  (SELECT COUNT(*) FROM reservation_request q WHERE q.slot_id = s.id AND q.status IN ('PENDING', 'PROCESSING'))
) FROM resource_slot s WHERE s.id = $SLOT_ID;")"

IFS='|' read -r total remain booked distinct_users duplicate_users pending <<< "$row"
printf 'slot=%s total=%s remain=%s booked=%s distinctBookedUsers=%s duplicateBookedUsers=%s pendingRequests=%s\n' \
  "$SLOT_ID" "$total" "$remain" "$booked" "$distinct_users" "$duplicate_users" "$pending"

if [[ "$total" != "$EXPECTED_QUOTA" || "$booked" != "$EXPECTED_QUOTA" || "$remain" != "0" || "$distinct_users" != "$booked" || "$duplicate_users" != "0" || "$pending" != "0" ]]; then
  echo "Verification failed: quota/accounting/duplicate invariant is broken." >&2
  exit 1
fi
echo "Verification passed: no oversell, no duplicate booking, and quota accounting is consistent."
