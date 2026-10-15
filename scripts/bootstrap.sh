#!/usr/bin/env bash
# One-command local setup for Intent OMS.
#
#   scripts/bootstrap.sh            check tools → .env → Postgres/Redis → build UI + backend → start → wait → print next steps
#   scripts/bootstrap.sh --stop     stop the service started by this script
#
# Options:
#   --no-ui          skip the UI build (API still works; the operator console is not served)
#   --skip-build     reuse an existing backend/oms-app/target/intent-oms.jar and static UI
#   --foreground     run the service in the foreground instead of detaching (logs to the terminal)
#   --no-seed-wait   do not wait for the demo tenant to finish seeding
#   --port <n>       HTTP port (default 8080 or OMS_PORT from .env)
#
# Requires: docker (with compose v2), JDK 21, Maven 3.9+, curl, python3; Node 20+ for the UI (optional).
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
STATE_DIR="$ROOT/.oms"
PID_FILE="$STATE_DIR/oms.pid"
LOG_FILE="$STATE_DIR/oms.log"
JAR="$ROOT/backend/oms-app/target/intent-oms.jar"
STATIC_INDEX="$ROOT/backend/oms-app/src/main/resources/static/index.html"

NO_UI=0; SKIP_BUILD=0; FOREGROUND=0; SEED_WAIT=1; PORT_OVERRIDE=""; STOP=0
while [ $# -gt 0 ]; do
  case "$1" in
    --no-ui) NO_UI=1 ;;
    --skip-build) SKIP_BUILD=1 ;;
    --foreground) FOREGROUND=1 ;;
    --no-seed-wait) SEED_WAIT=0 ;;
    --port) shift; PORT_OVERRIDE="$1" ;;
    --stop) STOP=1 ;;
    -h|--help) sed -n '2,16p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "unknown option: $1" >&2; exit 2 ;;
  esac
  shift
done

bold() { printf '\033[1m%s\033[0m\n' "$*"; }
step() { printf '\n\033[1;36m▶ %s\033[0m\n' "$*"; }
ok()   { printf '  \033[32m✓\033[0m %s\n' "$*"; }
warn() { printf '  \033[33m!\033[0m %s\n' "$*"; }
fail() { printf '  \033[31m✗\033[0m %s\n' "$*" >&2; exit 1; }

# ----------------------------------------------------------------------------------------------------------------------
if [ "$STOP" = 1 ]; then
  if [ -f "$PID_FILE" ] && kill -0 "$(cat "$PID_FILE")" 2>/dev/null; then
    kill "$(cat "$PID_FILE")" && rm -f "$PID_FILE" && ok "service stopped"
  else
    warn "no running service recorded in $PID_FILE"
  fi
  echo "Postgres and Redis keep running; stop them with: docker compose stop postgres redis"
  exit 0
fi

cd "$ROOT"
mkdir -p "$STATE_DIR"

# ----------------------------------------------------------------------------------------------------------------------
step "1/7 Checking prerequisites"

command -v docker >/dev/null 2>&1 || fail "docker is not installed. Install Docker Desktop (macOS/Windows) or Docker Engine (Linux): https://docs.docker.com/get-docker/"
docker info >/dev/null 2>&1 || fail "docker is installed but the daemon is not running. Start Docker and re-run."
docker compose version >/dev/null 2>&1 || fail "docker compose v2 is required (the 'docker compose' subcommand). Upgrade Docker or install the compose plugin."
ok "docker $(docker version --format '{{.Server.Version}}' 2>/dev/null || echo ok), compose $(docker compose version --short 2>/dev/null || echo ok)"

# Java 21: honour JAVA_HOME, else try to locate 21 on macOS, else whatever `java` is.
java_major() { "$1" -version 2>&1 | awk -F'"' '/version/ {split($2,v,"."); print (v[1]=="1")?v[2]:v[1]; exit}'; }
if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ]; then :
elif [ -x /usr/libexec/java_home ] && /usr/libexec/java_home -v 21 >/dev/null 2>&1; then
  export JAVA_HOME="$(/usr/libexec/java_home -v 21)"
elif [ -d /opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home ]; then
  export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
fi
JAVA_BIN="${JAVA_HOME:+$JAVA_HOME/bin/}java"
command -v "$JAVA_BIN" >/dev/null 2>&1 || fail "java not found. Install JDK 21 (e.g. 'brew install openjdk@21' or https://adoptium.net) and set JAVA_HOME."
JV="$(java_major "$JAVA_BIN")"
[ "$JV" = "21" ] || fail "JDK 21 is required; found Java $JV at $JAVA_BIN. Set JAVA_HOME to a JDK 21 (brew: /opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home)."
export PATH="${JAVA_HOME:+$JAVA_HOME/bin:}$PATH"
ok "java 21 ($JAVA_BIN)"

command -v mvn >/dev/null 2>&1 || fail "maven not found. Install Maven 3.9+ ('brew install maven' or https://maven.apache.org/install.html)."
ok "maven $(mvn -v 2>/dev/null | awk '/Apache Maven/ {print $3; exit}')"

