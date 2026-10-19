#!/usr/bin/env bash
# Create a tenant from a configuration folder and load it through the API.
#
#   scripts/new-tenant.sh <tenantId> "<Tenant name>" [options]
#
# What it does (idempotent; safe to re-run):
#   1. copies config/tenants/starter → config/tenants/<tenantId> (skipped when the folder exists)
#   2. logs in as the tenant's administrator (creates the tenant + admin with --create-login if needed)
#   3. loads, in order: tenant-config.yaml → state models → decisions (+ publish) → golden sets → compliance rules
#      → order types → catalog → nodes → shipping rates → playbooks → queues → SLAs → agents → inventory.yaml
#
# Options:
#   --from <dir>              template folder (default config/tenants/starter)
#   --base <url>              service URL (default $BASE or http://localhost:8080)
#   --admin <user:password>   administrator login for the NEW tenant (default $OMS_ADMIN_USER:$OMS_ADMIN_PASSWORD, else admin:<prompt>)
#   --create-login            when the login fails, insert the tenant row and a bcrypt admin user through psql
#                             (docker exec into $PG_CONTAINER, default oms-postgres-1; or psql on $PSQL_URL)
#   --publish <mode>          how new decision versions go live: emergency (default; config.admin, flagged for a
#                             48 h retro review), draft (leave as drafts), approve (submit + approve with --approvers)
#   --approvers <a:pw,b:pw>   approver logins for --publish approve (two distinct users for T2/T3 decisions)
#   --skip-inventory          do not load inventory.yaml
#   --dry-run                 print what would be sent, send nothing
#
# Requires: curl, python3 (with PyYAML, or ruby/yq as a fallback for YAML → JSON).
# Alternative without this script: start the service with OMS_DEMO_TENANT=<tenantId> OMS_DEMO_SEED=true and the
# seeder loads config/tenants/<tenantId> exactly as it does for the demo (including sample orders and inventory).
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TENANT="${1:-}"; NAME="${2:-}"
[ -n "$TENANT" ] && [ -n "$NAME" ] || { sed -n '2,24p' "$0" | sed 's/^# \{0,1\}//'; exit 2; }
shift 2
FROM="$ROOT/config/tenants/starter"; BASE="${BASE:-http://localhost:8080}"
ADMIN="${OMS_ADMIN_USER:-}${OMS_ADMIN_PASSWORD:+:$OMS_ADMIN_PASSWORD}"
CREATE_LOGIN=0; PUBLISH=emergency; APPROVERS=""; SKIP_INV=0; DRY=0
while [ $# -gt 0 ]; do
  case "$1" in
    --from) shift; FROM="$1" ;;
    --base) shift; BASE="$1" ;;
    --admin) shift; ADMIN="$1" ;;
    --create-login) CREATE_LOGIN=1 ;;
    --publish) shift; PUBLISH="$1" ;;
    --approvers) shift; APPROVERS="$1" ;;
    --skip-inventory) SKIP_INV=1 ;;
    --dry-run) DRY=1 ;;
    *) echo "unknown option: $1" >&2; exit 2 ;;
  esac; shift
done
case "$PUBLISH" in emergency|draft|approve) ;; *) echo "--publish must be emergency|draft|approve" >&2; exit 2 ;; esac
[[ "$TENANT" =~ ^[a-z0-9][a-z0-9_-]{1,62}$ ]] || { echo "tenantId must be lower-case letters, digits, - or _" >&2; exit 2; }

step() { printf '\n\033[1;36m▶ %s\033[0m\n' "$*"; }
ok()   { printf '  \033[32m✓\033[0m %s\n' "$*"; }
skip() { printf '  \033[2m· %s\033[0m\n' "$*"; }
warn() { printf '  \033[33m!\033[0m %s\n' "$*"; }
fail() { printf '  \033[31m✗\033[0m %s\n' "$*" >&2; exit 1; }
command -v curl >/dev/null || fail "curl is required"; command -v python3 >/dev/null || fail "python3 is required"

# YAML → JSON on stdout. Tries PyYAML, then ruby, then yq.
y2j() {
  if python3 -c 'import yaml' 2>/dev/null; then python3 -c 'import sys,json,yaml; print(json.dumps(yaml.safe_load(open(sys.argv[1]))))' "$1"
  elif command -v ruby >/dev/null 2>&1; then ruby -ryaml -rjson -rdate -e 'puts JSON.generate(YAML.safe_load(File.read(ARGV[0]), permitted_classes: [Date, Time]))' "$1"
  elif command -v yq >/dev/null 2>&1; then yq -o=json '.' "$1"
  else fail "YAML conversion needs PyYAML (pip3 install pyyaml), ruby or yq"; fi
}
jq_() { python3 -c "import sys,json; d=json.load(sys.stdin); $1"; }
jget() { python3 -c "import sys,json; d=json.load(sys.stdin); print($1)"; }

