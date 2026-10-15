#!/usr/bin/env bash
# Runs once when the Codespace / dev container is created: warm the dependency caches and build the UI + backend so
# the first `postStartCommand` (scripts/bootstrap.sh --skip-build) starts in seconds instead of minutes.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."

echo "▶ Intent OMS dev container: first-time build"
[ -f .env ] || { cp .env.example .env; echo "  ✓ .env created from .env.example"; }
# Codespaces secret OPENROUTER_API_KEY (Settings → Codespaces → Secrets) switches the AI providers on.
if [ -n "${OPENROUTER_API_KEY:-}" ] && ! grep -Eq '^OPENROUTER_API_KEY=.+' .env; then
  printf '\nOPENROUTER_API_KEY=%s\n' "$OPENROUTER_API_KEY" >>.env; echo "  ✓ OPENROUTER_API_KEY copied into .env"
fi

echo "▶ UI: npm ci + build → backend/oms-app/src/main/resources/static"
./scripts/build-ui.sh

echo "▶ Backend: mvn -DskipTests install (all modules)"
( cd backend && mvn -q -B -ntp -DskipTests clean install )

echo "▶ Pre-pulling postgres:16-alpine and redis:7-alpine"
docker pull -q postgres:16-alpine >/dev/null 2>&1 || true
docker pull -q redis:7-alpine >/dev/null 2>&1 || true
echo "✓ build done; the service starts on every container start (postStartCommand)"
