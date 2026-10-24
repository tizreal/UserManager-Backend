#!/usr/bin/env bash
# Migration rehearsal for the security release. See docs/deployment.md, section 4.
#
# Runs the release build against a DISPOSABLE database (a Neon child branch of the
# pre-deployment backup, or a local fixture), then verifies the Flyway baseline and
# V2-V4, data preservation, authentication, demo restrictions, maintenance mode,
# restart idempotency, secret rotation and that no email can be delivered.
#
# Usage:  scripts/rehearsal/rehearse.sh <env-file>
#
# The env file lives OUTSIDE the repository and contains only rehearsal credentials:
#   REHEARSAL_PGHOST=ep-xxxx.<region>.aws.neon.tech   # the rehearsal branch's DIRECT host
#   REHEARSAL_PGPORT=5432
#   REHEARSAL_PGDATABASE=neondb
#   REHEARSAL_PGUSER=neondb_owner
#   REHEARSAL_PGPASSWORD=...           # reset on the rehearsal branch only
#   REHEARSAL_SSLMODE=require          # "disable" only for a local fixture
#   PROD_PGHOST=ep-yyyy.<region>.aws.neon.tech   # production host: the script refuses to use it
#   EXPECTED_USERS=5
#
# Nothing secret is printed. Generated secrets exist only for the duration of the run.
# The target database is modified (that is the point): never point this at production.

set -u
ENV_FILE="${1:?usage: rehearse.sh <env-file>}"
[ -f "$ENV_FILE" ] || { echo "env file not found"; exit 2; }
# shellcheck disable=SC1090
set -a; . "$ENV_FILE"; set +a

: "${REHEARSAL_PGHOST:?}" "${REHEARSAL_PGDATABASE:?}" "${REHEARSAL_PGUSER:?}" "${REHEARSAL_PGPASSWORD:?}"
: "${PROD_PGHOST:?PROD_PGHOST is required so the script can refuse to run against production}"
REHEARSAL_PGPORT="${REHEARSAL_PGPORT:-5432}"
REHEARSAL_SSLMODE="${REHEARSAL_SSLMODE:-require}"
EXPECTED_USERS="${EXPECTED_USERS:-5}"
APP_PORT="${APP_PORT:-18080}"

REPO_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
RUN_DIR="$(mktemp -d "${TMPDIR:-/tmp}/um-rehearsal.XXXXXX")"
BASE="http://localhost:$APP_PORT/api"
SEEDED="'demo','ada','alan','grace'"   # accounts DemoDataSeeder upserts on every boot

PASS=0; FAIL=0
pass() { PASS=$((PASS + 1)); echo "  PASS  $1"; }
fail() { FAIL=$((FAIL + 1)); echo "  FAIL  $1"; }
check() { if [ "$2" = "$3" ]; then pass "$1"; else fail "$1 (expected '$3', got '$2')"; fi; }
section() { echo; echo "== $1"; }
abort() { echo "ABORT: $1"; stop_app; exit 2; }

export PGHOST="$REHEARSAL_PGHOST" PGPORT="$REHEARSAL_PGPORT" PGDATABASE="$REHEARSAL_PGDATABASE" \
       PGUSER="$REHEARSAL_PGUSER" PGPASSWORD="$REHEARSAL_PGPASSWORD" PGSSLMODE="$REHEARSAL_SSLMODE"
q() { psql -X -q -t -A -v ON_ERROR_STOP=1 -c "$1" 2>&1; }

lower() { echo "$1" | tr '[:upper:]' '[:lower:]' | sed 's/-pooler\././'; }

# ---------------------------------------------------------------- guards
section "Guards"
[ "$(lower "$REHEARSAL_PGHOST")" != "$(lower "$PROD_PGHOST")" ] \
  || { echo "ABORT: REHEARSAL_PGHOST is the production host"; exit 2; }
