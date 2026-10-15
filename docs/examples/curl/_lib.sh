#!/usr/bin/env bash
# Shared helpers for the curl examples. Source this file: `source "$(dirname "$0")/_lib.sh"`.
set -euo pipefail
BASE=${BASE:-http://localhost:8080}
TENANT=${TENANT:-demo}
OMS_USER=${OMS_USER:-admin}
OMS_PASSWORD=${OMS_PASSWORD:-admin}

login() { # login [user] [password] → JWT
  curl -sf -X POST "$BASE/api/v1/auth/login" -H 'Content-Type: application/json' \
    -d "{\"tenantId\":\"$TENANT\",\"userId\":\"${1:-$OMS_USER}\",\"password\":\"${2:-$OMS_PASSWORD}\"}" \
    | python3 -c 'import sys,json; print(json.load(sys.stdin)["token"])'
}
TOKEN=${TOKEN:-$(login)}

api() { # api METHOD PATH [JSON]  → response body (uses $AS_TOKEN when set)
  local m=$1 p=$2 body=${3:-} t=${AS_TOKEN:-$TOKEN}
  if [ -n "$body" ]; then curl -s -X "$m" "$BASE$p" -H "Authorization: Bearer $t" -H 'Content-Type: application/json' -H "X-Tenant-Id: $TENANT" --data-binary "$body"
  else curl -s -X "$m" "$BASE$p" -H "Authorization: Bearer $t" -H "X-Tenant-Id: $TENANT"; fi
}
j() { python3 -c "import sys,json; d=json.load(sys.stdin); print($1)"; }       # j 'd["id"]'
pp() { python3 -m json.tool; }
step() { printf '\n\033[1;36m▶ %s\033[0m\n' "$*"; }
