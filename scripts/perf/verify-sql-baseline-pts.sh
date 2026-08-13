#!/usr/bin/env bash
set -euo pipefail

SLOT_ID="${1:?usage: verify-sql-baseline-pts.sh SLOT_ID EXPECTED_QUOTA}"
EXPECTED_QUOTA="${2:?usage: verify-sql-baseline-pts.sh SLOT_ID EXPECTED_QUOTA}"
ENV_FILE="${ENV_FILE:-/etc/lab-booking/loadtest.env}"
set -a; source "$ENV_FILE"; set +a

row="$(MYSQL_PWD="$LOADTEST_DB_PASSWORD" mysql -h"${LOADTEST_MYSQL_HOST:-127.0.0.1}" \
  -P"${LOADTEST_MYSQL_PORT:-3306}" -u"$LOADTEST_DB_USERNAME" lab_booking_loadtest -N -e "
SELECT CONCAT(s.total_quota,'|',s.remain_quota,'|',COUNT(r.id),'|',COUNT(DISTINCT r.user_id))
FROM resource_slot s LEFT JOIN reservation r ON r.slot_id=s.id AND r.status='BOOKED'
WHERE s.id=$SLOT_ID GROUP BY s.id;")"
IFS='|' read -r total remain booked distinct_users <<< "$row"
printf 'slot=%s total=%s mysqlRemain=%s booked=%s distinctBookedUsers=%s\n' \
  "$SLOT_ID" "$total" "$remain" "$booked" "$distinct_users"
[[ "$total" == "$EXPECTED_QUOTA" && "$remain" == 0 && "$booked" == "$EXPECTED_QUOTA" \
   && "$distinct_users" == "$EXPECTED_QUOTA" ]]
echo "Verification passed: pure SQL baseline has no oversell or duplicate booking."