pass "target host is not the production host"
case "$REHEARSAL_PGHOST" in *-pooler*) echo "ABORT: use the branch's direct endpoint, not -pooler"; exit 2;; esac
pass "target is a direct (non-pooled) endpoint"
[ "$(q "SELECT 1")" = "1" ] || { echo "ABORT: cannot connect to the rehearsal database"; exit 2; }
pass "connected to the rehearsal database"

# ---------------------------------------------------------------- pre-state
section "Pre-state (must look like production before deployment)"
[ "$(q "SELECT to_regclass('public.flyway_schema_history') IS NULL")" = "t" ] \
  || { echo "ABORT: flyway_schema_history already exists: this branch was already migrated. Create a fresh branch."; exit 2; }
pass "no flyway_schema_history (pre-migration state)"
PRE_USERS="$(q "SELECT count(*) FROM users")"
check "user count before migration" "$PRE_USERS" "$EXPECTED_USERS"
PRE_POSTS="$(q "SELECT count(*) FROM posts")"; PRE_COMMENTS="$(q "SELECT count(*) FROM comments")"
echo "        posts=$PRE_POSTS comments=$PRE_COMMENTS"

# Snapshot of every original column, per user. Seeded rows are compared on identity
# columns only, because the seeder resets their password and profile on every boot.
SNAP_ALL="SELECT user_id || ':' || md5(row(user_id, first_name, last_name, user_name, password, phone_number,
  email, email_verified, country_code, created_at, updated_at)::text)
  FROM users WHERE user_name NOT IN ($SEEDED) ORDER BY user_id"
SNAP_SEEDED="SELECT user_id || ':' || user_name || ':' || created_at FROM users WHERE user_name IN ($SEEDED) ORDER BY user_id"
q "$SNAP_ALL" > "$RUN_DIR/pre_other.txt"; q "$SNAP_SEEDED" > "$RUN_DIR/pre_seeded.txt"
q "SELECT user_id FROM users ORDER BY user_id" > "$RUN_DIR/pre_ids.txt"
# From here on, compare only the ORIGINAL rows (the rehearsal adds its own test account).
SNAP_ALL="${SNAP_ALL/WHERE user_name NOT IN/WHERE user_id IN ($(paste -sd, "$RUN_DIR/pre_ids.txt")) AND user_name NOT IN}"
echo "        $(wc -l < "$RUN_DIR/pre_other.txt") non-seeded user(s), $(wc -l < "$RUN_DIR/pre_seeded.txt") seeded account(s)"

# ---------------------------------------------------------------- build
section "Build (current checkout: $(git -C "$REPO_ROOT" rev-parse --short HEAD) on $(git -C "$REPO_ROOT" branch --show-current))"
( cd "$REPO_ROOT/UserManager" && mvn -B -q -DskipTests clean package ) > "$RUN_DIR/build.log" 2>&1 \
  || { echo "ABORT: build failed (see $RUN_DIR/build.log)"; exit 2; }
JAR="$(ls "$REPO_ROOT"/UserManager/target/*.jar | grep -v plain | head -1)"
pass "built $(basename "$JAR")"

