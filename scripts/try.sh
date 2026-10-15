#!/usr/bin/env bash
# Try Intent OMS with nothing but Docker: one command builds (or pulls) the image, starts PostgreSQL + Redis + the
# service with the four demo tenants seeded, waits until it is healthy, prints the logins and opens the browser.
#
#   scripts/try.sh              build the image from this checkout and start everything   (docker-compose.yml)
#   scripts/try.sh --pull       use the published image instead of building                (docker-compose.quickstart.yml)
#   scripts/try.sh --reset      wipe the database volume and start fresh (asks first; --yes skips the question)
#   scripts/try.sh --down       stop the containers (data is kept; --reset --down also removes the data)
#   scripts/try.sh --logs       follow the service log
#
# Options: --yes (no questions)  --no-open (do not open a browser)  --image <ref> (image for --pull)
# Environment: OPENROUTER_API_KEY (copied into .env on first run → Jev + LLM decisions), OMS_PORT (host port, 8080).
# Idempotent: run it again after a `git pull` to rebuild and restart; nothing is asked. Problems? scripts/doctor.sh
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

PULL=0; RESET=0; DOWN=0; YES=0; OPEN=1; LOGS=0; IMAGE=""
while [ $# -gt 0 ]; do
  case "$1" in
    --pull) PULL=1 ;;
    --reset) RESET=1 ;;
    --down) DOWN=1 ;;
    --yes|-y) YES=1 ;;
    --no-open) OPEN=0 ;;
    --logs) LOGS=1 ;;
    --image) shift; IMAGE="${1:-}" ;;
    -h|--help) sed -n '2,13p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "unknown option: $1 (try --help)" >&2; exit 2 ;;
  esac
  shift
done

bold() { printf '\033[1m%s\033[0m\n' "$*"; }
step() { printf '\n\033[1;36m▶ %s\033[0m\n' "$*"; }
ok()   { printf '  \033[32m✓\033[0m %s\n' "$*"; }
warn() { printf '  \033[33m!\033[0m %s\n' "$*"; }
fail() { printf '  \033[31m✗\033[0m %s\n' "$*" >&2; printf '  \033[2mrun scripts/doctor.sh for a full environment check and fixes\033[0m\n' >&2; exit 1; }

COMPOSE_FILE="docker-compose.yml"
[ "$PULL" = 1 ] && COMPOSE_FILE="docker-compose.quickstart.yml"
[ -f "$COMPOSE_FILE" ] || fail "$COMPOSE_FILE not found in $ROOT (run this script from a checkout of the repository)"
compose() { docker compose -f "$COMPOSE_FILE" "$@"; }

PORT="${OMS_PORT:-8080}"
if [ -f .env ]; then  # OMS_PORT in .env is the host port compose publishes; honour it when the shell does not override
  ENV_PORT="$(grep -E '^OMS_PORT=' .env | tail -1 | cut -d= -f2 | awk '{print $1}')"
  [ -z "${OMS_PORT:-}" ] && [ -n "$ENV_PORT" ] && PORT="$ENV_PORT"
fi
BASE="http://localhost:$PORT"

# ---------------------------------------------------------------------------------------------------------------- docker
step "Checking Docker"
command -v docker >/dev/null 2>&1 || fail "docker is not installed. macOS: brew install --cask docker; Linux: curl -fsSL https://get.docker.com | sh; https://docs.docker.com/get-docker/"
docker info >/dev/null 2>&1 || fail "docker is installed but not running. Start Docker Desktop (or: sudo systemctl start docker) and re-run."
docker compose version >/dev/null 2>&1 || fail "'docker compose' v2 is required. Upgrade Docker Desktop or install the compose plugin: https://docs.docker.com/compose/install/"
ok "docker $(docker version --format '{{.Server.Version}}' 2>/dev/null || echo ok), compose $(docker compose version --short 2>/dev/null || echo ok)"

# ---------------------------------------------------------------------------------------------------------------- --logs / --down
if [ "$LOGS" = 1 ]; then exec docker compose -f "$COMPOSE_FILE" logs -f oms; fi

