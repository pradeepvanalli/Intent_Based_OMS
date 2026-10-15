#!/usr/bin/env bash
# Lumen & Loom — the apparel reference tenant, scenarios A1–A5 (config/tenants/apparel/README.md).
#   A1 bracketing detected at capture      A2 size exchange from the nearest store      A3 keep-it vs return margin
#   A4 drop oversell handled by policy     A5 final-sale dispute answered with a grounded CS draft
# Runs against a live service (default http://localhost:8080) with the apparel tenant loaded
# (OMS_DEMO_TENANTS=demo,apparel or scripts/new-tenant.sh apparel "Lumen & Loom" --from config/tenants/apparel).
# Login: tenant apparel, user admin / admin (override with TOKEN=... or OMS_ADMIN_USER / OMS_ADMIN_PASSWORD).
# Works without an AI key: the L0 rules carry the clear-cut cases and the heuristic fallback the rest; with
# TYPESAFE_API_KEY set, Jev answers the judgement calls.
export TENANT=${TENANT:-apparel}
export BASE=${BASE:-http://localhost:8080}
ADMIN_USER=${OMS_ADMIN_USER:-admin}; ADMIN_PASS=${OMS_ADMIN_PASSWORD:-admin}
TOKEN=${TOKEN:-$(curl -sf -X POST "$BASE/api/v1/auth/login" -H 'Content-Type: application/json' \
  -d "{\"tenantId\":\"$TENANT\",\"userId\":\"$ADMIN_USER\",\"password\":\"$ADMIN_PASS\"}" | python3 -c 'import sys,json; print(json.load(sys.stdin)["token"])')}
export TOKEN
source "$(dirname "$0")/../usecases/lib.sh"
hr; echo "Lumen & Loom · apparel reference tenant · scenarios A1–A5 against $BASE (tenant $TENANT)"; hr
TS=$(date +%s)
JEV=$(jev_enabled); say "Jev L1 provider available: $JEV"

# ---- helpers ---------------------------------------------------------------------------------------------------------
# mk_order ref orderType custRef tier shipMethod lines(sku:qty:price,...) city state zip line1 note intentJson extJson
mk_order() {
python3 - "$@" <<'PY'
import json, sys
ref, ot, cust, tier, method, lines, city, state, zp, line1, note, intent, ext = sys.argv[1:14]
ls, total = [], 0.0
for i, l in enumerate(lines.split(","), 1):
    sku, qty, price = l.split(":"); qty = int(qty); price = float(price); total += qty * price
    ls.append({"lineId": str(i), "sku": sku, "qty": qty, "unitPrice": {"amount": round(price, 2), "currency": "USD"}})
o = {"channel": "web", "channelOrderId": ref, "orderType": ot, "customerRef": cust,
     "customer": {"ref": cust, "name": cust.replace("-", " ").title(), "email": cust + "@example.com", "tier": tier,
                  "history": {"split_acceptance_rate": 0.4, "returns_12m": 1, "orders_12m": 4}},
     "lines": ls,
     "addresses": [{"type": "ship_to", "name": cust, "line1": line1, "city": city, "state": state, "zip": zp, "country": "US", "addressType": "residential"}],
     "tenders": [{"type": "card", "token": "tok_visa_ok", "amount": {"amount": round(total * 1.15, 2), "currency": "USD"}}],
     "shipMethod": method, "intentChoices": json.loads(intent or "[]"), "extensions": json.loads(ext or "{}")}
if note: o["notes"] = [{"source": "customer", "text": note}]
print(json.dumps(o))
PY
}
place() { api POST /api/v1/orders "$(mk_order "$@")" | j 'd.get("id") or json.dumps(d)'; }
# evaluate decision inputJson subjectType subjectId -> prints the result and the record id on the last line
evaluate() {
  local dec=$1 input=$2 st=$3 sid=$4
  api POST "/api/v1/decisions/$dec/evaluate-now" "{\"input\":$input,\"subjectType\":\"$st\",\"subjectId\":\"$sid\",\"mode\":\"live\"}" | python3 -c "
import sys, json; d = json.load(sys.stdin)
if 'value' not in d: print('    ' + json.dumps(d)[:300]); sys.exit(0)
print('    $dec →', d.get('value'), '| band', d.get('band'), '| layer', d.get('layer'), '| confidence', round(d.get('confidence') or 0, 2), '| provider', d.get('provider'))
[print('    rule fired:', r) for r in (d.get('rulesFired') or [])]
[print('    reason code:', c) for c in (d.get('reasonCodes') or [])]
print('    record', d.get('recordId'))"
}
ret() { # ret orderId channel receipt reasonCode text
  api POST /api/v1/returns "{\"orderId\":\"$1\",\"channel\":\"$2\",\"receiptPresent\":$3,\"lines\":[{\"lineId\":\"1\",\"qty\":1,\"reasonCode\":\"$4\",\"customerReasonText\":\"${5:-}\"}]}" | j 'd.get("rma") or json.dumps(d)'
}
decide() { # decide <rmaId> — a fast fast-lane/keep-it RMA can be auto-resolved by the ReturnWorkflow before this
           # call lands; read the RMA's current (already-decided) state instead of surfacing that race as a 409
  local body; body=$(api POST "/api/v1/returns/$1/decide")
  echo "$body" | python3 -c "import sys,json; sys.exit(0 if 'eligibility' in json.load(sys.stdin) else 1)" 2>/dev/null || body=$(api GET "/api/v1/returns/$1")
  echo "$body" | jq_ '
e=d.get("eligibility") or {}; print("    eligible:", e.get("eligible"), "| reasons:", e.get("reasons"), "| refund timing:", d.get("refundTiming"), "| risk:", d.get("riskScore"), d.get("riskLevel"), "| reason codes:", [c["code"] for c in (d.get("reasonCodes") or [])], "| status:", d.get("status"))
k=d.get("keepIt") or {}
if k.get("offered"): print("    keep-it offered: getting it back would cost $"+str((k.get("returnCost") or {}).get("amount", k.get("returnCostUsd"))), "to recover $"+str((k.get("recovery") or {}).get("amount", k.get("recoveryValueUsd"))), "→ refunded, she keeps the socks")
elif k: print("    keep-it not offered:", k.get("reason"))
r=d.get("refund") or {}
if r: print("    refund:", r.get("status"), (r.get("amount") or {}).get("amount"), "via", r.get("method"))
if d.get("reviewTaskId"): print("    → human task", d.get("reviewTaskId"), "(" + str(d.get("reviewTaskType")) + ") in risk_review: the model recommends, a person decides")'; }
show_plan() { api GET "/api/v1/orders/$1/view" | jq_ '
o=d["order"]; it=o.get("intent") or {}
print("    status:", o.get("status"), "| order type:", o.get("orderType"), "| intent:", it.get("policy"), "(" + str(it.get("source")) + ")")
for g in d.get("fulfilmentGroups") or []: print("      ·", g.get("nodeId"), [(a["sku"], a["qty"]) for a in g.get("allocations", [])], "ETA", g.get("promiseDate"), "status", g.get("status"))
m=o.get("margin") or {}; print("    margin:", str(round((m.get("marginRatio") or 0)*100,1))+"%", "(floor", str(round((m.get("floor") or 0)*100))+"%)")'; }
feed() { api POST /api/v1/inventory/feed "$1" >/dev/null; }
inv_set() { # inv_set sku node=qty node=qty ... (every other node of the network goes to 0)
  local s=$1; shift; local body="" n q
  for n in DC-PA 3PL-LV ST-NYC-SOHO ST-LA-MELROSE ST-CHI-GOLD ST-MIA-DESIGN ST-AUS-SOCO; do
    q=0; for kv in "$@"; do [ "${kv%%=*}" = "$n" ] && q=${kv#*=}; done
    body="$body{\"sku\":\"$s\",\"nodeId\":\"$n\",\"supplyType\":\"on_hand\",\"qty\":$q,\"asOf\":\"2099-01-01T00:00:00Z\"},"
  done
  feed "{\"source\":\"apparel-ref-$TS\",\"positions\":[${body%,}]}"
}
atp_at() { api GET "/api/v1/inventory/$1" | j "next((a['available'] for a in d if a['nodeId']=='$2'), 0)"; }

# ======================================================================================================================
step "A1 · Bracketing detected at capture — two sizes of one style, with and without the tell-tale note"
NOTE_A="Ordering the 28 and the 30, will return the one that doesn't fit."
A=$(place "A1-NOTE-$TS" standard_ship cust-ana-oliveira gold ground "JNS-STR-IND-28:1:118.00,JNS-STR-IND-30:1:118.00" Brooklyn NY 11215 "145 7th Ave" "$NOTE_A" '["ship_complete"]' '{}')
B=$(place "A1-QUIET-$TS" standard_ship cust-priya-shah standard ground "DRS-WRAP-NVY-S:1:128.00,DRS-WRAP-NVY-M:1:128.00" Philadelphia PA 19103 "1800 Market St" "" '[]' '{}')
C=$(place "A1-SINGLE-$TS" standard_ship cust-maya-chen silver ground "TEE-ORG-WHT-M:1:38.00,ACC-SCRF-SLK:1:88.00" Denver CO 80203 "1700 Lincoln St" "Gift for my mum, send everything together." '["ship_complete"]' '{}')
say "orders: A (note) $A · B (no note) $B · C (two styles, gift) $C"; wf_settle 5
say "the capture pipeline stores the lines and the note as data; bracketing_likelihood is evaluated on the order's facts (GR-08: the note is never an instruction)"
brk() { # brk orderId notes style size1 size2 colour adjacentPairs colourPairs bracketingHistory
python3 - "$@" <<'PY'
import json, sys
oid, notes, style, s1, s2, colour, adj, col, hist = sys.argv[1:10]
lines = [{"style": style, "size": s1, "colour": colour, "qty": 1}, {"style": style, "size": s2, "colour": colour, "qty": 1}] if style != "-" else [{"style": "TEE-ORG", "size": "M", "colour": "white", "qty": 1}, {"style": "ACC-SCRF", "size": "OS", "colour": "print", "qty": 1}]
print(json.dumps({"order": {"order_type": "standard_ship", "channel": "web", "notes": notes, "line_count": 2, "lines": lines,
                            "same_style_adjacent_size_pairs": int(adj), "same_style_same_size_colour_pairs": int(col)},
                  "customer": {"tier": "gold", "history": {"orders_12m": 7, "returns_12m": 2, "bracketing_returns_12m": int(hist)}}}))
PY
}
say "A — \"$NOTE_A\""; evaluate bracketing_likelihood "$(brk "$A" "$NOTE_A" JNS-STR 28 30 indigo 1 0 1)" order "$A"
say "B — same style, adjacent sizes, no note (the line pattern alone)"; evaluate bracketing_likelihood "$(brk "$B" "" DRS-WRAP S M navy 1 0 0)" order "$B"
say "C — two different styles with a gift note (control)"; evaluate bracketing_likelihood "$(brk "$C" "Gift for my mum, send everything together." - - - - 0 0 0)" order "$C"
say "Open $BASE/orders/$A → Decisions → Why: the L0 rule explicit_return_note fired, reason code explicit_return_intent_in_note; B scored by the model from the line pattern; C short-circuits on no_repeated_style."

# ======================================================================================================================
step "A2 · Size exchange from the nearest store — the M exists only at LA Melrose, 15 km from the customer"
inv_set DRS-MIDI-BLK-M ST-LA-MELROSE=3
say "DRS-MIDI-BLK-M available: LA Melrose $(atp_at DRS-MIDI-BLK-M ST-LA-MELROSE) · DC-PA $(atp_at DRS-MIDI-BLK-M DC-PA)"
O=$(place "A2-ORIG-$TS" standard_ship cust-camila-reyes gold ground "DRS-MIDI-BLK-S:1:148.00" "Santa Monica" CA 90401 "1299 Ocean Ave" "" '[]' '{}')
wf_settle 6; ship_and_deliver "$O" "$(order_node "$O")"; say "original order $O delivered (S)"
R=$(ret "$O" web true wrong_size "The S is snug across the shoulders, I need the M"); say "RMA $R opened for the S"; decide "$R"
why "$(decision_for "$R" return return_risk)"
XIN=$(python3 -c "import json; print(json.dumps({'exchange': {'original_sku': 'DRS-MIDI-BLK-S', 'new_sku': 'DRS-MIDI-BLK-M', 'reason': 'wrong_size', 'value_usd': 148.0, 'days_since_delivery': 1, 'nearest_store_km': 15, 'nearest_store_atp': $(atp_at DRS-MIDI-BLK-M ST-LA-MELROSE), 'dc_atp': $(atp_at DRS-MIDI-BLK-M DC-PA), 'network_atp': $(atp_at DRS-MIDI-BLK-M ST-LA-MELROSE), 'same_price': True, 'customer_prefers_refund': False}, 'order': {'channel': 'web', 'destination_state': 'CA'}, 'customer': {'tier': 'gold', 'history': {'returns_12m': 2, 'not_received_claims': 0, 'flags': []}}}))")
say "exchange_fulfilment: how does the M reach her?"; evaluate exchange_fulfilment "$XIN" return "$R"
E=$(place "A2-EXCH-$TS" exchange cust-camila-reyes gold same_day "DRS-MIDI-BLK-M:1:148.00" "Santa Monica" CA 90401 "1299 Ocean Ave" "Size exchange, the S is snug across the shoulders." '["ship_complete"]' "{\"exchangeForOrderId\":\"$O\",\"exchangeForRma\":\"$R\",\"exchangeOriginalSku\":\"DRS-MIDI-BLK-S\",\"exchangeType\":\"size_swap\",\"settlement\":\"void_on_return_receipt\"}")
say "exchange order $E (order type exchange, same_day)"; wf_settle 7; show_plan "$E"
say "Open $BASE/orders/$E → the plan sources from ST-LA-MELROSE (the only node with the M); the RMA $R carries the fast-lane reason code. Talk track: an exchange keeps the sale; the T2 decision only ships before the return arrives when the history is ordinary."

# ======================================================================================================================
step "A3 · Keep-it vs return — the margin decision on a \$21 pair of socks and a \$128 dress"
K=$(place "A3-SOCKS-$TS" standard_ship cust-daniela-costa standard ground "ACC-SOCK-ORG:1:21.00" Phoenix AZ 85004 "2 N Central Ave" "" '[]' '{}')
D=$(place "A3-DRESS-$TS" standard_ship cust-ingrid-larsen silver ground "DRS-WRAP-NVY-M:1:128.00" Seattle WA 98101 "400 Pine St" "" '[]' '{}')
wf_settle 6; ship_and_deliver "$K" "$(order_node "$K")"; ship_and_deliver "$D" "$(order_node "$D")"
say "1) \$21 socks, changed mind, receipt present → trusted fast lane (score 0) → keep-it economics: label \$7.95 + handling \$2.50 vs recovery 45 % × \$21"
R1=$(ret "$K" web true changed_mind "Ordered the wrong colour of socks"); decide "$R1"; why "$(decision_for "$R1" return return_risk)"
say "2) \$128 dress, wrong size, receipt present → size_return_fast_lane rule → instant refund, label issued, dress comes back to Columbus"
R2=$(ret "$D" web true wrong_size "Too long in the body, going down a size"); decide "$R2"; why "$(decision_for "$R2" return return_risk)"
say "Customer-safe wording (GR-15):"; api GET "/api/v1/returns/$R1/customer-message" | j 'd.get("text")'
say "Open $BASE/returns → RMA $R1 shows keep-it with the cost/recovery pair; RMA $R2 shows the fast-lane rule. Talk track: the keep-it threshold is three tenant keys (returns.return_shipping_cost_usd, return_handling_cost_usd, recovery_rate), not code."

# ======================================================================================================================
step "A4 · Drop oversell handled by policy — 15 orders for the Drop 04 bomber in minutes, 10 units in stock"
inv_set JKT-BMB-ECR-M DC-PA=10
say "JKT-BMB-ECR-M available at DC-PA: $(atp_at JKT-BMB-ECR-M DC-PA) (nothing anywhere else)"
DROP_IDS=()
CITIES=("Los Angeles|CA|90013|210 W 5th St" "New York|NY|10041|55 Water St" "Austin|TX|78701|600 Congress Ave" "Indianapolis|IN|46204|1 Monument Cir" "San Francisco|CA|94111|101 California St" "Atlanta|GA|30303|191 Peachtree St NE" "Seattle|WA|98101|1201 3rd Ave" "Miami|FL|33131|1000 Brickell Ave" "Chicago|IL|60606|233 S Wacker Dr" "Los Angeles|CA|90071|400 S Hope St" "Chicago|IL|60606|150 N Riverside Plz" "San Francisco|CA|94105|1 Market St" "St. Louis|MO|63102|700 Clark Ave" "Detroit|MI|48226|1 Campus Martius" "Denver|CO|80202|1600 Larimer St")
for i in $(seq 1 15); do
  IFS='|' read -r city st zp l1 <<< "${CITIES[$((i-1))]}"
  note=""; [ "$i" = 4 ] && note="If it's sold out please just cancel, I don't want to wait."; [ "$i" = 9 ] && note="Happy to wait for a restock if needed, just keep my place."
  DROP_IDS+=("$(place "A4-DROP-$i-$TS" standard_ship "cust-drop-$i" standard ground "JKT-BMB-ECR-M:1:325.00" "$city" "$st" "$zp" "$l1" "$note" '["ship_complete"]' '{}')")
done
say "15 orders captured"; wf_settle 12
api GET "/api/v1/orders?q=A4-DROP&limit=50" | jq_ '
from collections import Counter
c=Counter(i.get("status") for i in d.get("items",[]) if i.get("channelOrderId","").startswith("A4-DROP"))
print("    status mix:", dict(c), "| ATP left at DC-PA after allocation: see below")'
say "JKT-BMB-ECR-M available at DC-PA now: $(atp_at JKT-BMB-ECR-M DC-PA)"
drop_in() { python3 -c "import json; print(json.dumps({'drop': {'sku': 'JKT-BMB-ECR-M', 'oversold_units': 5, 'restock_planned': $1, 'restock_eta_days': $2, 'substitute_available': $3, 'substitute_sku': '', 'demand_spike_source': 'influencer'}, 'order': {'channel': 'web', 'notes': '$4', 'placed_minutes_after_drop': $5, 'value_usd': 325.0}, 'customer': {'tier': 'standard', 'history': {'orders_12m': 2}}}))"; }
say "oversold line, restock planned in 12 days → policy rule restock_within_two_weeks"; evaluate drop_oversell_action "$(drop_in True 12 False "" 9)" order "${DROP_IDS[10]}"
say "oversold line, the shopper wrote 'if it is sold out please just cancel' and the next cut is 45 days out → the model's call"; evaluate drop_oversell_action "$(drop_in True 45 False "If it is sold out please just cancel, I do not want to wait." 2)" order "${DROP_IDS[3]}"
say "oversold line, no restock and no other colour → policy rule nothing_to_offer → waitlist"; evaluate drop_oversell_action "$(drop_in False 0 False "" 12)" order "${DROP_IDS[14]}"
say "Open $BASE/orders?q=A4-DROP → ten released at DC-PA, five in sourcing/backordered; $BASE/control-tower shows the inventory_short exceptions. Talk track: the oversell policy is a decision merchandising edits in the studio; the pre_order SLA (sla.yaml) evaluates it automatically after a day."

# ======================================================================================================================
step "A5 · Final-sale dispute answered with a grounded CS draft"
F=$(place "A5-FINAL-$TS" standard_ship cust-sofia-rossi standard ground "DRS-MAXI-FLR-M:1:59.00,ACC-BEAN-WOOL:1:42.00" Boston MA 02116 "500 Boylston St" "" '[]' '{}')
wf_settle 6; ship_and_deliver "$F" "$(order_node "$F")"; say "order $F delivered: a \$59 final-sale maxi dress (was \$148) and a beanie"
MSG="I did not see it was final sale, the colour does not suit me and I want to return the floral dress."
R=$(ret "$F" web true changed_mind "$MSG"); say "RMA $R: return attempt on the final-sale line"; decide "$R"; why "$(decision_for "$R" return return_risk)"
say "The L0 rule final_sale denies before any model runs; the denial is a T3 recommendation: a return_denial task sits in risk_review until a person applies it (ReturnService.applyDenial)."
say "Customer-safe wording (GR-15):"; api GET "/api/v1/returns/$R/customer-message" | j 'd.get("text")'
say "CS reply draft grounded in order facts only (FR-CS-05): the intent is classified, the policy facts come from the order, the model writes the sentence"
api POST /api/v1/copilot/cs/draft "{\"orderId\":\"$F\",\"message\":\"$MSG\"}" | jq_ 'print("    intent:", (d.get("intent") or {}).get("intent"), "| confidence", round(((d.get("intent") or {}).get("confidence") or 0),2)); print("    draft:", (d.get("draft") or "")[:600]); print("    facts used:", d.get("factsUsed")); print("    generated:", d.get("generated"), "| model:", d.get("model"))'
say "Open $BASE/returns → RMA $R (reason code final_sale, task in risk_review) and $BASE/orders/$F → CS panel. Talk track: the policy is a rule the returns team owns; the apology is drafted from the order's facts, never from the model's memory; the beanie stays returnable."

echo; hr; say "Done. Tenant $TENANT · orders tagged with $TS · see config/tenants/apparel/README.md for what each screen shows."