# ---------------------------------------------------------------- app control
MKEY="$(openssl rand -hex 32)"
DEMO_PW="$(openssl rand -hex 12)"
APP_PID=""
start_app() {  # $1 = log file, $2 = JWT secret
  env -u SPRING_FLYWAY_URL -u SPRING_FLYWAY_USER -u SPRING_FLYWAY_PASSWORD -u DDL_AUTO \
    PORT="$APP_PORT" \
    DATABASE_URL="jdbc:postgresql://$REHEARSAL_PGHOST:$REHEARSAL_PGPORT/$REHEARSAL_PGDATABASE?sslmode=$REHEARSAL_SSLMODE" \
    DATABASE_USERNAME="$REHEARSAL_PGUSER" DATABASE_PASSWORD="$REHEARSAL_PGPASSWORD" \
    JWT_SECRET="$2" \
    MAINTENANCE_MODE=true MAINTENANCE_BYPASS_KEY="$MKEY" \
    APP_SEED_DEMO=true DEMO_PASSWORD="$DEMO_PW" \
    CLIENT_IP_MODE=REMOTE_ADDR CLIENT_URL=http://localhost:5173 \
    RESEND_API_KEY=re_rehearsal_invalid_key RESEND_FROM_EMAIL=rehearsal@example.invalid \
    MAIL_HOST=127.0.0.1 MAIL_PORT=9 MAIL_USERNAME=rehearsal MAIL_PASSWORD=rehearsal \
    java -Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=9 -Dhttp.proxyHost=127.0.0.1 -Dhttp.proxyPort=9 \
         -jar "$JAR" > "$1" 2>&1 &
  APP_PID=$!
  for _ in $(seq 1 180); do
    if curl -s -o /dev/null -w '%{http_code}' "$BASE/health" 2>/dev/null | grep -q 200; then return 0; fi
    kill -0 "$APP_PID" 2>/dev/null || return 1
    sleep 1
  done
  return 1
}
stop_app() {
  [ -n "$APP_PID" ] || return 0
  kill "$APP_PID" 2>/dev/null
  for _ in $(seq 1 30); do kill -0 "$APP_PID" 2>/dev/null || break; sleep 1; done
  kill -9 "$APP_PID" 2>/dev/null; APP_PID=""
  # Windows: make sure the port is free for the next start.
  if command -v netstat >/dev/null && command -v taskkill >/dev/null; then
    for p in $(netstat -ano 2>/dev/null | grep ":$APP_PORT " | grep LISTENING | awk '{print $NF}' | sort -u); do
      taskkill //F //PID "$p" >/dev/null 2>&1
    done
  fi
}
trap stop_app EXIT

H="X-Maintenance-Key: $MKEY"
code() { curl -s -o /dev/null -w '%{http_code}' "$@"; }
login() {  # $1 user, $2 password -> prints token
  curl -s -D - -o /dev/null -H "$H" -H 'Content-Type: application/json' \
    -d "{\"username\":\"$1\",\"password\":\"$2\"}" "$BASE/user/login" \
    | tr -d '\r' | awk -F': ' 'tolower($1)=="authorization"{print $2}' | sed 's/^Bearer //'
}

# ---------------------------------------------------------------- first boot: migration
section "First boot (baseline + V2-V4)"
JWT1="$(openssl rand -base64 64 | tr -d '\n')"
start_app "$RUN_DIR/app1.log" "$JWT1" || abort "application did not start (see $RUN_DIR/app1.log)"
pass "application started with MAINTENANCE_MODE=true"
grep -q 'Successfully baselined schema with version: 1' "$RUN_DIR/app1.log" && pass "log: baselined at version 1" || fail "log: baseline line missing"
grep -q 'Successfully applied 3 migrations' "$RUN_DIR/app1.log" && pass "log: applied 3 migrations" || fail "log: 'applied 3 migrations' missing"
grep -q 'now at version v4' "$RUN_DIR/app1.log" && pass "log: now at version v4" || fail "log: not at v4"
grep -qiE 'ERROR|Exception' "$RUN_DIR/app1.log" && echo "        note: ERROR/Exception lines in app1.log (checked below for email)" || true

section "Flyway history"
HIST="$(q "SELECT string_agg(installed_rank || '|' || coalesce(version,'') || '|' || type || '|' || success, ' ' ORDER BY installed_rank) FROM flyway_schema_history")"
check "history is exactly BASELINE 1, SQL 2, SQL 3, SQL 4" "$HIST" "1|1|BASELINE|true 2|2|SQL|true 3|3|SQL|true 4|4|SQL|true"
HIST_STAMP="$(q "SELECT string_agg(installed_rank || '@' || installed_on, ' ' ORDER BY installed_rank) FROM flyway_schema_history")"