TOKEN=""
# raw METHOD PATH [JSON] → body, newline, HTTP status. Uses $AS_TOKEN when set.
raw() {
  local m=$1 p=$2 body=${3:-} t=${AS_TOKEN:-$TOKEN}
  if [ "$DRY" = 1 ] && [ "$m" != GET ]; then echo "  [dry-run] $m $p ${body:0:120}" >&2; printf '{}\n200'; return; fi
  if [ -n "$body" ]; then curl -s -w '\n%{http_code}' -X "$m" "$BASE$p" -H "Authorization: Bearer $t" -H "X-Tenant-Id: $TENANT" -H 'Content-Type: application/json' --data-binary "$body"
  else curl -s -w '\n%{http_code}' -X "$m" "$BASE$p" -H "Authorization: Bearer $t" -H "X-Tenant-Id: $TENANT"; fi
}
# api METHOD PATH [JSON] → body only (for pipelines).
api() { local out; out=$(raw "$@"); printf '%s' "${out%$'\n'*}"; }
# call METHOD PATH [JSON] → sets $R (body) and $STATUS in the current shell.
STATUS=""; R=""
call() { local out; out=$(raw "$@"); STATUS=${out##*$'\n'}; R=${out%$'\n'*}; }
expect() { # expect <2xx-ok-label> after an api call
  case "$STATUS" in 2*) ok "$1" ;; *) warn "$1 → HTTP $STATUS: $(printf '%s' "$2" | head -c 300)" ;; esac
}
login() { curl -s -X POST "$BASE/api/v1/auth/login" -H 'Content-Type: application/json' -d "{\"tenantId\":\"$TENANT\",\"userId\":\"$1\",\"password\":\"$2\"}" | python3 -c 'import sys,json
try: print(json.load(sys.stdin).get("token",""))
except Exception: print("")'; }

# ----------------------------------------------------------------------------------------------------------------------
step "1/15 Configuration folder"
DEST="$ROOT/config/tenants/$TENANT"
[ -d "$FROM" ] || fail "template folder not found: $FROM"
if [ -d "$DEST" ]; then skip "$DEST exists; using it as is"; else cp -R "$FROM" "$DEST"; ok "copied $FROM → $DEST"; fi
for f in tenant-config.yaml order-types.yaml catalog.yaml nodes.yaml shipping-rates.yaml playbooks.yaml sla.yaml exception-taxonomy.yaml rules/compliance.yaml; do
  [ -f "$DEST/$f" ] || warn "missing $f (that step will be skipped)"
done

# ----------------------------------------------------------------------------------------------------------------------
step "2/15 Administrator login for tenant '$TENANT'"
curl -sf "$BASE/actuator/health/readiness" >/dev/null || fail "service not reachable at $BASE (start it with scripts/bootstrap.sh)"
if [ -z "$ADMIN" ]; then
  printf '  admin user id for %s [admin]: ' "$TENANT"; read -r AU; AU=${AU:-admin}
  printf '  password (min 12 chars): '; read -rs AP; echo; ADMIN="$AU:$AP"
fi
AUSER=${ADMIN%%:*}; APASS=${ADMIN#*:}
TOKEN=$(login "$AUSER" "$APASS")
if [ -z "$TOKEN" ] && [ "$CREATE_LOGIN" = 1 ]; then
  # Preferred path: the platform-admin API (any principal holding the wildcard permission, e.g. the demo admin).
  PA_USER=${PLATFORM_ADMIN_USER:-platform}; PA_PASS=${PLATFORM_ADMIN_PASSWORD:-platform}; PA_TENANT=${PLATFORM_ADMIN_TENANT:-demo}
  PA_TOKEN=$(curl -sf -X POST "$BASE/api/v1/auth/login" -H 'Content-Type: application/json' -d "{\"tenantId\":\"$PA_TENANT\",\"userId\":\"$PA_USER\",\"password\":\"$PA_PASS\"}" | python3 -c 'import sys,json; print(json.load(sys.stdin).get("token",""))' 2>/dev/null || true)
  if [ -n "$PA_TOKEN" ]; then
    warn "login failed; creating tenant '$TENANT' and admin '$AUSER' through POST /api/v1/admin/tenants as $PA_TENANT/$PA_USER"
    RESP=$(curl -s -X POST "$BASE/api/v1/admin/tenants" -H "Authorization: Bearer $PA_TOKEN" -H 'Content-Type: application/json' -H "X-Tenant-Id: $PA_TENANT" \
      -d "$(python3 -c 'import json,sys; print(json.dumps({"tenantId": sys.argv[1], "name": sys.argv[2], "admin": {"userId": sys.argv[3], "password": sys.argv[4], "roles": ["admin"]}}))' "$TENANT" "$NAME" "$AUSER" "$APASS")")
    if printf '%s' "$RESP" | grep -q '"tenantId"'; then ok "tenant created via API: $RESP"; TOKEN=$(login "$AUSER" "$APASS") && return 0; fi
    warn "admin API refused: $RESP — falling back to psql"
  fi
  warn "login failed; creating tenant and admin user through psql (--create-login)"
  [ ${#APASS} -ge 12 ] || fail "choose a password of at least 12 characters"
  SQL=$(cat <<SQL
create extension if not exists pgcrypto;
insert into tenant(tenant_id, name) values ('$TENANT', \$name\$$NAME\$name\$) on conflict do nothing;
insert into app_user(tenant_id, user_id, display_name, password_hash, roles)
values ('$TENANT', '$AUSER', '$AUSER', crypt(\$pw\$$APASS\$pw\$, gen_salt('bf', 10)), '["admin"]'::jsonb)
on conflict (tenant_id, user_id) do update set password_hash = excluded.password_hash, roles = excluded.roles, active = true;
SQL
)
  if [ -n "${PSQL_URL:-}" ] && command -v psql >/dev/null; then printf '%s\n' "$SQL" | psql -v ON_ERROR_STOP=1 -q "$PSQL_URL"
  else printf '%s\n' "$SQL" | docker exec -i "${PG_CONTAINER:-oms-postgres-1}" psql -v ON_ERROR_STOP=1 -q -U oms -d oms; fi
  TOKEN=$(login "$AUSER" "$APASS")
fi
if [ -z "$TOKEN" ]; then
  cat >&2 <<TXT
  ✗ cannot log in as $AUSER on tenant $TENANT. There is no tenant-creation endpoint; create the tenant and its first
    administrator in one of these ways, then re-run:
      a) re-run with --create-login (inserts the tenant row and a bcrypt admin user through psql in the compose container)
      b) add "$AUSER:<password>:$TENANT:admin" to OMS_DEV_USERS in .env and restart (dev/local only)
      c) OIDC: sign in with a group mapped to the admin role and a token whose tenant claim is "$TENANT"
TXT
  exit 1
fi
ok "logged in as $AUSER"
api GET /api/v1/auth/me | jq_ 'p=d.get("permissions",[]); assert "*" in p or "config.admin" in p, "admin needs config.admin"' || fail "$AUSER lacks config.admin"

# ----------------------------------------------------------------------------------------------------------------------
step "3/15 Tenant config (tenant-config.yaml → PUT /api/v1/admin/config/{key}, only absent keys)"
if [ -f "$DEST/tenant-config.yaml" ]; then
  EXISTING=$(api GET "/api/v1/admin/config")
  CFG=$(y2j "$DEST/tenant-config.yaml")
  OT=$( [ -f "$DEST/order-types.yaml" ] && y2j "$DEST/order-types.yaml" || echo '{}')
  printf '%s\n%s\n%s' "$EXISTING" "$CFG" "$OT" | python3 -c '
import sys,json
ex,cfg,ot=[json.loads(l) for l in sys.stdin.read().split("\n",2)]
cons={t["name"]:t["sourcing"] for t in ot.get("orderTypes",[]) if t.get("sourcing")}
if cons and "sourcing.order_type_constraints" not in cfg: cfg["sourcing.order_type_constraints"]=cons
for k,v in cfg.items():
    print(json.dumps({"key":k,"value":v,"present":k in ex}))' | while read -r line; do
    K=$(printf '%s' "$line" | jget 'd["key"]'); P=$(printf '%s' "$line" | jget 'd["present"]')
    if [ "$P" = True ]; then skip "$K already set"; continue; fi
    BODY=$(printf '%s' "$line" | python3 -c 'import sys,json; d=json.load(sys.stdin); print(json.dumps({"value":d["value"],"reason":"tenant bootstrap"}))')
    call PUT "/api/v1/admin/config/$K" "$BODY"; expect "$K" "$R"
  done
fi

# ----------------------------------------------------------------------------------------------------------------------
step "4/15 State models (POST /api/v1/state-models/publish)"
for f in "$DEST"/state-models/*.yaml; do
  [ -f "$f" ] || continue
  ID=$(y2j "$f" | jget 'd["stateModel"]'); V=$(y2j "$f" | jget 'd["version"]')
  HAVE=$(api GET /api/v1/state-models | jget "json.dumps(next((m.get('versions',[]) for m in d if m.get('modelId')=='$ID'), []))" 2>/dev/null || echo "[]")
  if printf '%s' "$HAVE" | grep -qE "(^|[^0-9])$V([^0-9]|$)"; then skip "$ID v$V already published"; continue; fi
  BODY=$(python3 -c 'import sys,json; print(json.dumps({"yaml":open(sys.argv[1]).read(),"reason":"tenant bootstrap"}))' "$f")
  call POST /api/v1/state-models/publish "$BODY"; expect "$ID v$V" "$R"
done

# ----------------------------------------------------------------------------------------------------------------------
step "5/15 Decisions (POST /api/v1/decisions/{name}/drafts → publish mode: $PUBLISH)"
approve_with() { # approve_with <name> <version>
  local IFS=','; for a in $APPROVERS; do
    local t; t=$(login "${a%%:*}" "${a#*:}"); [ -n "$t" ] || { warn "approver ${a%%:*} cannot log in"; continue; }
    AS_TOKEN=$t call POST "/api/v1/decisions/$1/versions/$2/approve" '{}'; expect "approved by ${a%%:*}" "$R"
  done
}
for f in "$DEST"/decisions/*.yaml; do
  [ -f "$f" ] || continue
  NAME_D=$(y2j "$f" | jget 'd["decision"]')
  call GET "/api/v1/decisions/$NAME_D"; LIST=$R; [ "$STATUS" = 404 ] && LIST='[]'
  LIVE=$(printf '%s' "$LIST" | jget 'any((v.get("definition",v).get("status") in ("live","canary","shadow")) for v in (d if isinstance(d,list) else []))' 2>/dev/null || echo False)
  if [ "$LIVE" = True ]; then skip "$NAME_D already has a live/canary/shadow version"; continue; fi
  BODY=$(python3 -c 'import sys,json; print(json.dumps({"yaml":open(sys.argv[1]).read()}))' "$f")
  call POST "/api/v1/decisions/$NAME_D/drafts" "$BODY"; expect "$NAME_D draft" "$R"
  case "$STATUS" in 2*) ;; *) continue ;; esac
  V=$(printf '%s' "$R" | jget 'd.get("version") or d.get("definition",{}).get("version")')
  case "$PUBLISH" in
    draft) skip "left as draft v$V" ;;
    emergency)
      call POST "/api/v1/decisions/$NAME_D/versions/$V/publish" '{"rollout":"live","emergency":true}'; expect "$NAME_D v$V live (emergency publish; retro review due in 48 h)" "$R" ;;
    approve)
      [ -n "$APPROVERS" ] || fail "--publish approve needs --approvers a:pw[,b:pw]"
      call POST "/api/v1/decisions/$NAME_D/versions/$V/evaluate" '{}'
      case "$STATUS" in 2*) ok "evaluated: passed=$(printf '%s' "$R" | jget 'd.get("passed")')" ;; *) warn "evaluation → HTTP $STATUS (publish will be refused unless the gate passes)" ;; esac
      call POST "/api/v1/decisions/$NAME_D/versions/$V/submit" '{"reason":"Initial tenant configuration","ticket":"BOOTSTRAP-1"}'; expect "$NAME_D submitted" "$R"
      approve_with "$NAME_D" "$V"
      call POST "/api/v1/decisions/$NAME_D/versions/$V/publish" '{"rollout":"live"}'; expect "$NAME_D v$V live" "$R" ;;
  esac
done

# ----------------------------------------------------------------------------------------------------------------------
step "6/15 Golden sets (PUT /api/v1/golden-sets/{name})"
for f in "$DEST"/golden-sets/*.json; do
  [ -f "$f" ] || continue
  GS=$(jget 'd["goldenSet"]' <"$f")
  BODY=$(python3 -c 'import sys,json; d=json.load(open(sys.argv[1])); print(json.dumps([{"caseId":c.get("caseId"),"input":c.get("input"),"expected":c.get("expected"),"notes":c.get("notes")} for c in d["cases"]]))' "$f")
  call PUT "/api/v1/golden-sets/$GS" "$BODY"; expect "$GS ($(printf '%s' "$R" | jget 'd.get("cases","?")') cases)" "$R"
done

# ----------------------------------------------------------------------------------------------------------------------
step "7/15 Compliance rules (PUT /api/v1/compliance/rules)"
if [ -f "$DEST/rules/compliance.yaml" ]; then
  BODY=$(python3 -c 'import sys,json; print(json.dumps({"yaml":open(sys.argv[1]).read()}))' "$DEST/rules/compliance.yaml")
  call PUT /api/v1/compliance/rules "$BODY"; expect "compliance rules ($(printf '%s' "$R" | jget 'len(d) if isinstance(d,list) else "?"'))" "$R"
fi

# ----------------------------------------------------------------------------------------------------------------------
step "8/15 Order types (PUT /api/v1/order-types/{name})"
if [ -f "$DEST/order-types.yaml" ]; then
  y2j "$DEST/order-types.yaml" | jq_ '[print(json.dumps(t)) for t in d.get("orderTypes",[])]' | while read -r t; do
    N=$(printf '%s' "$t" | jget 'd["name"]'); call PUT "/api/v1/order-types/$N" "$t"; expect "$N" "$R"
  done
fi

# ----------------------------------------------------------------------------------------------------------------------
step "9/15 Catalog (PUT /api/v1/catalog/{sku})"
if [ -f "$DEST/catalog.yaml" ]; then
  y2j "$DEST/catalog.yaml" | jq_ '
for p in d.get("products",[]):
    attrs={k:v for k,v in p.items() if k not in ("sku","name")}
    print(json.dumps({"sku":p["sku"],"name":p.get("name"),"attributes":attrs}))' | while read -r item; do
    S=$(printf '%s' "$item" | jget 'd["sku"]'); call PUT "/api/v1/catalog/$S" "$item"; expect "$S" "$R"
  done
fi

# ----------------------------------------------------------------------------------------------------------------------
step "10/15 Nodes (PUT /api/v1/nodes/{id})"
if [ -f "$DEST/nodes.yaml" ]; then
  y2j "$DEST/nodes.yaml" | jq_ '[print(json.dumps(n)) for n in d.get("nodes",[])]' | while read -r n; do
    ID=$(printf '%s' "$n" | jget 'd["id"]'); call PUT "/api/v1/nodes/$ID" "$n"; expect "$ID" "$R"
  done
fi

# ----------------------------------------------------------------------------------------------------------------------
step "11/15 Shipping rates (PUT /api/v1/shipping-rates)"
if [ -f "$DEST/shipping-rates.yaml" ]; then call PUT /api/v1/shipping-rates "$(y2j "$DEST/shipping-rates.yaml")"; expect "rate card" "$R"; fi

# ----------------------------------------------------------------------------------------------------------------------
step "12/15 Playbooks (PUT /api/v1/playbooks/{name})"
if [ -f "$DEST/playbooks.yaml" ]; then
  PB=$(y2j "$DEST/playbooks.yaml")
  printf '%s' "$PB" | jq_ '[print(json.dumps({"name":p["name"],"description":p.get("description"),"steps":p.get("steps",[])})) for p in d.get("playbooks",[])]' | while read -r p; do
    N=$(printf '%s' "$p" | jget 'd["name"]'); call PUT "/api/v1/playbooks/$N" "$p"; expect "$N" "$R"
  done
  printf '%s' "$PB" | jq_ 'print("spendLimits" in d)' | grep -q True && warn "playbooks.spendLimits has no write API yet; the seeder path loads it (see the note at the end)"
fi

# ----------------------------------------------------------------------------------------------------------------------
step "13/15 Queues from exception-taxonomy.yaml (PUT /api/v1/queues/{name})"
if [ -f "$DEST/exception-taxonomy.yaml" ]; then
  TX=$(y2j "$DEST/exception-taxonomy.yaml")
  printf '%s' "$TX" | jq_ '[print(json.dumps({"name":q["name"],"defaultSlaMinutes":q.get("slaMinutes"),"escalationQueue":q.get("escalateTo"),"skills":q.get("skills",[])})) for q in d.get("queues",[])]' | while read -r q; do
    N=$(printf '%s' "$q" | jget 'd["name"]'); call PUT "/api/v1/queues/$N" "$q"; expect "$N" "$R"
  done
  printf '%s' "$TX" | jq_ 'print(len(d.get("exceptionTypes",[])))' | grep -qv '^0$' && warn "exceptionTypes have no write API yet; unknown types fall back to the built-in defaults until loaded by the seeder path"
fi

# ----------------------------------------------------------------------------------------------------------------------
step "14/15 SLA definitions (PUT /api/v1/sla-definitions) and agents (POST /api/v1/agents)"
if [ -f "$DEST/sla.yaml" ]; then
  SL=$(y2j "$DEST/sla.yaml")
  printf '%s' "$SL" | jq_ '[print(json.dumps(s)) for s in d.get("slas",[])]' | while read -r s; do
    call PUT /api/v1/sla-definitions "$s"; expect "sla $(printf '%s' "$s" | jget 'd.get("orderType","*")+"/"+d["entity"]+"/"+d["state"]')" "$R"
  done
  printf '%s' "$SL" | jq_ '[print(json.dumps({"orderType":"*","entity":"order_line","state":k,"expectedNextEventWithin":v})) for k,v in (d.get("expectedNextEventWithin") or {}).items()]' | while read -r s; do
    call PUT /api/v1/sla-definitions "$s"; expect "expected next event $(printf '%s' "$s" | jget 'd["state"]')" "$R"
  done
fi
if [ -f "$DEST/agents/agents.yaml" ]; then
  y2j "$DEST/agents/agents.yaml" | jq_ '
for a in d.get("agents",[]):
    body={"id":a["id"],"owner":a.get("owner"),"protocol":a.get("protocol"),"trustTier":a.get("trustTier"),"callbackUrl":a.get("callbackUrl"),
          "limits":{"allowedActions":a.get("allowedActions",[]),"maxOrderValueUsd":a.get("maxOrderValueUsd"),"maxRefundUsd":a.get("maxRefundUsd"),"ratePerMinute":a.get("ratePerMinute"),"confirmActions":a.get("confirmActions",[])}}
    print(json.dumps(body))' | while read -r a; do
    ID=$(printf '%s' "$a" | jget 'd["id"]')
    call GET "/api/v1/agents/$ID"; if [ "$STATUS" = 200 ]; then skip "agent $ID exists"; continue; fi
    call POST /api/v1/agents "$a"; expect "agent $ID" "$R"
    case "$STATUS" in 2*) printf '%s' "$R" | jq_ 'print("    secret (shown once):", d.get("secret")); print("    api key (shown once):", d.get("apiKey"))' ;; esac
  done
fi

# ----------------------------------------------------------------------------------------------------------------------
step "15/15 Inventory (POST /api/v1/inventory/feed)"
if [ "$SKIP_INV" = 1 ]; then skip "skipped (--skip-inventory)"
elif [ -f "$DEST/inventory.yaml" ]; then call POST /api/v1/inventory/feed "$(y2j "$DEST/inventory.yaml")"; expect "inventory ($(printf '%s' "$R" | jget 'd.get("positions","?")') positions)" "$R"
else skip "no inventory.yaml; feed stock through POST /webhooks/inventory/{nodeId}"; fi

# ----------------------------------------------------------------------------------------------------------------------
echo
printf '\033[1mTenant %s ("%s") is loaded.\033[0m\n' "$TENANT" "$NAME"
cat <<TXT

  Log in at $BASE with tenant "$TENANT", user "$AUSER".
  Place an order:   curl -X POST $BASE/api/v1/orders -H "Authorization: Bearer \$(scripts/token.sh $AUSER <password> $TENANT)" ... (docs/GETTING_STARTED.md §3)
  Webhooks:         send X-Tenant-Id: $TENANT and set webhooks.inbound_secret / shopify.webhook_secret via PUT /api/v1/admin/config/{key}
  Users:            POST $BASE/api/v1/auth/users {userId, password, roles}   (or map IdP groups through OIDC)
  Review decisions: $BASE/studio — $( [ "$PUBLISH" = emergency ] && echo "versions went live through the emergency path and are flagged for a 48 h retro review" || echo "publish mode: $PUBLISH" )

  Not loadable through the API yet (loaded only by the seeder): exception-taxonomy.yaml exceptionTypes and
  playbooks.yaml spendLimits. To load everything the way the demo tenant is loaded, start the service once with
      OMS_DEMO_TENANT=$TENANT OMS_DEMO_SEED=true
  (this also creates sample orders and deterministic inventory, so use it for sandboxes, not production).
TXT