command -v curl >/dev/null 2>&1 || fail "curl not found."
command -v python3 >/dev/null 2>&1 || fail "python3 not found (used to read JSON responses)."
ok "curl, python3"

HAVE_NODE=0
if command -v node >/dev/null 2>&1 && command -v npm >/dev/null 2>&1; then
  NV="$(node -v | sed 's/^v//' | cut -d. -f1)"
  if [ "$NV" -ge 20 ]; then HAVE_NODE=1; ok "node $(node -v)"; else warn "node $(node -v) found; 20+ is needed for the UI build. Skipping the UI."; fi
else
  warn "node/npm not found; the operator UI will not be built (API, Swagger, MCP and UCP still work). Install Node 20+ to get the UI."
fi

# ----------------------------------------------------------------------------------------------------------------------
step "2/7 Environment file"
if [ ! -f .env ]; then
  cp .env.example .env
  ok ".env created from .env.example (edit it to add AI keys; everything works without them in degraded mode)"
else
  ok ".env already present (left untouched)"
fi
# Load .env for the service (values exported; comments and blank lines ignored by the shell).
set -a; # shellcheck disable=SC1091
source .env; set +a
PORT="${PORT_OVERRIDE:-${OMS_PORT:-8080}}"
export OMS_PORT="$PORT"
export OMS_CONFIG_DIR="${OMS_CONFIG_DIR:-$ROOT/config}"
# Reference tenants seeded alongside the demo (folders under config/tenants; missing folders are skipped with a warning).
export OMS_DEMO_TENANTS="${OMS_DEMO_TENANTS:-demo,retail,electronics,apparel}"
BASE="http://localhost:$PORT"

if command -v lsof >/dev/null 2>&1 && lsof -iTCP:"$PORT" -sTCP:LISTEN >/dev/null 2>&1; then
  if [ -f "$PID_FILE" ] && kill -0 "$(cat "$PID_FILE")" 2>/dev/null; then
    warn "a service started by this script is already running (pid $(cat "$PID_FILE")); stopping it first"
    kill "$(cat "$PID_FILE")"; sleep 3
  else
    fail "port $PORT is already in use. Stop the other process or run with --port <n>."
  fi
fi

# ----------------------------------------------------------------------------------------------------------------------
step "3/7 Starting PostgreSQL and Redis (docker compose)"
docker compose up -d postgres redis >/dev/null
for i in $(seq 1 60); do
  if docker compose exec -T postgres pg_isready -U oms -d oms >/dev/null 2>&1; then break; fi
  [ "$i" = 60 ] && fail "PostgreSQL did not become ready in 60 s. Check: docker compose logs postgres"
  sleep 1
done
ok "postgres ready on localhost:5440 (oms/oms), redis on 6379"

# ----------------------------------------------------------------------------------------------------------------------
step "4/7 Building the operator UI"
if [ "$NO_UI" = 1 ]; then
  warn "skipped (--no-ui)"
elif [ "$HAVE_NODE" = 0 ]; then
  warn "skipped (no Node 20+)"
elif [ "$SKIP_BUILD" = 1 ] && [ -f "$STATIC_INDEX" ]; then
  ok "reusing existing static UI (--skip-build)"
else
  ./scripts/build-ui.sh >"$STATE_DIR/ui-build.log" 2>&1 || { tail -30 "$STATE_DIR/ui-build.log"; fail "UI build failed; see $STATE_DIR/ui-build.log"; }
  ok "UI built into backend/oms-app/src/main/resources/static"
fi

# ----------------------------------------------------------------------------------------------------------------------
step "5/7 Building the backend (mvn -DskipTests clean install)"
if [ "$SKIP_BUILD" = 1 ] && [ -f "$JAR" ]; then
  ok "reusing $JAR (--skip-build)"
else
  ( cd backend && mvn -q -DskipTests clean install ) >"$STATE_DIR/backend-build.log" 2>&1 || { tail -40 "$STATE_DIR/backend-build.log"; fail "backend build failed; see $STATE_DIR/backend-build.log"; }
  ok "built $JAR"
fi

# ----------------------------------------------------------------------------------------------------------------------
step "6/7 Starting Intent OMS on :$PORT"
JAVA_OPTS="${JAVA_OPTS:--XX:MaxRAMPercentage=50}"
if [ "$FOREGROUND" = 1 ]; then
  bold "running in the foreground; Ctrl-C stops it. Open $BASE once 'Started' appears."
  exec "$JAVA_BIN" $JAVA_OPTS -jar "$JAR"
fi
# shellcheck disable=SC2086
nohup "$JAVA_BIN" $JAVA_OPTS -jar "$JAR" >"$LOG_FILE" 2>&1 &
echo $! >"$PID_FILE"
ok "pid $(cat "$PID_FILE"), log $LOG_FILE"

printf '  waiting for readiness'
for i in $(seq 1 180); do
  if curl -sf "$BASE/actuator/health/readiness" 2>/dev/null | grep -q '"UP"'; then printf '\n'; break; fi
  if ! kill -0 "$(cat "$PID_FILE")" 2>/dev/null; then printf '\n'; tail -40 "$LOG_FILE"; fail "the service exited during start-up; see $LOG_FILE"; fi
  [ "$i" = 180 ] && { printf '\n'; tail -40 "$LOG_FILE"; fail "not ready after 180 s; see $LOG_FILE"; }
  printf '.'; sleep 1
