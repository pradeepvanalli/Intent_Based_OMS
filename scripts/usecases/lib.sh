#!/usr/bin/env bash
# Shared helpers for the demo use-case scripts. Source this file.
set -euo pipefail
BASE=${BASE:-http://localhost:8080}
TENANT=${TENANT:-demo}
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

token() { "$ROOT/scripts/token.sh" "${1:-admin}" "${2:-${1:-admin}}" "$TENANT" "$BASE"; }
TOKEN=${TOKEN:-$(token admin admin)}
H=(-H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" -H "X-Tenant-Id: $TENANT")

api() { # api METHOD PATH [JSON]  (uses $TOKEN unless AS_TOKEN is set)
  local m=$1 p=$2 body=${3:-}
  local t=${AS_TOKEN:-$TOKEN}
  if [ -n "$body" ]; then curl -s -X "$m" "$BASE$p" -H "Authorization: Bearer $t" -H "Content-Type: application/json" -H "X-Tenant-Id: $TENANT" -d "$body";
  else curl -s -X "$m" "$BASE$p" -H "Authorization: Bearer $t" -H "X-Tenant-Id: $TENANT"; fi
}
j() { python3 -c "import sys,json; d=json.load(sys.stdin); print($1)"; }
jq_() { python3 -c "import sys,json; d=json.load(sys.stdin); $1"; }
step() { printf '\n\033[1;36m▶ %s\033[0m\n' "$*"; }
say() { printf '\033[2m  %s\033[0m\n' "$*"; }
hr() { printf '\033[1;33m%s\033[0m\n' "────────────────────────────────────────────────────────────────────────"; }
wf_settle() { sleep "${1:-3}"; }

# Capture an order. Args: channelOrderId JSON-body-extras (lines, addresses, notes, intentChoices...) → prints order id
capture() {
  local ref=$1 body=$2
  api POST /api/v1/orders "$body" | j 'd.get("id") or json.dumps(d)'
}
# Ship + deliver an order through the mock WMS/carrier webhooks
ship_and_deliver() {
  local oid=$1 node=${2:-DC-NJ} ts=$(date +%s%N)
  curl -s -X POST "$BASE/webhooks/wms/$node" -H "Content-Type: application/json" -H "X-Tenant-Id: $TENANT" \
    -d "{\"eventId\":\"wms-$ts-$oid\",\"type\":\"shipped\",\"orderId\":\"$oid\",\"carrier\":\"UPS\",\"service\":\"ground\",\"tracking\":\"1Z$ts\"}" >/dev/null
  sleep 1.5
  curl -s -X POST "$BASE/webhooks/carrier" -H "Content-Type: application/json" -H "X-Tenant-Id: $TENANT" \
    -d "{\"eventId\":\"car-$ts-$oid\",\"type\":\"delivered\",\"orderId\":\"$oid\",\"tracking\":\"1Z$ts\"}" >/dev/null
  sleep 1.5
}
order_node() { api GET "/api/v1/orders/$1/view" | j '(d.get("fulfilmentGroups") or [{}])[0].get("nodeId","DC-NJ")'; }
order_status() { api GET "/api/v1/orders/$1/view" | j 'd["order"]["status"]'; }
decision_for() { api GET "/api/v1/decision-records?subjectType=$2&subjectId=$1" | j "next((r['id'] for r in d if r.get('decision')=='$3'),'')"; }
why() { api GET "/api/v1/decision-records/$1/why" | jq_ 'print("    decision", d["decision"], "v"+str(d["version"]), "tier", d["tier"], "| layer", d["layer"], "| band", d["band"], "| action", d["action"], "| value", d["value"], "| confidence", round(d.get("confidence") or 0,2), "| provider", d.get("provider")); [print("    rule fired:", r) for r in (d.get("rulesFired") or [])]; [print("    reason code:", c) for c in (d.get("reasonCodes") or [])]'; }
jev_enabled() { api GET /api/v1/decisions/providers | python3 -c "import sys,json; d=json.load(sys.stdin); a=d if isinstance(d,dict) else {}; print('yes' if a.get('jev') or (isinstance(d,list) and any(p.get('name')=='jev' and p.get('available') for p in d)) else 'no')" 2>/dev/null || echo no; }