section "Schema after migration"
check "new user columns" "$(q "SELECT string_agg(column_name, ',' ORDER BY column_name) FROM information_schema.columns WHERE table_schema='public' AND table_name='users' AND column_name IN ('role','token_version','status','deleted_at','pending_email')")" "deleted_at,pending_email,role,status,token_version"
check "constraints ck_users_role, ck_users_status" "$(q "SELECT count(*) FROM pg_constraint WHERE conname IN ('ck_users_role','ck_users_status')")" "2"
check "case-insensitive unique indexes" "$(q "SELECT count(*) FROM pg_indexes WHERE indexname IN ('ux_users_user_name_lower','ux_users_email_lower')")" "2"
check "password_reset_tokens exists and is empty" "$(q "SELECT count(*) FROM password_reset_tokens")" "0"

section "Existing data preserved"
check "every original user_id still present" "$(q "SELECT count(*) FROM users WHERE user_id IN ($(paste -sd, "$RUN_DIR/pre_ids.txt"))")" "$PRE_USERS"
q "$SNAP_ALL" > "$RUN_DIR/post_other.txt"; q "$SNAP_SEEDED" > "$RUN_DIR/post_seeded.txt"
cmp -s "$RUN_DIR/pre_other.txt" "$RUN_DIR/post_other.txt" && pass "non-seeded users: every original column unchanged" || fail "non-seeded users changed"
cmp -s "$RUN_DIR/pre_seeded.txt" "$RUN_DIR/post_seeded.txt" && pass "seeded accounts: same ids, usernames, created_at" || fail "seeded accounts' identity changed"
check "original users defaulted to USER / 0 / ACTIVE" "$(q "SELECT count(*) FROM users WHERE user_id IN ($(paste -sd, "$RUN_DIR/pre_ids.txt")) AND role='USER' AND token_version=0 AND status='ACTIVE' AND deleted_at IS NULL AND pending_email IS NULL")" "$PRE_USERS"
check "posts unchanged" "$(q "SELECT count(*) FROM posts")" "$PRE_POSTS"
check "comments unchanged" "$(q "SELECT count(*) FROM comments")" "$PRE_COMMENTS"

section "Maintenance mode"
check "GET /health without key" "$(code "$BASE/health")" "200"
check "POST /user/login without key" "$(code -H 'Content-Type: application/json' -d '{"username":"demo","password":"x"}' "$BASE/user/login")" "503"
check "GET /posts/ without key" "$(code "$BASE/posts/")" "503"
check "POST /user/signup without key" "$(code -H 'Content-Type: application/json' -d '{}' "$BASE/user/signup")" "503"
check "wrong key" "$(code -H 'X-Maintenance-Key: wrong' "$BASE/posts/")" "503"
check "empty key" "$(code -H 'X-Maintenance-Key;' "$BASE/posts/")" "503"
check "key as query parameter" "$(code "$BASE/posts/?X-Maintenance-Key=$MKEY")" "503"
check "key as cookie" "$(code -b "X-Maintenance-Key=$MKEY" "$BASE/posts/")" "503"

section "Authentication"
DEMO_TOKEN="$(login demo "$DEMO_PW")"
[ -n "$DEMO_TOKEN" ] && pass "demo login returns a token" || fail "demo login failed"
BODY="$(curl -s -H "$H" -H 'Content-Type: application/json' -d "{\"username\":\"demo\",\"password\":\"$DEMO_PW\"}" "$BASE/user/login")"
echo "$BODY" | grep -q '"role":"USER"' && pass "demo role is USER" || fail "demo role not USER"
echo "$BODY" | grep -qi '"password"' && fail "login response contains a password field" || pass "login response has no password field"
# Wrong credentials and unverified accounts answer 400 (existing frontend contract; see AuthenticationIT).
check "wrong password rejected" "$(code -H "$H" -H 'Content-Type: application/json' -d '{"username":"demo","password":"wrong-password"}' "$BASE/user/login")" "400"
check "no token on /user/get" "$(code -H "$H" "$BASE/user/get")" "401"
check "tampered token" "$(code -H "$H" -H "Authorization: Bearer ${DEMO_TOKEN}x" "$BASE/user/get")" "401"
check "valid token on /user/get" "$(code -H "$H" -H "Authorization: Bearer $DEMO_TOKEN" "$BASE/user/get")" "200"
POSTS_BODY="$(curl -s -H "$H" -H "Authorization: Bearer $DEMO_TOKEN" "$BASE/posts/")"
echo "$POSTS_BODY" | grep -qiE '"password"|"email"' && fail "feed exposes email/password" || pass "feed exposes no email/password"