if [ "$DOWN" = 1 ]; then
  step "Stopping Intent OMS"
  if [ "$RESET" = 1 ]; then
    if [ "$YES" != 1 ]; then read -r -p "  This deletes the database volume (all orders, tenants, drafts). Continue? [y/N] " a; case "$a" in y|Y|yes) ;; *) echo "  aborted"; exit 0 ;; esac; fi
    compose down -v --remove-orphans; ok "containers stopped and data removed"
  else
    compose down --remove-orphans; ok "containers stopped (data kept in the 'pgdata' volume; scripts/try.sh --reset --down removes it)"
  fi
  exit 0
fi

# ---------------------------------------------------------------------------------------------------------------- .env
step "Environment file"
if [ ! -f .env ]; then
  cp .env.example .env
  ok ".env created from .env.example (every value is a safe local default)"
else
  ok ".env already present (left as it is)"
fi
if [ -n "${OPENROUTER_API_KEY:-}" ]; then
  if grep -Eq '^OPENROUTER_API_KEY=.+' .env; then
    ok "OPENROUTER_API_KEY already set in .env"
  else
    printf '\n# added by scripts/try.sh from the environment\nOPENROUTER_API_KEY=%s\n' "$OPENROUTER_API_KEY" >>.env
    ok "OPENROUTER_API_KEY copied from your environment into .env (Jev + LLM decisions on)"
  fi
elif grep -Eq '^(OPENROUTER_API_KEY|TYPESAFE_API_KEY|ANTHROPIC_API_KEY|OPENAI_API_KEY)=.+' .env; then
  ok "AI key found in .env"
else
  warn "no AI key: the system runs in degraded mode (rules, heuristics and defaults). export OPENROUTER_API_KEY=sk-or-... and re-run to switch AI on."
fi

# ---------------------------------------------------------------------------------------------------------------- reset
if [ "$RESET" = 1 ]; then
  step "Resetting the database"
  if [ "$YES" != 1 ]; then
    read -r -p "  This deletes the database volume (all orders, tenants, drafts) and re-seeds the demo tenants. Continue? [y/N] " a
    case "$a" in y|Y|yes) ;; *) echo "  aborted"; exit 0 ;; esac
  fi
  compose down -v --remove-orphans >/dev/null 2>&1 || true
  ok "volume removed; the demo tenants will be seeded again on start"
fi

# ---------------------------------------------------------------------------------------------------------------- port
if ! compose ps --status running --format '{{.Service}}' 2>/dev/null | grep -qx oms; then
  if command -v lsof >/dev/null 2>&1 && lsof -nP -iTCP:"$PORT" -sTCP:LISTEN >/dev/null 2>&1; then
    fail "port $PORT is already in use by another process. Stop it (scripts/bootstrap.sh --stop stops a source-built service) or run: OMS_PORT=8090 scripts/try.sh"
  fi
fi

# ---------------------------------------------------------------------------------------------------------------- up
if [ "$PULL" = 1 ]; then
  step "Pulling the published image and starting (docker-compose.quickstart.yml)"
  [ -n "$IMAGE" ] && export OMS_IMAGE="$IMAGE"
  OMS_PORT="$PORT" compose pull --quiet oms || fail "could not pull ${OMS_IMAGE:-the image}. Is it published yet? Build it locally instead: scripts/try.sh (without --pull)"
  OMS_PORT="$PORT" compose up -d --remove-orphans
else
  step "Building the image and starting (docker-compose.yml; the first build takes 5-10 minutes, later ones seconds)"
  OMS_PORT="$PORT" compose up -d --build --remove-orphans || fail "docker compose up failed. Scroll up for the error; common causes: not enough Docker memory (give it 4+ GiB), no network for Maven/npm downloads."
fi
ok "containers started: $(compose ps --status running --format '{{.Service}}' 2>/dev/null | tr '\n' ' ')"

# ---------------------------------------------------------------------------------------------------------------- wait
step "Waiting for Intent OMS on $BASE"
printf '  '
READY=0
for i in $(seq 1 240); do
  if curl -sf "$BASE/actuator/health" 2>/dev/null | grep -q '"UP"'; then READY=1; break; fi
  state="$(docker inspect --format '{{.State.Status}}' "$(compose ps -q oms 2>/dev/null)" 2>/dev/null || echo unknown)"
  if [ "$state" = "exited" ] || [ "$state" = "dead" ]; then
    printf '\n'; compose logs --tail 60 oms
    fail "the oms container stopped. The last log lines are above (full log: scripts/try.sh --logs). Database problem? scripts/try.sh --reset"
  fi
  printf '.'; sleep 2
