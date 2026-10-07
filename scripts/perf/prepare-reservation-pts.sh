#!/usr/bin/env bash
set -euo pipefail

COUNT="${COUNT:-500}"
QUOTA="${QUOTA:-200}"
SLOT_TYPE="${SLOT_TYPE:?SLOT_TYPE must be NORMAL or HOT}"
BASE_URL="${BASE_URL:-http://172.18.225.136:8082}"
RUN_ID="${RUN_ID:-$(date +%Y%m%d%H%M%S)}"
PASSWORD="${LOADTEST_USER_PASSWORD:-LoadTest!2026}"
ENV_FILE="${ENV_FILE:-/etc/lab-booking/loadtest.env}"
OUT_DIR="${OUT_DIR:-/opt/lab-booking/loadtest}"

[[ "$COUNT" =~ ^[1-9][0-9]*$ && "$QUOTA" =~ ^[1-9][0-9]*$ ]] || exit 2
(( QUOTA <= COUNT )) || exit 2
[[ "$SLOT_TYPE" == NORMAL || "$SLOT_TYPE" == HOT ]] || exit 2
set -a; source "$ENV_FILE"; set +a

mysql_exec() {
  MYSQL_PWD="$LOADTEST_DB_PASSWORD" mysql --protocol=TCP --host="${LOADTEST_MYSQL_HOST:-127.0.0.1}" \
    --port="${LOADTEST_MYSQL_PORT:-3306}" --user="$LOADTEST_DB_USERNAME" \
    --database=lab_booking_loadtest --batch --skip-column-names "$@"
}

resource_id="$(mysql_exec -e "
  INSERT INTO resource(resource_code,resource_name,resource_type,status,location,description,created_at,updated_at)
  VALUES('PTS-${SLOT_TYPE}-${RUN_ID}','PTS ${SLOT_TYPE} 对比资源','TEST_FIELD','AVAILABLE','loadtest','isolated benchmark',NOW(),NOW());
  SELECT LAST_INSERT_ID();")"
slot_id="$(mysql_exec -e "
  INSERT INTO resource_slot(resource_id,start_datetime,end_datetime,slot_type,open_time,total_quota,remain_quota,status,created_at,updated_at)
  VALUES($resource_id,DATE_ADD(NOW(),INTERVAL 2 DAY),DATE_ADD(DATE_ADD(NOW(),INTERVAL 2 DAY),INTERVAL 2 HOUR),
         '$SLOT_TYPE',IF('$SLOT_TYPE'='HOT',DATE_SUB(NOW(),INTERVAL 1 MINUTE),NULL),$QUOTA,$QUOTA,'OPEN',NOW(),NOW());
  SELECT LAST_INSERT_ID();")"

mkdir -p "$OUT_DIR"
csv="$OUT_DIR/${SLOT_TYPE,,}-reservation-tokens.csv"
tmp_response="$(mktemp)"
trap 'rm -f "$tmp_response"' EXIT
printf 'accessToken,resourceId,slotId,requestId\n' > "$csv"

for ((i=1; i<=COUNT; i++)); do
  username="pts_${SLOT_TYPE,,}_${RUN_ID}_$(printf '%04d' "$i")"
  body="{\"username\":\"$username\",\"password\":\"$PASSWORD\",\"confirmPassword\":\"$PASSWORD\",\"nickname\":\"PTS-$i\"}"
  curl -fsS --connect-timeout 5 --max-time 20 -H 'Content-Type: application/json' -d "$body" "$BASE_URL/auth/register" -o /dev/null
  login="{\"username\":\"$username\",\"password\":\"$PASSWORD\"}"
  curl -fsS --connect-timeout 5 --max-time 20 -H 'Content-Type: application/json' -d "$login" "$BASE_URL/auth/login" -o "$tmp_response"
  token="$(python3 - "$tmp_response" <<'PY'
import json, sys
data=json.load(open(sys.argv[1],encoding='utf-8'))
print(data['data']['token'],end='')
PY
)"
  printf '%s,%s,%s,%s\n' "$token" "$resource_id" "$slot_id" "$(cat /proc/sys/kernel/random/uuid)" >> "$csv"
done
chmod 600 "$csv"

if [[ "$SLOT_TYPE" == HOT ]]; then
  "$(dirname "$0")/preheat-hot-reservation-redis.sh" "$slot_id"
fi

echo "MODE=$([[ "$SLOT_TYPE" == HOT ]] && echo optimized || echo sql)"
echo "RUN_ID=$RUN_ID"
echo "RESOURCE_ID=$resource_id"
echo "SLOT_ID=$slot_id"
echo "QUOTA=$QUOTA"
echo "TOKEN_CSV=$csv"
