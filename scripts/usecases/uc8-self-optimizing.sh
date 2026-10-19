#!/usr/bin/env bash
# UC8 — Self-optimizing orchestration: real orders produce outcomes, learners turn them into facts and proposals, and a
# learned value reaches live decisions only through a governed change: evaluate on replay, a different person
# approves, promote, measure, roll back. Works on a fresh demo tenant (the self-optimizing reference data is seeded at
# startup) and is safe to re-run. BASE / TENANT as in lib.sh (default http://localhost:8080, tenant demo).
source "$(dirname "$0")/lib.sh"

STRATEGY_TOKEN=$(token strategy strategy)
OPS_TOKEN=$(token ops ops)
TS=$(date +%s)

# call METHOD PATH [JSON] [TOKEN] -> body in $BODY, HTTP status in $CODE
call() {
  local m=$1 p=$2 body=${3:-} t=${4:-$TOKEN} out
  if [ -n "$body" ]; then out=$(curl -s -w $'\n%{http_code}' -X "$m" "$BASE$p" -H "Authorization: Bearer $t" -H "Content-Type: application/json" -H "X-Tenant-Id: $TENANT" -d "$body");
  else out=$(curl -s -w $'\n%{http_code}' -X "$m" "$BASE$p" -H "Authorization: Bearer $t" -H "X-Tenant-Id: $TENANT"); fi
  CODE=${out##*$'\n'}; BODY=${out%$'\n'*}
}
# must CODE...: fail loudly unless the last call returned one of the given statuses
must() {
  for c in "$@"; do [ "$CODE" = "$c" ] && return 0; done
  printf '\033[31m  ✗ expected HTTP %s, got %s: %s\033[0m\n' "$*" "$CODE" "${BODY:0:400}" >&2
  exit 1
}
ok() { printf '\033[32m  ✓ %s\033[0m\n' "$*"; }
field() { printf '%s' "$BODY" | python3 -c "import sys,json; d=json.load(sys.stdin); print($1)"; }

hr; echo "UC8 · Self-optimizing orchestration — outcomes to facts to a governed, reversible change"; hr

step "1) What the system is learning right now"
call GET /api/v1/learning/seed-status "" "$STRATEGY_TOKEN"
if [ "$CODE" = 200 ]; then say "reference data: complete=$(field 'd.get("complete")'), seeded $(field 'd.get("seededAt")')"; fi
call GET /api/v1/learning/learners "" "$STRATEGY_TOKEN"; must 200
printf '%s' "$BODY" | python3 -c '
import sys,json
ls=json.load(sys.stdin)
print("    %d learners · %d at L2 or above · %d paused or switched off" % (len(ls), sum(1 for l in ls if l["autonomyLevel"] in ("L2_ACT_WITH_APPROVAL","L3_AUTONOMOUS")), sum(1 for l in ls if not l["running"])))
for l in ls:
    if not l["running"]: print("    paused:", l["displayName"], "-", l.get("pausedReason") or l.get("gateReason"))'
ok "learner registry readable by the strategy lead"

step "2) Real orders: one that may split across nodes, one paid cash on delivery"
order_body() { # ref customer tenderJson linesJson
  printf '{"channel":"web","channelOrderId":"%s","orderType":"standard_ship","customerRef":"%s","customer":{"ref":"%s","name":"UC8 Shopper","email":"uc8@example.com"},"lines":%s,"addresses":[{"type":"ship_to","name":"UC8 Shopper","line1":"500 Market St, Unit %s","city":"San Francisco","state":"CA","zip":"94105","country":"US"}],"tenders":[%s],"intentChoices":["split_up_to_k"],"shipMethod":"ground"}' "$1" "$2" "$2" "$4" "$2" "$3"
}
SPLIT_LINES='[{"lineId":"1","sku":"TEE-BLK-M","qty":1,"unitPrice":{"amount":28.00,"currency":"USD"}},{"lineId":"2","sku":"BOOT-LTH-9","qty":1,"unitPrice":{"amount":220.00,"currency":"USD"}}]'
call POST /api/v1/orders "$(order_body "UC8-SPLIT-$TS" "cust-uc8-$TS" '{"type":"card","token":"tok_visa_ok","amount":{"amount":320.00,"currency":"USD"}}' "$SPLIT_LINES")"; must 200 201
SPLIT=$(field 'd["id"]'); ok "captured $SPLIT"
COD_LINES='[{"lineId":"1","sku":"HOOD-GRY-M","qty":1,"unitPrice":{"amount":68.00,"currency":"USD"}}]'
call POST /api/v1/orders "$(order_body "UC8-COD-$TS" "cust-uc8cod-$TS" '{"type":"cod","token":"cod_cash","amount":{"amount":95.00,"currency":"USD"}}' "$COD_LINES")"; must 200 201
COD=$(field 'd["id"]'); ok "captured $COD (cash on delivery)"
for _ in $(seq 1 40); do case "$(order_status "$SPLIT")" in released|partially_shipped|shipped|delivered) break ;; esac; sleep 1; done
call GET "/api/v1/orders/$SPLIT/view"; must 200
say "split order: status $(field 'd["order"]["status"]'), $(field 'len(d.get("fulfilmentGroups") or [])') fulfilment group(s) from $(field '", ".join(g.get("nodeId","?") for g in (d.get("fulfilmentGroups") or []))')"
call GET "/api/v1/orders/$COD/view"; must 200
say "COD order: status $(field 'd["order"]["status"]'), holds: $(field '", ".join(h.get("type","?") for h in (d.get("holds") or []) if not h.get("releasedAt")) or "none"')"

