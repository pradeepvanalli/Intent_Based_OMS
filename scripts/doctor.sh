#!/usr/bin/env bash
# Environment check for Intent OMS. Prints what is installed, what is missing and how to fix it.
#
#   scripts/doctor.sh            check everything (Docker path + source-build path)
#   scripts/doctor.sh --docker   only what `scripts/try.sh` / `docker compose up` needs
#   scripts/doctor.sh --source   only what `scripts/bootstrap.sh` / `make run` needs (JDK 21, Maven, Node 22)
#
# Exit code: 0 when every required check for the selected path passes, 1 otherwise. Warnings never fail the run.
set -uo pipefail

MODE=all
case "${1:-}" in
  --docker) MODE=docker ;;
  --source) MODE=source ;;
  -h|--help) sed -n '2,9p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
  "") ;;
  *) echo "unknown option: $1" >&2; exit 2 ;;
esac

OS="$(uname -s)"
FAILS=0; WARNS=0
bold() { printf '\033[1m%s\033[0m\n' "$*"; }
ok()   { printf '  \033[32m✓\033[0m %s\n' "$*"; }
warn() { printf '  \033[33m!\033[0m %s\n' "$*"; WARNS=$((WARNS+1)); }
bad()  { printf '  \033[31m✗\033[0m %s\n' "$*"; FAILS=$((FAILS+1)); }
fix()  { printf '      \033[2mfix:\033[0m %s\n' "$*"; }
section() { printf '\n\033[1;36m%s\033[0m\n' "$*"; }

# ----------------------------------------------------------------------------------------------------------------- helpers
port_in_use() {  # port_in_use <port> → 0 if something listens
  if command -v lsof >/dev/null 2>&1; then lsof -nP -iTCP:"$1" -sTCP:LISTEN >/dev/null 2>&1
  elif command -v ss >/dev/null 2>&1; then ss -ltn 2>/dev/null | awk '{print $4}' | grep -q ":$1\$"
  elif command -v netstat >/dev/null 2>&1; then netstat -an 2>/dev/null | grep -E "[.:]$1 .*LISTEN" >/dev/null
  else return 1; fi
}
port_owner() {
  if command -v lsof >/dev/null 2>&1; then lsof -nP -iTCP:"$1" -sTCP:LISTEN 2>/dev/null | awk 'NR==2 {print $1" (pid "$2")"}'; fi
}
total_mem_gb() {
  case "$OS" in
    Darwin) echo $(( $(sysctl -n hw.memsize 2>/dev/null || echo 0) / 1024 / 1024 / 1024 )) ;;
    Linux)  awk '/MemTotal/ {printf "%d", $2/1024/1024}' /proc/meminfo 2>/dev/null || echo 0 ;;
    *) echo 0 ;;
  esac
}
java_major() { "$1" -version 2>&1 | awk -F'"' '/version/ {split($2,v,"."); print (v[1]=="1")?v[2]:v[1]; exit}'; }

bold "Intent OMS doctor ($OS, $(date '+%Y-%m-%d %H:%M'))"

# ----------------------------------------------------------------------------------------------------------------- common
section "Machine"
MEM="$(total_mem_gb)"
if [ "$MEM" -ge 8 ]; then ok "memory: ${MEM} GiB"
elif [ "$MEM" -gt 0 ]; then warn "memory: ${MEM} GiB (8 GiB recommended: the JVM takes ~1.5 GiB, Postgres ~0.5 GiB, the image build ~3 GiB)"
else warn "memory: could not determine"; fi
NCPU="$( (command -v nproc >/dev/null 2>&1 && nproc) || sysctl -n hw.ncpu 2>/dev/null || echo '?')"
ok "cpus: $NCPU"
FREE_GB="$(df -Pk . 2>/dev/null | awk 'NR==2 {printf "%d", $4/1024/1024}')"
if [ -n "$FREE_GB" ] && [ "$FREE_GB" -lt 6 ]; then warn "disk: ${FREE_GB} GiB free here (the image build needs ~4 GiB of layers and cache)"; else ok "disk: ${FREE_GB:-?} GiB free"; fi
for t in curl; do
  if command -v "$t" >/dev/null 2>&1; then ok "$t"; else bad "$t not found"; fix "install $t with your package manager"; fi
done
if command -v python3 >/dev/null 2>&1; then ok "python3 $(python3 --version 2>&1 | awk '{print $2}')"; else warn "python3 not found (scripts/bootstrap.sh and scripts/demo.sh use it to read JSON; try.sh does not)"; fi

