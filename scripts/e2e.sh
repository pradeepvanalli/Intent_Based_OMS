#!/usr/bin/env bash
# Playwright end-to-end suite against a freshly seeded Intent OMS. Local only (CI does not run it).
#
#   scripts/e2e.sh                  build UI + jar, fresh database, boot with the e2e profile, run every spec, stop
#   scripts/e2e.sh --skip-build     reuse the existing jar and static UI
#   scripts/e2e.sh --keep           leave the app running afterwards (http://localhost:$OMS_PORT)
#   scripts/e2e.sh --reuse          run against an app already listening on $OMS_PORT (no build, no fresh database)
#   scripts/e2e.sh -- --grep Tasks  anything after -- goes to `playwright test`
#
# Env: OMS_PORT (default 8095), E2E_DB (default oms_e2e), JAVA_HOME (default Homebrew openjdk@21).
# The database is dropped and re-created on every run, so this never touches your dev database `oms`.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PORT="${OMS_PORT:-8095}"
DB="${E2E_DB:-oms_e2e}"
JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home}"
JAVA="$JAVA_HOME/bin/java"
RESULTS="$ROOT/ui/e2e/.results"
BUILD=1 KEEP=0 REUSE=0 PW_ARGS=()
while [ $# -gt 0 ]; do
  case "$1" in
    --skip-build) BUILD=0 ;;
    --keep) KEEP=1 ;;
    --reuse) REUSE=1; BUILD=0 ;;
    --) shift; PW_ARGS=("$@"); break ;;
    *) PW_ARGS+=("$1") ;;
  esac
  shift
done

say() { printf '\033[36m▸ %s\033[0m\n' "$*"; }
die() { printf '\033[31m✗ %s\033[0m\n' "$*" >&2; exit 1; }
mkdir -p "$RESULTS"
APP_PID=""
cleanup() { if [ -n "$APP_PID" ] && [ "$KEEP" = 0 ]; then say "stopping the app (pid $APP_PID)"; kill "$APP_PID" 2>/dev/null || true; wait "$APP_PID" 2>/dev/null || true; fi; }
trap cleanup EXIT

if [ "$REUSE" = 0 ]; then
  lsof -iTCP:"$PORT" -sTCP:LISTEN >/dev/null 2>&1 && die "port $PORT is already in use; stop that process, pick another OMS_PORT, or use --reuse"
  [ -x "$JAVA" ] || die "JDK 21 not found at $JAVA_HOME (set JAVA_HOME)"
  say "starting Postgres (docker compose)"
  (cd "$ROOT" && docker compose up -d postgres >/dev/null)
  for _ in $(seq 1 60); do (cd "$ROOT" && docker compose exec -T postgres pg_isready -U oms >/dev/null 2>&1) && break; sleep 1; done

  if [ "$BUILD" = 1 ]; then
    say "building the UI into the backend static folder"
    "$ROOT/scripts/build-ui.sh" >/dev/null
    say "building the service jar"
    (cd "$ROOT/backend" && JAVA_HOME="$JAVA_HOME" mvn -q -ntp -DskipTests -pl oms-app -am package)
  fi
  JAR="$ROOT/backend/oms-app/target/intent-oms.jar"
  [ -f "$JAR" ] || die "no jar at $JAR (run without --skip-build)"
  cp "$JAR" "$RESULTS/intent-oms-e2e.jar"

  say "fresh database $DB"
  (cd "$ROOT" && docker compose exec -T postgres psql -q -U oms -d postgres -c "drop database if exists $DB with (force)" -c "create database $DB" >/dev/null)

  say "booting the service on :$PORT with the e2e profile (log: ui/e2e/.results/app.log)"
  OMS_DB_URL="jdbc:postgresql://localhost:5440/$DB" OMS_PORT="$PORT" OMS_CONFIG_DIR="$ROOT/config" \
    "$JAVA" -Xmx1536m -jar "$RESULTS/intent-oms-e2e.jar" --spring.profiles.active=e2e > "$RESULTS/app.log" 2>&1 &
  APP_PID=$!
fi

BASE="http://localhost:$PORT"
say "waiting for readiness"
for i in $(seq 1 180); do
  curl -sf "$BASE/actuator/health/readiness" >/dev/null 2>&1 && break
  [ -n "$APP_PID" ] && ! kill -0 "$APP_PID" 2>/dev/null && { tail -40 "$RESULTS/app.log"; die "the service exited during startup"; }
  [ "$i" = 180 ] && die "the service did not become ready in 3 minutes (see $RESULTS/app.log)"
  sleep 1
done

say "waiting for the demo seed"
TOKEN=""
for i in $(seq 1 180); do
  TOKEN=$(curl -sf -X POST "$BASE/api/v1/auth/login" -H 'content-type: application/json' -d '{"tenantId":"demo","userId":"admin","password":"admin"}' 2>/dev/null | sed -n 's/.*"token":"\([^"]*\)".*/\1/p' || true)
  if [ -n "$TOKEN" ]; then
    ORDERS=$(curl -sf -H "Authorization: Bearer $TOKEN" "$BASE/api/v1/orders?limit=1" 2>/dev/null | sed -n 's/.*"total":\([0-9]*\).*/\1/p' || true)
    SEEDED=$(curl -sf -H "Authorization: Bearer $TOKEN" "$BASE/api/v1/learning/seed-status" 2>/dev/null | sed -n 's/.*"complete":\(true\|false\).*/\1/p' || true)
    if [ "${ORDERS:-0}" -gt 0 ] && { [ "$SEEDED" = "true" ] || [ -z "$SEEDED" ]; }; then break; fi
  fi
  [ "$i" = 180 ] && die "demo data did not appear in 3 minutes (see $RESULTS/app.log)"
  sleep 1
done

say "running Playwright"
set +e
(cd "$ROOT/ui" && E2E_BASE_URL="$BASE" npx playwright test -c e2e/playwright.config.ts ${PW_ARGS[@]+"${PW_ARGS[@]}"})
CODE=$?
set -e
say "HTML report: ui/e2e/report/index.html (npx playwright show-report ui/e2e/report)"
[ "$KEEP" = 1 ] && [ -n "$APP_PID" ] && say "app left running on $BASE (pid $APP_PID)"
exit $CODE