step "3) Outcomes arrive: ship and deliver the split order, a customer asks where an order is"
for NODE in $(api GET "/api/v1/orders/$SPLIT/view" | j '" ".join(sorted({g.get("nodeId","DC-NJ") for g in (d.get("fulfilmentGroups") or [{"nodeId":"DC-NJ"}])}))'); do ship_and_deliver "$SPLIT" "$NODE"; done
call GET "/api/v1/orders/$SPLIT/view"; must 200
say "split order now $(field 'd["order"]["status"]')"
call POST "/api/v1/orders/$SPLIT/contacts" "{\"reason\":\"wismo\",\"channel\":\"email\",\"source\":\"uc8\",\"externalId\":\"uc8-wismo-$TS\"}"; must 200 201
ok "WISMO contact recorded (recorded=$(field 'd.get("recorded")'))"
call POST "/api/v1/orders/$SPLIT/contacts" "{\"reason\":\"wismo\",\"channel\":\"email\",\"source\":\"uc8\",\"externalId\":\"uc8-wismo-$TS\"}"; must 200 201
ok "the same contact re-sent is ignored (recorded=$(field 'd.get("recorded")'))"

step "4) A chargeback arrives from Stripe, signed"
SECRET="whsec_uc8_demo"
call PUT /api/v1/admin/config/payments.stripe.webhook_secret "{\"value\":\"$SECRET\",\"reason\":\"uc8 signed webhook demo\"}"; must 200
call GET "/api/v1/orders/$SPLIT/view"; must 200
PSP=$(field '(d.get("payments") or [{}])[0].get("pspRef") or ""')
if [ -z "$PSP" ]; then say "no captured card payment on the split order yet; skipping the chargeback step"; else
  DISPUTE=$(printf '{"id":"evt_uc8_%s","type":"charge.dispute.created","data":{"object":{"id":"dp_uc8_%s","payment_intent":"%s","amount":24800,"currency":"usd","reason":"fraudulent","status":"needs_response"}}}' "$TS" "$TS" "$PSP")
  T=$(date +%s); SIG=$(printf '%s' "$T.$DISPUTE" | openssl dgst -sha256 -hmac "$SECRET" -hex | sed 's/^.*= //')
  OUT=$(curl -s -w $'\n%{http_code}' -X POST "$BASE/webhooks/payments/stripe?tenant=$TENANT" -H "Content-Type: application/json" -H "Stripe-Signature: t=$T,v1=$SIG" -d "$DISPUTE")
  CODE=${OUT##*$'\n'}; BODY=${OUT%$'\n'*}; must 200 201 202
  ok "dispute accepted for payment $PSP"
  BAD=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$BASE/webhooks/payments/stripe?tenant=$TENANT" -H "Content-Type: application/json" -H "Stripe-Signature: t=$T,v1=deadbeef" -d "$DISPUTE")
  [ "$BAD" = 401 ] && ok "a forged signature is refused (401)" || { echo "  ✗ forged signature returned $BAD" >&2; exit 1; }
  call GET "/api/v1/learning/fraud/orders/$SPLIT/labels" "" "$STRATEGY_TOKEN"; must 200
  say "fraud labels on the order's decisions: $(printf '%s' "$BODY" | head -c 300)"
fi

step "5) Learners run on what happened"
for L in band_threshold golden_set_growth override_to_rule inventory_stock_age; do
  call POST "/api/v1/learning/learners/$L/run" "" "$STRATEGY_TOKEN"; must 200
  say "$(field 'd["displayName"]'): $(field '"ran" if d["running"] else "skipped - " + d["gateReason"]')"
done
call POST /api/v1/learning/learners/node_reliability/run "" "$STRATEGY_TOKEN"; must 400 422
ok "node reliability learns from each delivery event, so it has no batch run ($CODE)"
call POST /api/v1/learning/jobs/reward-maturity/run "" "$STRATEGY_TOKEN"; must 200; say "rewards recomputed: $(field 'd["result"]') episode(s)"
call POST /api/v1/learning/jobs/coverage/run "" "$STRATEGY_TOKEN"; must 200; say "outcome coverage: $(field '", ".join("%s %d%%" % (k, round(v*100)) for k,v in d["result"].items())')"
call GET "/api/v1/learning/facts?subjectType=NODE&subjectId=ST-LA-02" "" "$STRATEGY_TOKEN"; must 200
say "ST-LA-02 learned: $(field '", ".join("%s=%s (v%d, %d obs)" % (f["factKey"], f["value"].get("value", f["value"].get("values")), f["version"], f["sampleSize"]) for f in d)')"

step "6) A learned value goes live only through a governed change"
# Try learned families in order. One that is already live, or was rolled back recently (a 30-day cooldown, so a
# reverted change is not re-proposed on the same evidence), is skipped; that keeps this script safe to re-run.
PROP=""
for CANDIDATE in \
  'sourcing.reject_penalty|node_reliability|0.1|2.0|0.5|use the learned, decaying reject penalty per node' \
  'monitoring.warn_early_threshold|warn_early|0.3|0.9|0.1|warn customers about a likely delay at the learned risk level' \
  'promise.buffer_days|promise_buffer|0|3|1|add the learned promise buffer per customer segment' \
  'inventory.reservation_ttl|inventory_reservation_ttl|5|1440|60|hold carts for the learned time before releasing stock'; do
  IFS='|' read -r FAMILY LEARNER LO HI STEP WHY <<< "$CANDIDATE"
  call POST /api/v1/learning/live-parameters/proposals "{\"family\":\"$FAMILY\",\"learnerKey\":\"$LEARNER\",\"min\":$LO,\"max\":$HI,\"maxStep\":$STEP,\"reason\":\"UC8: $WHY\"}" "$STRATEGY_TOKEN"
  if [ "$CODE" = 200 ] || [ "$CODE" = 201 ]; then PROP=$(field 'd["proposalId"]'); break; fi
  if [ "$CODE" = 409 ]; then ok "$FAMILY was rolled back recently or is already live, so it cannot be re-proposed yet (409); trying the next one"; continue; fi
  must 200 201
done
[ -n "$PROP" ] || { printf '\033[31m  ✗ every candidate family is cooling down or live; run on a fresh demo tenant\033[0m\n' >&2; exit 1; }
ok "strategy lead drafted $PROP for $FAMILY ($(field 'd["status"]'))"
call POST "/api/v1/learning/proposals/$PROP/evaluate" "" "$STRATEGY_TOKEN"; must 200
say "replay: method $(field 'd["evaluationReport"].get("method")') · $(field '(d["evaluationReport"].get("reason") or d["evaluationReport"].get("note") or "")[:160]')"
call POST "/api/v1/learning/proposals/$PROP/approve" '{}' "$STRATEGY_TOKEN"; must 400 409 422
ok "the author cannot approve their own change ($CODE)"
call POST "/api/v1/learning/proposals/$PROP/approve" '{}' "$OPS_TOKEN"; must 403
ok "an ops analyst cannot approve learning changes (403)"
call POST "/api/v1/learning/proposals/$PROP/approve" '{}'; must 200
ok "admin approved ($(field 'd["status"]'), by $(field 'd["approvedBy"]'))"
# A live-use family cannot be replayed, so promotion waives full evidence with a written reason (EX-07).
call POST "/api/v1/learning/proposals/$PROP/promote" '{"overrideReason":"Live-use family: no counterfactual replay exists; bounded by range and max step"}'; must 200
ok "promoted ($(field 'd["status"]'))"
call GET /api/v1/learning/live-parameters "" "$STRATEGY_TOKEN"; must 200
say "live families: $(field '", ".join("%s (%s, %s)" % (p["parameter"], p["status"], p["learnerKey"]) for p in d)')"
call GET "/api/v1/learning/proposals/$PROP/card" "" "$STRATEGY_TOKEN"; must 200
say "change card: $(field '"; ".join("%s: %s -> %s" % (x.get("key"), x.get("from"), x.get("to")) for x in (d.get("diff") or []))')"
call GET "/api/v1/learning/lift?days=90" "" "$STRATEGY_TOKEN"; must 200
say "lift vs holdout: $(field '"holdout running" if d.get("holdout") else "no holdout"') · $(field '"; ".join("%s %s" % (m["metric"], ("%+.3f" % m["lift"]) if m.get("lift") is not None else "n/a") for m in (d.get("metrics") or [])[:4])')"

step "7) Roll it back: exactly what it replaced comes back"
call POST "/api/v1/learning/promotions/$PROP/rollback" '{"reason":"UC8 demo: roll back after review"}'; must 200
ok "rolled back ($(field 'd["status"]'))"
call GET /api/v1/learning/live-parameters "" "$STRATEGY_TOKEN"; must 200
say "$FAMILY is now $(FAMILY="$FAMILY" field 'next((p["status"] for p in d if p["parameter"]==__import__("os").environ["FAMILY"]), "absent")')"

step "8) Why did the split order go where it went? (the facts a decision used are on its record)"
REC=$(decision_for "$SPLIT" order sourcing_node_score)
if [ -n "$REC" ]; then
  call GET "/api/v1/decision-records/$REC/why"; must 200
  printf '%s' "$BODY" | python3 -c '
import sys,json
d=json.load(sys.stdin); l=d.get("learning") or {}
facts=l.get("learnedFacts") or []
print("    %s v%s · %d learned fact(s) consulted, %s served" % (d.get("decision"), d.get("version"), len(facts), l.get("learnedFactsServed")))
for f in facts[:6]: print("    ·", f.get("subjectId"), f.get("factKey"), "v%s" % f.get("version"), "learned", f.get("learned"), "served", f.get("served"), "(%s: %s)" % (f.get("source"), f.get("reason")))'
else say "no node-score decision recorded for $SPLIT yet"; fi

echo; say "Open $BASE/learning to see learners, proposals in every status, the canary and holdout, lift, and the objective. Talk track: outcomes become facts on their own; a fact reaches a live decision only after replay, a second person's approval and a promotion that can be rolled back in one click."