done
ok "ready: $BASE/actuator/health/readiness"

# ----------------------------------------------------------------------------------------------------------------------
step "7/7 Demo tenants ($OMS_DEMO_TENANTS)"
SEED="${OMS_DEMO_SEED:-true}"
if [ "$SEED" = "true" ] && [ "$SEED_WAIT" = 1 ]; then
  TOKEN=""
  printf '  waiting for the demo seed (sample orders)'
  for i in $(seq 1 150); do
    TOKEN="$(curl -sf -X POST "$BASE/api/v1/auth/login" -H 'Content-Type: application/json' -d '{"tenantId":"demo","userId":"admin","password":"admin"}' 2>/dev/null | python3 -c 'import sys,json; print(json.load(sys.stdin).get("token",""))' 2>/dev/null || true)"
    if [ -n "$TOKEN" ]; then
      TOTAL="$(curl -sf "$BASE/api/v1/orders?limit=1" -H "Authorization: Bearer $TOKEN" | python3 -c 'import sys,json; print(json.load(sys.stdin).get("total",0))' 2>/dev/null || echo 0)"
      if [ "${TOTAL:-0}" -gt 0 ] && grep -q 'Demo seed summary:' "$LOG_FILE" 2>/dev/null; then printf '\n'; break; fi
    fi
    [ "$i" = 150 ] && { printf '\n'; warn "seed still running after 150 s; it finishes in the background (watch $LOG_FILE)"; }
    printf '.'; sleep 2
  done
  if [ -n "$TOKEN" ]; then
    curl -sf "$BASE/api/v1/orders?limit=1" -H "Authorization: Bearer $TOKEN" | python3 -c 'import sys,json; d=json.load(sys.stdin); print("  ✓ demo tenant seeded:", d.get("total",0), "orders")' 2>/dev/null || true
    curl -sf "$BASE/api/v1/public/tenants" | python3 -c 'import sys,json; d=json.load(sys.stdin); print("  ✓ tenants available on the login page:", ", ".join(t["tenantId"]+" ("+t.get("name","")+")" for t in d))' 2>/dev/null || true
    curl -sf "$BASE/api/v1/decisions/providers" -H "Authorization: Bearer $TOKEN" | python3 -c 'import sys,json; d=json.load(sys.stdin); on=[k for k,v in d.items() if v and k!="heuristic"]; print("  ✓ AI providers available:", ", ".join(on) if on else "none (degraded mode: rules, heuristic fallback and defaults)")' 2>/dev/null || true
  fi
elif [ "$SEED" = "true" ]; then
  ok "seed runs in the background (--no-seed-wait)"
else
  ok "demo seed disabled (OMS_DEMO_SEED=false)"
fi

# ----------------------------------------------------------------------------------------------------------------------
echo
bold "Intent OMS is running."
cat <<TXT

  UI and API         $BASE
  Swagger UI         $BASE/swagger-ui
  MCP (agents)       POST $BASE/mcp
  UCP                $BASE/ucp/v1  (discovery: $BASE/.well-known/ucp)
  Health / metrics   $BASE/actuator/health/readiness · $BASE/actuator/prometheus
  Logs               $LOG_FILE

  Demo logins (every seeded tenant: $OMS_DEMO_TENANTS; password = user name; pick the tenant on the login page):
    admin      everything
    ops        ops_analyst         holds, releases, bulk actions, tasks, exceptions
    cs         cs_rep              order changes, returns, refunds, appeasements
    risk       risk_analyst        return decisions, agent admin, decision approvals and overrides
    finance    finance_controller  refund and margin-floor approvals
    strategy   strategy_lead       policy studio drafts, simulations, bulk actions

  Next steps:
    scripts/demo.sh                      end-to-end order: promise → capture → ship → deliver → return → KPIs
    scripts/usecases/run-all.sh          the five business scenarios (docs/DEMO_USE_CASES.md)
    scripts/reference/retail.sh          Maple & Main general retailer: 5 scenarios (config/tenants/retail/README.md)
    scripts/reference/electronics.sh     Voltline Electronics: 5 scenarios (config/tenants/electronics/README.md)
    scripts/reference/apparel.sh         Lumen & Loom apparel: 5 scenarios (config/tenants/apparel/README.md)
    scripts/new-tenant.sh acme "Acme"    your own tenant from config/tenants/starter
    open $BASE/studio                  policy studio; the copilot panel turns an English request into a draft
    docs/GETTING_STARTED.md              adopter's guide; docs/examples/ for client code and webhooks

  AI keys: add OPENROUTER_API_KEY (or TYPESAFE_API_KEY / ANTHROPIC_API_KEY / OPENAI_API_KEY) to .env and restart
  (scripts/bootstrap.sh --skip-build) to replace the deterministic fallback with Jev and an LLM.
  Stop with: scripts/bootstrap.sh --stop
TXT