section "Demo restrictions"
DA="Authorization: Bearer $DEMO_TOKEN"
check "demo: list all users" "$(code -H "$H" -H "$DA" "$BASE/user/")" "403"
check "demo: update profile" "$(code -H "$H" -H "$DA" -H 'Content-Type: application/json' -d '{"firstName":"x"}' "$BASE/user/update")" "403"
check "demo: change password" "$(code -H "$H" -H "$DA" -H 'Content-Type: application/json' -d "{\"password\":\"newpassword1\",\"currentPassword\":\"$DEMO_PW\"}" "$BASE/user/update")" "403"
DEL="$(code -H "$H" -H "$DA" -H 'Content-Type: application/json' -d "{\"currentPassword\":\"$DEMO_PW\"}" "$BASE/user/delete-account")"
case "$DEL" in 403|409) pass "demo: delete own account rejected ($DEL)";; *) fail "demo: delete own account (got $DEL)";; esac
OTHER_ID="$(q "SELECT user_id FROM users WHERE user_name <> 'demo' ORDER BY user_id LIMIT 1")"
check "demo: delete another user" "$(code -X DELETE -H "$H" -H "$DA" "$BASE/user/$OTHER_ID")" "403"
check "demo: deactivate another user" "$(code -X POST -H "$H" -H "$DA" "$BASE/user/$OTHER_ID/deactivate")" "403"
check "demo: admin cleanup endpoint" "$(code -X POST -H "$H" -H "$DA" "$BASE/admin/demo-content/cleanup")" "403"
check "demo: password reset request answers 200" "$(code -H "$H" "$BASE/user/reset/demo@usermanager.dev")" "200"
check "demo: no reset token was created" "$(q "SELECT count(*) FROM password_reset_tokens")" "0"
check "demo still works after attempts" "$(code -H "$H" -H "$DA" "$BASE/user/get")" "200"

section "Signup cannot assign privileges (mass assignment)"
SUFFIX="$(openssl rand -hex 4)"; RU="rehearsal-$SUFFIX"; RPW="$(openssl rand -hex 12)"
check "signup with userId=1, role=ADMIN, emailVerified=true" "$(code -H "$H" -H 'Content-Type: application/json' \
  -d "{\"username\":\"$RU\",\"email\":\"$RU@example.invalid\",\"password\":\"$RPW\",\"userId\":1,\"role\":\"ADMIN\",\"emailVerified\":true}" "$BASE/user/signup")" "200"
check "new account is USER, unverified, not user 1" "$(q "SELECT role || ',' || coalesce(email_verified::text,'null') || ',' || (user_id <> 1) FROM users WHERE user_name = '$RU'")" "USER,false,true"
check "unverified account cannot log in" "$(code -H "$H" -H 'Content-Type: application/json' -d "{\"username\":\"$RU\",\"password\":\"$RPW\"}" "$BASE/user/login")" "400"
q "$SNAP_ALL" > "$RUN_DIR/post2_other.txt"
cmp -s "$RUN_DIR/pre_other.txt" "$RUN_DIR/post2_other.txt" && pass "existing users untouched by the signup" || fail "existing users changed by the signup"

