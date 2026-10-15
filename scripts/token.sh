#!/usr/bin/env bash
# Print a JWT for a demo user. Usage: scripts/token.sh [user] [password] [tenant] [base]
set -euo pipefail
USER_ID=${1:-admin}; PASS=${2:-admin}; TENANT=${3:-demo}; BASE=${4:-http://localhost:8080}
curl -sf -X POST "$BASE/api/v1/auth/login" -H 'Content-Type: application/json' \
  -d "{\"tenantId\":\"$TENANT\",\"userId\":\"$USER_ID\",\"password\":\"$PASS\"}" | python3 -c 'import sys,json; print(json.load(sys.stdin)["token"])'