section "Ports"
for p in 8080 5440; do
  if port_in_use "$p"; then
    owner="$(port_owner "$p")"
    case "$p" in
      8080) bad "port 8080 busy${owner:+ by $owner}"; fix "stop that process (scripts/bootstrap.sh --stop, scripts/try.sh --down) or run with OMS_PORT=8090 (try.sh honours OMS_PORT)" ;;
      5440) if docker ps --format '{{.Names}}' 2>/dev/null | grep -q 'postgres'; then ok "port 5440 in use by the compose Postgres (expected)"; else bad "port 5440 busy${owner:+ by $owner}"; fix "compose publishes Postgres on 5440; stop the other process or change the published port in docker-compose.yml"; fi ;;
    esac
  else ok "port $p free"; fi
done
if port_in_use 6379; then warn "port 6379 in use (compose starts Redis there; fine if it is the compose Redis, otherwise stop the other Redis)"; else ok "port 6379 free"; fi

# ----------------------------------------------------------------------------------------------------------------- docker
if [ "$MODE" != source ]; then
  section "Docker (scripts/try.sh, docker compose up --build, docker-compose.quickstart.yml)"
  if command -v docker >/dev/null 2>&1; then
    ok "docker client $(docker version --format '{{.Client.Version}}' 2>/dev/null || echo '?')"
    if docker info >/dev/null 2>&1; then
      ok "docker daemon $(docker version --format '{{.Server.Version}}' 2>/dev/null || echo running)"
      DMEM="$(docker info --format '{{.MemTotal}}' 2>/dev/null || echo 0)"; DMEM_GB=$(( DMEM / 1024 / 1024 / 1024 ))
      if [ "$DMEM" -gt 0 ] && [ "$DMEM_GB" -lt 4 ]; then
        bad "docker has only ${DMEM_GB} GiB of memory"; fix "Docker Desktop → Settings → Resources → Memory: give it at least 4 GiB (6 GiB for a comfortable image build)"
      elif [ "$DMEM" -gt 0 ]; then ok "docker memory ${DMEM_GB} GiB, cpus $(docker info --format '{{.NCPU}}' 2>/dev/null)"; fi
      if docker buildx version >/dev/null 2>&1; then ok "buildx $(docker buildx version 2>/dev/null | awk '{print $2}')"; else warn "buildx missing: the Dockerfile uses BuildKit cache mounts; set DOCKER_BUILDKIT=1 or upgrade Docker"; fi
    else
      bad "docker daemon not running"
      case "$OS" in
        Darwin) fix "open -a Docker  (Docker Desktop, OrbStack or Colima), wait for it to start, re-run" ;;
        Linux)  fix "sudo systemctl start docker; add yourself to the docker group: sudo usermod -aG docker \$USER && newgrp docker" ;;
        *)      fix "start Docker Desktop" ;;
      esac
    fi
    if docker compose version >/dev/null 2>&1; then
      CV="$(docker compose version --short 2>/dev/null || echo 0)"
      case "$CV" in 1.*) bad "docker compose $CV: v2+ required"; fix "upgrade Docker Desktop or install the compose v2 plugin: https://docs.docker.com/compose/install/" ;; *) ok "docker compose $CV" ;; esac
    else
      bad "'docker compose' (v2 plugin) not found"
      fix "Docker Desktop ships it; on Linux: sudo apt-get install docker-compose-plugin (Debian/Ubuntu) or https://docs.docker.com/compose/install/linux/"
    fi
  else
    bad "docker not installed"
    case "$OS" in
      Darwin) fix "brew install --cask docker   (or OrbStack: brew install orbstack), then open it once" ;;
      Linux)  fix "curl -fsSL https://get.docker.com | sh && sudo usermod -aG docker \$USER" ;;
      *)      fix "https://docs.docker.com/get-docker/" ;;
    esac
  fi
fi

