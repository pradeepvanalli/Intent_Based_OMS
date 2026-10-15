#!/usr/bin/env bash
# Runs on every start of the Codespace / dev container: start Postgres + Redis + Intent OMS (non-interactive) and print
# where to click. Logs: .oms/oms.log. Stop with scripts/bootstrap.sh --stop; rebuild after code changes with
# scripts/bootstrap.sh (full) or `make build && scripts/bootstrap.sh --skip-build`.
set -uo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."

# docker-in-docker needs a moment after the container starts.
for i in $(seq 1 30); do docker info >/dev/null 2>&1 && break; sleep 2; done

if [ -f backend/oms-app/target/intent-oms.jar ]; then
  scripts/bootstrap.sh --skip-build --no-seed-wait </dev/null || echo "bootstrap failed; see .oms/oms.log and run scripts/doctor.sh"
else
  echo "no jar yet (onCreateCommand still running or failed): run scripts/bootstrap.sh when it finishes"
fi

if [ -n "${CODESPACE_NAME:-}" ]; then
  echo
  echo "Codespaces URL: https://${CODESPACE_NAME}-8080.${GITHUB_CODESPACES_PORT_FORWARDING_DOMAIN:-app.github.dev}"
  echo "(or click the 'Intent OMS' port in the PORTS panel). Logins: tenant demo, user admin, password admin."
fi