done
printf '\n'
[ "$READY" = 1 ] || { compose logs --tail 40 oms; fail "not healthy after 8 minutes. Log above; scripts/try.sh --logs follows it."; }
ok "healthy: $BASE/actuator/health"

# Demo seed: a login succeeds as soon as the dev users are bootstrapped; orders appear once the seeder finishes.
printf '  seeding the demo tenants'
SEEDED=0
for i in $(seq 1 90); do
  TOKEN="$(curl -sf -X POST "$BASE/api/v1/auth/login" -H 'Content-Type: application/json' -d '{"tenantId":"demo","userId":"admin","password":"admin"}' 2>/dev/null | sed -n 's/.*"token" *: *"\([^"]*\)".*/\1/p')"
  if [ -n "$TOKEN" ]; then
    TOTAL="$(curl -sf "$BASE/api/v1/orders?limit=1" -H "Authorization: Bearer $TOKEN" 2>/dev/null | sed -n 's/.*"total" *: *\([0-9]*\).*/\1/p')"
    if [ "${TOTAL:-0}" -gt 0 ] 2>/dev/null; then SEEDED=1; break; fi
  fi
  printf '.'; sleep 2
done
printf '\n'
if [ "$SEEDED" = 1 ]; then ok "demo tenant seeded (${TOTAL} orders on 'demo'; retail, electronics and apparel follow in the background)"
else warn "seeding is still running; it finishes in the background (scripts/try.sh --logs)"; fi

# ---------------------------------------------------------------------------------------------------------------- done
echo
bold "Intent OMS is running at $BASE"
cat <<TXT

  UI                 $BASE
  Swagger UI         $BASE/swagger-ui
  MCP (agents)       POST $BASE/mcp        UCP: $BASE/ucp/v1   (discovery: $BASE/.well-known/ucp)
  Health / metrics   $BASE/actuator/health  ·  $BASE/actuator/prometheus

  Logins (password = user name, same six users on every tenant):

    tenant         admin   ops          cs       risk          finance             strategy
    ───────────    ─────   ──────────   ──────   ───────────   ─────────────────   ─────────────
    demo           admin   ops_analyst  cs_rep   risk_analyst  finance_controller  strategy_lead   general retailer, 25 orders
    retail         admin   ops_analyst  cs_rep   risk_analyst  finance_controller  strategy_lead   omnichannel retail
    electronics    admin   ops_analyst  cs_rep   risk_analyst  finance_controller  strategy_lead   high-value, serialised
    apparel        admin   ops_analyst  cs_rep   risk_analyst  finance_controller  strategy_lead   returns-heavy fashion

    e.g. tenant "demo", user "admin", password "admin"   ·   tenant "apparel", user "cs", password "cs"

  Next
    scripts/demo.sh                   one order end to end: promise → capture → ship → deliver → return → KPIs
    scripts/usecases/run-all.sh       the business scenarios in docs/DEMO_USE_CASES.md
    scripts/try.sh --logs             follow the service log      scripts/try.sh --down    stop (keeps data)
    scripts/try.sh --reset            start again from a clean database
    docs/QUICKSTART.md                what you are looking at, AI keys, the four tenants
TXT

if [ "$OPEN" = 1 ] && [ -z "${CI:-}" ] && [ -z "${CODESPACES:-}" ]; then
  if [ "$(uname -s)" = Darwin ] && command -v open >/dev/null 2>&1; then open "$BASE" >/dev/null 2>&1 || true
  elif command -v wslview >/dev/null 2>&1; then wslview "$BASE" >/dev/null 2>&1 || true
  elif command -v xdg-open >/dev/null 2>&1 && [ -n "${DISPLAY:-}${WAYLAND_DISPLAY:-}" ]; then xdg-open "$BASE" >/dev/null 2>&1 || true
  fi
fi
