#!/usr/bin/env bash
set -euo pipefail

# Run only on the isolated ECS load-test host. It refuses any database other
# than lab_booking_loadtest and never prints the generated JWT values.
COUNT="${COUNT:-500}"
QUOTA="${QUOTA:-20}"
BASE_URL="${BASE_URL:-http://172.18.225.136:8082}"
RUN_ID="${RUN_ID:-$(date +%Y%m%d%H%M%S)}"
PASSWORD="${LOADTEST_USER_PASSWORD:-LoadTest!2026}"
ENV_FILE="${ENV_FILE:-/etc/lab-booking/loadtest.env}"
OUT_DIR="${OUT_DIR:-/opt/lab-booking/loadtest}"

if [[ ! "$COUNT" =~ ^[1-9][0-9]*$ ]] || [[ ! "$QUOTA" =~ ^[1-9][0-9]*$ ]] || (( QUOTA > COUNT )); then
  echo "COUNT must be positive and QUOTA must be between 1 and COUNT." >&2
  exit 2
fi
if [[ ! -r "$ENV_FILE" ]]; then
  echo "Load-test environment file is not readable: $ENV_FILE" >&2
  exit 2
fi

set -a
# shellcheck disable=SC1090
source "$ENV_FILE"
set +a
if [[ "${LOADTEST_MQ_ENABLED:-false}" != "false" ]]; then
  echo "Safety stop: LOADTEST_MQ_ENABLED must be false for the Redis core-path benchmark." >&2
  exit 2
fi

# The isolated ECS keeps MySQL private on localhost. Deliberately hard-code the
# database name instead of parsing a JDBC URL so this script cannot be pointed
# at the regular lab_booking database by a malformed EnvironmentFile.
db_name="lab_booking_loadtest"
db_host="${LOADTEST_MYSQL_HOST:-127.0.0.1}"
db_port="${LOADTEST_MYSQL_PORT:-3306}"

mysql_exec() {
  MYSQL_PWD="$LOADTEST_DB_PASSWORD" mysql --protocol=TCP --host="$db_host" --port="$db_port" \
    --user="$LOADTEST_DB_USERNAME" --database="$db_name" --batch --skip-column-names "$@"
}

resource_code="PTS-HOT-$RUN_ID"
resource_id="$(mysql_exec -e "
  INSERT INTO resource (resource_code, resource_name, resource_type, status, location, description, created_at, updated_at)
  VALUES ('$resource_code', 'PTS 热点预约压测资源', 'TEST_FIELD', 'AVAILABLE', 'loadtest', 'isolated PTS benchmark only', NOW(), NOW());
  SELECT LAST_INSERT_ID();")"
slot_id="$(mysql_exec -e "
  INSERT INTO resource_slot (resource_id, start_datetime, end_datetime, slot_type, open_time, total_quota, remain_quota, status, created_at, updated_at)
  VALUES ($resource_id, DATE_ADD(NOW(), INTERVAL 2 DAY), DATE_ADD(DATE_ADD(NOW(), INTERVAL 2 DAY), INTERVAL 2 HOUR), 'HOT', DATE_SUB(NOW(), INTERVAL 1 MINUTE), $QUOTA, $QUOTA, 'OPEN', NOW(), NOW());
  SELECT LAST_INSERT_ID();")"

mkdir -p "$OUT_DIR"
token_csv="$OUT_DIR/hot-reservation-$RUN_ID-tokens.csv"
tmp_response="$(mktemp)"
trap 'rm -f "$tmp_response"' EXIT
printf 'accessToken,resourceId,slotId\n' > "$token_csv"

for ((i = 1; i <= COUNT; i++)); do
  username="pts_${RUN_ID}_$(printf '%04d' "$i")"
  register_body="{\"username\":\"$username\",\"password\":\"$PASSWORD\",\"confirmPassword\":\"$PASSWORD\",\"nickname\":\"PTS-$i\"}"
  register_status="$(curl -sS --connect-timeout 5 --max-time 20 -o "$tmp_response" -w '%{http_code}' -H 'Content-Type: application/json' -d "$register_body" "$BASE_URL/auth/register")"
  if [[ "$register_status" != "200" ]] || ! python3 - "$tmp_response" <<'PY'
import json, sys
try:
    raise SystemExit(0 if json.load(open(sys.argv[1], encoding='utf-8')).get('code') == 200 else 1)
except Exception:
    raise SystemExit(1)
PY
  then
    echo "Failed to register test user #$i (HTTP $register_status)." >&2
    exit 1
  fi

  login_body="{\"username\":\"$username\",\"password\":\"$PASSWORD\"}"
  login_status="$(curl -sS --connect-timeout 5 --max-time 20 -o "$tmp_response" -w '%{http_code}' -H 'Content-Type: application/json' -d "$login_body" "$BASE_URL/auth/login")"
  token="$(python3 - "$tmp_response" <<'PY'
import json, sys
try:
    data = json.load(open(sys.argv[1], encoding='utf-8'))
    print(data['data']['token'] if data.get('code') == 200 else '', end='')
except Exception:
    pass
PY
)"
  if [[ "$login_status" != "200" ]] || [[ -z "$token" ]]; then
    echo "Failed to pre-login test user #$i (HTTP $login_status)." >&2
    exit 1
  fi
  printf '%s,%s,%s\n' "$token" "$resource_id" "$slot_id" >> "$token_csv"
done

chmod 600 "$token_csv"
systemctl restart lab-booking-loadtest.service
ready=false
for _ in $(seq 1 30); do
  if systemctl is-active --quiet lab-booking-loadtest.service \
    && curl --silent --show-error --connect-timeout 1 --max-time 2 --output /dev/null "$BASE_URL/"; then
    ready=true
    break
  fi
  sleep 1
done
if [[ "$ready" != "true" ]]; then
  echo "Load-test service did not become reachable on $BASE_URL within 30 seconds." >&2
  exit 1
fi

# The HOT slot is created through SQL so it may not be seen by a startup-time
# preheater that ran before the datasource finished initializing. Populate the
# same stock/user keys explicitly before the first PTS request.
"$(dirname "$0")/preheat-hot-reservation-redis.sh" "$slot_id"

echo "RUN_ID=$RUN_ID"
echo "RESOURCE_ID=$resource_id"
echo "SLOT_ID=$slot_id"
echo "QUOTA=$QUOTA"
echo "TOKEN_CSV=$token_csv"
echo "The token CSV expires with JWT policy; upload it to PTS and start the run within 24 hours."