# ----------------------------------------------------------------------------------------------------------------- source
if [ "$MODE" != docker ]; then
  section "Source build (scripts/bootstrap.sh, make run)"
  JH=""
  if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ]; then JH="$JAVA_HOME"
  elif [ -x /usr/libexec/java_home ] && /usr/libexec/java_home -v 21 >/dev/null 2>&1; then JH="$(/usr/libexec/java_home -v 21)"
  elif [ -d /opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home ]; then JH=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
  fi
  JB="${JH:+$JH/bin/}java"
  if command -v "$JB" >/dev/null 2>&1; then
    JV="$(java_major "$JB")"
    if [ "$JV" = "21" ]; then ok "java 21 ($JB)"; [ -z "${JAVA_HOME:-}" ] && [ -n "$JH" ] && warn "JAVA_HOME is not set; export JAVA_HOME=$JH (bootstrap.sh and the Makefile locate it, plain mvn may not)"
    else
      bad "java $JV found at $JB; JDK 21 is required"
      case "$OS" in
        Darwin) fix "brew install openjdk@21 && export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home" ;;
        Linux)  fix "sudo apt-get install temurin-21-jdk (Adoptium repo) or sdk install java 21-tem (SDKMAN), then export JAVA_HOME" ;;
        *)      fix "https://adoptium.net/temurin/releases/?version=21" ;;
      esac
    fi
  else
    bad "java not found"
    case "$OS" in
      Darwin) fix "brew install openjdk@21 && export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home" ;;
      *)      fix "install Temurin 21: https://adoptium.net/temurin/releases/?version=21 (or: sdk install java 21-tem)" ;;
    esac
  fi
  if command -v mvn >/dev/null 2>&1; then
    MV="$(mvn -v 2>/dev/null | awk '/Apache Maven/ {print $3; exit}')"
    case "$MV" in 3.9*|3.1[0-9]*|4.*) ok "maven $MV" ;; *) warn "maven $MV (3.9+ recommended)" ;; esac
  else
    bad "maven not found"
    case "$OS" in Darwin) fix "brew install maven" ;; Linux) fix "sudo apt-get install maven  (3.9+; or sdk install maven)" ;; *) fix "https://maven.apache.org/install.html" ;; esac
  fi
  if command -v node >/dev/null 2>&1 && command -v npm >/dev/null 2>&1; then
    NV="$(node -v | sed 's/^v//' | cut -d. -f1)"
    if [ "$NV" -ge 22 ]; then ok "node $(node -v), npm $(npm -v)"
    elif [ "$NV" -ge 20 ]; then warn "node $(node -v): works, but CI and the image use Node 22"
    else bad "node $(node -v) is too old for the UI build (22 recommended, 20 minimum)"; fix "nvm install 22 && nvm use 22   (or brew install node@22)"; fi
  else
    warn "node/npm not found: the operator UI will not be built from source (API, Swagger, MCP and UCP still work). Install Node 22: https://nodejs.org or nvm install 22"
  fi
  if command -v lsof >/dev/null 2>&1; then ok "lsof (used by bootstrap.sh to check ports)"; else warn "lsof not found; bootstrap.sh skips the port check (apt-get install lsof)"; fi
fi

# ----------------------------------------------------------------------------------------------------------------- repo
section "Repository"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
if [ -f "$ROOT/.env" ]; then
  ok ".env present"
  if grep -Eq '^(OPENROUTER_API_KEY|TYPESAFE_API_KEY|ANTHROPIC_API_KEY|OPENAI_API_KEY)=.+' "$ROOT/.env" 2>/dev/null; then ok "AI key configured in .env (Jev / LLM decisions enabled)"
  else warn "no AI key in .env: the system runs in degraded mode on rules and defaults (add OPENROUTER_API_KEY to switch AI on)"; fi
else
  ok ".env absent (scripts/try.sh and scripts/bootstrap.sh create it from .env.example)"
fi
[ -d "$ROOT/config/tenants/demo" ] && ok "config/tenants/demo present (demo seed source)" || bad "config/tenants/demo missing: is this a complete checkout?"

# ----------------------------------------------------------------------------------------------------------------- summary
echo
if [ "$FAILS" -eq 0 ]; then
  bold "All required checks passed${WARNS:+ ($WARNS warning(s))}."
  [ "$MODE" != source ] && echo "  Docker path:  scripts/try.sh                (containers only; nothing else to install)"
  [ "$MODE" != docker ] && echo "  Source path:  scripts/bootstrap.sh          (JDK 21 + Maven + Node on this machine)"
  exit 0
else
  bold "$FAILS problem(s) found, $WARNS warning(s). Apply the fixes above and re-run scripts/doctor.sh."
  echo "  More help: docs/QUICKSTART.md, docs/GETTING_STARTED.md, https://github.com/pradeepvanalli/Invent_Based_OMS/discussions"
  exit 1
fi