section "Admin promotion (the runbook SQL, on the rehearsal account)"
q "UPDATE users SET email_verified = true WHERE user_name = '$RU'" >/dev/null   # stands in for the emailed link
RID="$(q "SELECT user_id FROM users WHERE user_name = '$RU'")"
check "promotion SQL" "$(psql -X -q -t -A -c "UPDATE users SET role = 'ADMIN' WHERE user_id = $RID AND user_name = '$RU' AND role = 'USER' AND status = 'ACTIVE' AND email_verified = true" -c "SELECT 'rows=' || count(*) FROM users WHERE role = 'ADMIN'" 2>&1 | tail -1)" "rows=1"
ADMIN_TOKEN="$(login "$RU" "$RPW")"
AA="Authorization: Bearer $ADMIN_TOKEN"
check "admin: list all users" "$(code -H "$H" -H "$AA" "$BASE/user/")" "200"
check "admin: demo cleanup" "$(code -X POST -H "$H" -H "$AA" "$BASE/admin/demo-content/cleanup")" "200"

section "No email delivered"
sleep 3   # emails are sent asynchronously
if grep -q 'Error sending verify_email email' "$RUN_DIR/app1.log"; then
  pass "verification email could not leave the machine (blocked at the proxy)"
elif grep -qE 'Resend API error \(4[0-9][0-9]\) sending verify_email' "$RUN_DIR/app1.log"; then
  pass "verification email rejected by Resend (invalid key): not delivered"
else
  fail "no evidence that the verification email was blocked"
fi
grep -q 'Sent verify_email\|Sent reset_password' "$RUN_DIR/app1.log" && fail "log reports a sent email" || pass "log reports no sent email"
stop_app

# ---------------------------------------------------------------- second boot: idempotency + rotation
section "Restart with a rotated JWT secret"
JWT2="$(openssl rand -base64 64 | tr -d '\n')"
start_app "$RUN_DIR/app2.log" "$JWT2" || abort "application did not restart (see $RUN_DIR/app2.log)"
pass "application restarted"
grep -q 'No migration necessary' "$RUN_DIR/app2.log" && pass "log: no migration necessary" || fail "log: expected 'No migration necessary'"
grep -q 'Successfully applied' "$RUN_DIR/app2.log" && fail "log: migrations were applied again" || pass "log: nothing re-applied"
check "history unchanged (same rows, same installed_on)" "$(q "SELECT string_agg(installed_rank || '@' || installed_on, ' ' ORDER BY installed_rank) FROM flyway_schema_history")" "$HIST_STAMP"
check "token signed with the old secret" "$(code -H "$H" -H "$AA" "$BASE/user/")" "401"
ADMIN_TOKEN2="$(login "$RU" "$RPW")"
check "fresh admin login after rotation" "$(code -H "$H" -H "Authorization: Bearer $ADMIN_TOKEN2" "$BASE/user/")" "200"
q "$SNAP_ALL" > "$RUN_DIR/post3_other.txt"
cmp -s "$RUN_DIR/pre_other.txt" "$RUN_DIR/post3_other.txt" && pass "non-seeded users still unchanged after restart" || fail "non-seeded users changed after restart"
stop_app

section "Logs contain no secrets"
LEAK=0
for s in "$MKEY" "$DEMO_PW" "$RPW" "$REHEARSAL_PGPASSWORD" "$JWT1" "$JWT2" "$DEMO_TOKEN" "$ADMIN_TOKEN"; do
  grep -qF -- "$s" "$RUN_DIR/app1.log" "$RUN_DIR/app2.log" && LEAK=1
done
[ $LEAK = 0 ] && pass "no key, password, secret or token in the application logs" || fail "a secret appears in the application logs"

echo
echo "RESULT: $PASS passed, $FAIL failed.   Logs: $RUN_DIR"
echo "The rehearsal database now contains a rehearsal admin ($RU); delete the branch after review."
[ $FAIL = 0 ]
