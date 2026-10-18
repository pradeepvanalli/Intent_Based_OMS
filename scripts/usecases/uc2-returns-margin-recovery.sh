#!/usr/bin/env bash
# UC2 — Returns as margin recovery (PRD P1, FR-RET-01..05): four customers return the same way, four different outcomes.
# Rules handle the clear-cut ends (trusted fast lane, flagged/repeat not-received), Jev scores the judgement calls,
# a person sees only what neither is sure about — and every outcome carries reason codes CS can read out.
source "$(dirname "$0")/lib.sh"
hr; echo "UC2 · Returns as margin recovery — refund timing, keep-it and abuse containment"; hr
TS=$(date +%s)

buy_and_deliver() { # buy_and_deliver <ref> <customerRef> <tier> <sku> <price> [channel]
  local ref=$1 cust=$2 tier=$3 sku=$4 price=$5 ch=${6:-web}
  local oid
  oid=$(api POST /api/v1/orders "{\"channel\":\"$ch\",\"channelOrderId\":\"$ref\",\"orderType\":\"standard_ship\",\"customerRef\":\"$cust\",
   \"customer\":{\"ref\":\"$cust\",\"name\":\"$cust\",\"email\":\"$cust@example.com\",\"tier\":\"$tier\"},
   \"lines\":[{\"lineId\":\"1\",\"sku\":\"$sku\",\"qty\":1,\"unitPrice\":{\"amount\":$price,\"currency\":\"USD\"}}],
   \"addresses\":[{\"type\":\"ship_to\",\"name\":\"$cust\",\"line1\":\"1 Market St, Unit $cust\",\"city\":\"San Francisco\",\"state\":\"CA\",\"zip\":\"94105\",\"country\":\"US\"}],
   \"tenders\":[{\"type\":\"card\",\"token\":\"tok_visa_ok\",\"amount\":{\"amount\":$(python3 -c "print(round($price*1.2,2))"),\"currency\":\"USD\"}}],\"shipMethod\":\"ground\"}" | j 'd.get("id") or json.dumps(d)')
  sleep 4; ship_and_deliver "$oid" "$(order_node "$oid")"; echo "$oid"
}
ret() { # ret <orderId> <channel> <receipt> <reasonCode> [text]
  api POST /api/v1/returns "{\"orderId\":\"$1\",\"channel\":\"$2\",\"receiptPresent\":$3,\"lines\":[{\"lineId\":\"1\",\"qty\":1,\"reasonCode\":\"$4\",\"customerReasonText\":\"${5:-}\"}]}" | j 'd.get("rma") or json.dumps(d)'
}
decide() { # decide <rmaId> — a fast fast-lane/keep-it RMA can be auto-resolved by the ReturnWorkflow before this
           # call lands; read the RMA's current (already-decided) state instead of surfacing that race as a 409
  local body; body=$(api POST "/api/v1/returns/$1/decide")
  echo "$body" | python3 -c "import sys,json; sys.exit(0 if 'eligibility' in json.load(sys.stdin) else 1)" 2>/dev/null || body=$(api GET "/api/v1/returns/$1")
  echo "$body" | jq_ '
e=d.get("eligibility") or {}; print("    eligible:", e.get("eligible"), "| refund timing:", d.get("refundTiming"), "| risk score:", d.get("riskScore"), d.get("riskLevel"), "| reason codes:", [c["code"] for c in (d.get("reasonCodes") or [])], "| status:", d.get("status"))
k=d.get("keepIt") or {}
if k.get("offered"): print("    keep-it offered: return would cost $"+str(k.get("returnCost",{}).get("amount", k.get("returnCostUsd"))), "to recover $"+str(k.get("recovery",{}).get("amount", k.get("recoveryValueUsd"))), "→ refunded, customer keeps the item")
r=d.get("refund") or {}; print("    refund:", r.get("status"), (r.get("amount") or {}).get("amount"), "via", r.get("method"), "| legs:", len(r.get("legs") or []))
if d.get("reviewTaskId"): print("    → human review task", d.get("reviewTaskId"), "(type", d.get("reviewTaskType")+")")'; }

step "Data setup: four customers buy and receive their orders (WMS ship → carrier delivered)"
O_GOLD=$(buy_and_deliver "UC2-GOLD-$TS" "cust-gold-1" gold JEAN-IND-32 128.00);     say "gold customer, first return this year → $O_GOLD"
O_MUG=$(buy_and_deliver "UC2-MUG-$TS" "cust-mug-9" silver MUG-CER 18.00);          say "silver customer, \$18 mug → $O_MUG"
say "serial returner cust-serial-42: two earlier 'never arrived' claims from the store channel this year…"
P1=$(buy_and_deliver "UC2-SER-A-$TS" "cust-serial-42" bronze TEE-BLK-M 28.00 pos); R1=$(ret "$P1" store false not_received "never got it"); api POST "/api/v1/returns/$R1/decide" >/dev/null
P2=$(buy_and_deliver "UC2-SER-B-$TS" "cust-serial-42" bronze SOCK-3PK 36.00 pos); R2=$(ret "$P2" store false not_received "package missing again"); api POST "/api/v1/returns/$R2/decide" >/dev/null
O_SER=$(buy_and_deliver "UC2-SER-C-$TS" "cust-serial-42" bronze JEAN-IND-32 128.00); say "…and now returns a \$128 pair of jeans online → $O_SER"
say "cust-mid-5: two ordinary returns earlier this year (changed mind), nothing suspicious — but no longer a first-timer…"
M1=$(buy_and_deliver "UC2-MID-A-$TS" "cust-mid-5" silver TEE-WHT-L 28.00); RM1=$(ret "$M1" web true changed_mind "did not need it"); api POST "/api/v1/returns/$RM1/decide" >/dev/null
M2=$(buy_and_deliver "UC2-MID-B-$TS" "cust-mid-5" silver CAP-NVY 32.00); RM2=$(ret "$M2" web true changed_mind "wrong colour"); api POST "/api/v1/returns/$RM2/decide" >/dev/null
O_MID=$(buy_and_deliver "UC2-MID-$TS" "cust-mid-5" silver JEAN-IND-32 128.00);      say "…and now a third return, \$128 jeans, 'not as described' → $O_MID"

step "1) Gold customer, receipt present, first return → trusted fast lane (L0 rule) → INSTANT refund to the original card"
R=$(ret "$O_GOLD" web true wrong_size "too small"); decide "$R"; why "$(decision_for "$R" return return_risk)"
step "2) \$18 mug → return shipping + handling cost more than the item is worth → KEEP-IT refund, no reverse logistics"
R=$(ret "$O_MUG" web true changed_mind "did not like the colour"); decide "$R"
step "3) Serial returner with repeated 'not received' claims across store + online → STORE CREDIT ONLY, with reason codes CS can explain"
R=$(ret "$O_SER" web false not_received "it never arrived"); decide "$R"; why "$(decision_for "$R" return return_risk)"
say "cross-channel history used by the decision:"; api GET "/api/v1/returns?customerRef=cust-serial-42&limit=10" | jq_ 'print("    returns on file for cust-serial-42:", d.get("total"), [ (i.get("channel"), i.get("refundTiming")) for i in d.get("items",[]) ])'
step "4) The judgement call: mixed history, receipt present → Jev abuse-risk Score (T2) decides the timing; without Jev it goes to a reviewer"
R=$(ret "$O_MID" web true not_as_described "colour differs from the photos"); decide "$R"; why "$(decision_for "$R" return return_risk)"
step "Customer-safe wording (GR-15): typed slots only, no risk data"
api GET "/api/v1/returns/$R/customer-message" | j 'd.get("text")'

echo; say "Open $BASE/returns → each RMA shows score pips, reason-code chips and the Why. Talk track: refund timing is a decision object; the business edits the fast-lane rules in the studio, Jev scores the middle, and the abuse signal is cross-channel."
