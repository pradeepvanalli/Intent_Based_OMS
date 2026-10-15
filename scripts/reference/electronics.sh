#!/usr/bin/env bash
# Voltline Electronics reference tenant — scenarios E1–E5 against a running Intent OMS (default http://localhost:8080).
# Requires the `electronics` tenant seeded from config/tenants/electronics (OMS_DEMO_TENANTS=demo,electronics or
# scripts/new-tenant.sh electronics "Voltline Electronics") and the dev user admin/admin on that tenant.
#   E1 launch-day pre-order allocation   E2 high-value address risk hold   E3 backorder next action from a customer note
#   E4 warranty triage and disposition    E5 drop-ship vendor SLA breach re-promise
# Every step prints what to open in the UI. Works without an AI key (heuristic fallback); with TYPESAFE_API_KEY the
# free-text parts (notes, return text) are decided by Jev instead of the checkout choice.
export TENANT=${TENANT:-electronics}
source "$(dirname "$0")/../usecases/lib.sh"
hr; echo "Voltline Electronics · reference scenarios E1–E5  (tenant $TENANT at $BASE)"; hr
JEV=$(jev_enabled); say "Jev L1 provider available: $JEV"
TS=$(date +%s)
ONLY=${1:-all}
run() { [ "$ONLY" = all ] || [ "$ONLY" = "$1" ]; }

addr() { # addr name line1 city state zip [addressType]
  printf '{"type":"ship_to","name":"%s","line1":"%s","city":"%s","state":"%s","zip":"%s","country":"US"%s}' "$1" "$2" "$3" "$4" "$5" "${6:+,\"addressType\":\"$6\"}"
}
line() { printf '{"lineId":"%s","sku":"%s","qty":%s,"unitPrice":{"amount":%s,"currency":"USD"}}' "$1" "$2" "$3" "$4"; }
tender() { printf '{"type":"card","token":"%s","amount":{"amount":%s,"currency":"USD"}}' "${2:-tok_visa_ok}" "$1"; }
cust() { # cust ref name tier orders12m returns12m split_rate
  printf '{"ref":"%s","name":"%s","email":"%s@example.com","tier":"%s","ageVerified":true,"age":35,"history":{"orders_12m":%s,"returns_12m":%s,"split_acceptance_rate":%s}}' "$1" "$2" "$1" "$3" "$4" "$5" "$6"
}
order_view() { api GET "/api/v1/orders/$1/view"; }
show_order() {
  order_view "$1" | jq_ '
o=d["order"]; print("    status:", o.get("status"), "| type:", o.get("orderType"), "| intent:", (o.get("intent") or {}).get("policy"))
for g in d.get("fulfilmentGroups") or []: print("      group", g.get("nodeId"), g.get("status"), [(a["sku"], a["qty"]) for a in g.get("allocations",[])], "promise", g.get("promiseDate"))
for h in d.get("holds") or []: print("      hold:", h.get("type"), h.get("reasonCode"), "blocks", h.get("blocks"), "|", h.get("note"))
for q in d.get("lines") or []:
    qs = q.get("quantities") or []
    if qs: print("      line", q.get("lineId"), q.get("sku"), [(x["state"], x["qty"]) for x in qs])'
}
evaluate() { # evaluate decision subjectType subjectId inputJson → prints the why line
  api POST "/api/v1/decisions/$1/evaluate-now" "{\"input\":$4,\"subjectType\":\"$2\",\"subjectId\":\"$3\",\"mode\":\"LIVE\"}" | jq_ '
print("    decision", d.get("decision"), "| layer", d.get("layer"), "| band", d.get("band"), "| action", d.get("action"), "| value", d.get("value"), "| confidence", round(d.get("confidence") or 0,2), "| provider", d.get("provider"))
[print("    rule fired:", r) for r in (d.get("rulesFired") or [])]
[print("    reason code:", c) for c in (d.get("reasonCodes") or [])]
print("    record:", d.get("recordId"), "→ open", "'"$BASE"'/decisions/records/" + str(d.get("recordId")))'
}
tasks_in() { api GET "/api/v1/tasks?queue=$1&status=OPEN&limit=50" | jq_ 'items=(d.get("items") if isinstance(d,dict) else d) or []; print("    open tasks in '"$1"':", len(items)); [print("      ·", t.get("id"), t.get("subjectType"), t.get("subjectId"), (t.get("payload") or {}).get("recommendation","")) for t in items[:6]]'; }

# ======================================================================================================================
if run E1; then
hr; echo "E1 · Launch-day pre-order allocation — 12 orders in 14 minutes, 10 units in wave 1, one gold member arrives late"; hr
step "Wave 1 stock: exactly 10 Nova X2 on hand at DC-MEM, 350 more on ASN (not sellable: inventory.include_inbound=false)"
api POST /api/v1/inventory/feed '{"source":"ref-e1","positions":[
 {"sku":"PHN-NOVA-X2-256","nodeId":"DC-MEM","supplyType":"on_hand","qty":10,"asOf":"2099-01-01T00:00:00Z"},
 {"sku":"PHN-NOVA-X2-256","nodeId":"DC-SPK","supplyType":"on_hand","qty":0,"asOf":"2099-01-01T00:00:00Z"},
 {"sku":"PHN-NOVA-X2-256","nodeId":"DC-MEM","supplyType":"asn","qty":200,"asOf":"2099-01-01T00:00:00Z"}]}' >/dev/null
api GET /api/v1/inventory/PHN-NOVA-X2-256 | jq_ 'print("    Nova X2 available by node:", {a["nodeId"]: a["available"] for a in d if a.get("available")}, "| inbound:", {a["nodeId"]: a.get("inbound") for a in d if a.get("inbound")})'

step "12 pre-orders arrive (channelOrderId VL-E1-<n>-$TS); the 12th is a gold member 14 minutes after launch"
declare -a PO=()
for n in $(seq 1 12); do
  TIER=standard; ORD=2; NOTE="Pre-ordering the Nova X2 at launch."
  if [ "$n" -eq 12 ]; then TIER=gold; ORD=11; NOTE="Gold member since 2021, pre-ordered the minute I saw the email. Please do not let me miss the first batch."; fi
  if [ "$n" -eq 5 ]; then TIER=gold; ORD=9; fi
  ID=$(api POST /api/v1/orders "{\"channel\":\"web\",\"channelOrderId\":\"VL-E1-$n-$TS\",\"orderType\":\"pre_order\",\"customerRef\":\"cust-e1-$n\",
    \"customer\":$(cust "cust-e1-$n" "Launch Buyer $n" $TIER $ORD 0 0.3),
    \"lines\":[$(line 1 PHN-NOVA-X2-256 1 1099.00)],
    \"addresses\":[$(addr "Launch Buyer $n" "$((100+n)) Congress Ave" Austin TX 78701 residential)],
    \"tenders\":[$(tender 1175.93)],\"intentChoices\":[\"ship_complete\"],\"shipMethod\":\"ground\",
    \"notes\":[{\"source\":\"customer\",\"text\":\"$NOTE\"}],
    \"extensions\":{\"launch\":{\"campaign\":\"nova-x2-launch\",\"waveRequested\":1}}}" | j 'd.get("id") or json.dumps(d)')
  PO[$n]=$ID; printf '    #%-2s %s %s\n' "$n" "$TIER" "$ID"
done
wf_settle 10
step "Who got wave 1? Orders 1–10 release from DC-MEM; 11 and 12 land in backordered (plan.result no_feasible_node)"
for n in 1 10 11 12; do echo "  order #$n"; show_order "${PO[$n]}"; done

step "The launch desk asks preorder_allocation (T2) about the two waiting orders — fair allocation by order time, loyalty protection by rule"
alloc_input() { # alloc_input rank tier orders12m
  printf '{"order":{"launch_rank":%s,"qty":1,"minutes_after_launch":%s,"channel":"web","payment_status":"authorized","notes":""},"customer":{"tier":"%s","history":{"orders_12m":%s,"preorders_cancelled_12m":0},"orders_in_launch":1},"allocation":{"wave_units":10,"wave_units_remaining":0,"protected_units_remaining":2,"protected_tiers":["gold"],"next_wave_eta_days":14}}' "$1" "$(( $1 + 2 ))" "$2" "$3"
}
say "order #11 (standard tier, rank 11): wave is gone, no protection"
evaluate preorder_allocation order "${PO[11]}" "$(alloc_input 11 standard 2)"
say "order #12 (gold, rank 12, 14 minutes late): rule loyalty_protection → allocate_now from the protected units"
evaluate preorder_allocation order "${PO[12]}" "$(alloc_input 12 gold 11)"
step "Apply the answer: one protected unit becomes available and order #12's line leaves backordered; #11 keeps its place for wave 2"
api POST /api/v1/inventory/feed "{\"source\":\"ref-e1-protected\",\"positions\":[{\"sku\":\"PHN-NOVA-X2-256\",\"nodeId\":\"DC-MEM\",\"supplyType\":\"on_hand\",\"qty\":1,\"asOf\":\"2099-01-01T00:00:01Z\"}]}" >/dev/null
api POST "/api/v1/orders/${PO[12]}/notes" '{"text":"Launch desk: allocated from loyalty-protected units (preorder_allocation rule loyalty_protection).","source":"ops","author":"launch-desk"}' >/dev/null
wf_settle 8; echo "  order #12"; show_order "${PO[12]}"; echo "  order #11"; show_order "${PO[11]}"
say "Open $BASE/orders/${PO[12]} (Decisions → Why on preorder_allocation: layer L0_RULE, rule loyalty_protection) and $BASE/orders/${PO[11]} (backordered, SLA P7D → backorder_next_action)."
say "Talk track: allocation is a governed T2 decision — fairness by order time and loyalty protection are rules the merchandising team edits in the studio; the model only sees the ambiguous tail."
fi

# ======================================================================================================================
if run E2; then
hr; echo "E2 · High-value address risk hold — a \$2,399 export-controlled laptop to a freight forwarder"; hr
step "Capture: Forge 16 laptop, two-day, ship-to is a Doral FL forwarder, note \"ship to my forwarder, will re-export\""
H=$(api POST /api/v1/orders "{\"channel\":\"web\",\"channelOrderId\":\"VL-E2-$TS\",\"orderType\":\"standard_ship\",\"customerRef\":\"cust-e2-$TS\",
  \"customer\":$(cust "cust-e2-$TS" "R. Albrecht" standard 0 0 0.5),
  \"lines\":[$(line 1 LAP-FORGE-16 1 2399.00)],
  \"addresses\":[$(addr "R. Albrecht c/o SkyBridge Freight Forwarding" "8400 NW 36th St Suite 450 Box 11923" Doral FL 33166 freight_forwarder)],
  \"tenders\":[$(tender 2542.94)],\"intentChoices\":[\"ship_complete\"],\"shipMethod\":\"two_day\",
  \"notes\":[{\"source\":\"customer\",\"text\":\"ship to my forwarder, will re-export\"}]}" | j 'd.get("id") or json.dumps(d)')
say "order $H"; wf_settle 6
step "Compliance (L0) already holds it: EXPORT_FORWARDER_REVIEW blocks release; adult signature required (HIGH_VALUE_ADULT_SIGNATURE)"
show_order "$H"
step "high_value_address_risk (T2 Score 0–4): forwarder match + export-controlled item is a rule, not a judgement"
evaluate high_value_address_risk order "$H" '{"address":{"verification_status":"unverified","type":"freight_forwarder","freight_forwarder_match":true,"po_box":false,"prior_failed_deliveries":0,"matches_billing":false},"order":{"total":2542.94,"ship_method":"two_day","notes":"ship to my forwarder, will re-export","export_controlled":true,"item_categories":["laptop"]},"customer":{"history":{"orders_12m":0,"chargebacks_12m":0},"account_age_days":1},"payment":{"avs_match":true}}'
step "Score 4 → playbook high_value_review: address_risk hold + risk_review task (run here through the exception API)"
X=$(api POST /api/v1/exceptions "{\"source\":\"cs\",\"code\":\"HIGH_VALUE_ADDRESS\",\"type\":\"high_value_address\",\"orderId\":\"$H\",\"message\":\"high_value_address_risk scored 4: freight forwarder, re-export note, export-controlled laptop\",\"details\":{\"score\":4,\"decision\":\"high_value_address_risk\"}}" | j 'd.get("id") or json.dumps(d)')
say "exception $X"; wf_settle 4
api GET "/api/v1/exceptions/$X" | jq_ 'print("    exception type:", d.get("type"), "| severity:", d.get("severity"), "| queue:", d.get("queue"), "| playbook:", d.get("playbook"), "| status:", d.get("status"))'
tasks_in risk_review
show_order "$H"
step "Same laptop, same price, to a verified home address of a long-standing customer: score 0, released without a hold"
evaluate high_value_address_risk order "$H-control" '{"address":{"verification_status":"verified","type":"residential","freight_forwarder_match":false,"po_box":false,"prior_failed_deliveries":0,"matches_billing":true},"order":{"total":2542.94,"ship_method":"two_day","notes":"","export_controlled":true,"item_categories":["laptop"]},"customer":{"history":{"orders_12m":6,"chargebacks_12m":0},"account_age_days":900},"payment":{"avs_match":true}}'
say "Open $BASE/orders/$H: Holds card (compliance EXPORT_FORWARDER_REVIEW, address_risk), Decisions → Why, and the risk_review queue at $BASE/tasks?queue=risk_review."
say "Talk track: the legal part is an L0 compliance rule; the judgement part is a T2 score with act ≥ 0.9 — a person releases, and the trace shows exactly why it stopped."
fi

# ======================================================================================================================
if run E3; then
hr; echo "E3 · Backorder next action from a customer note — the Aero 14 is sold out, 60 on PO, ETA nine days"; hr
step "Make the Aero 14 unavailable everywhere (PO inbound only)"
api POST /api/v1/inventory/feed '{"source":"ref-e3","positions":[
 {"sku":"LAP-AERO-14","nodeId":"DC-MEM","supplyType":"on_hand","qty":0,"asOf":"2099-01-01T00:00:00Z"},
 {"sku":"LAP-AERO-14","nodeId":"DC-SPK","supplyType":"on_hand","qty":0,"asOf":"2099-01-01T00:00:00Z"},
 {"sku":"LAP-AERO-14","nodeId":"DC-MEM","supplyType":"po","qty":60,"asOf":"2099-01-01T00:00:00Z"}]}' >/dev/null
bo() { # bo ref tier note intentChoice extraLinesJson
  api POST /api/v1/orders "{\"channel\":\"web\",\"channelOrderId\":\"$1-$TS\",\"orderType\":\"backorder\",\"customerRef\":\"cust-$1\",
    \"customer\":$(cust "cust-$1" "Backorder Buyer" "$2" 7 0 0.3),
    \"lines\":[$(line 1 LAP-AERO-14 1 1299.00)$5],
    \"addresses\":[$(addr "Backorder Buyer" "1550 Market St Apt 601" Denver CO 80202 residential)],
    \"tenders\":[$(tender 1500.00)],\"intentChoices\":[\"$4\"],\"shipMethod\":\"ground\",
    \"notes\":[{\"source\":\"customer\",\"text\":\"$3\"}]}" | j 'd.get("id") or json.dumps(d)'
}
step "Order A — note: \"happy to wait, don't substitute\" (gold)"
A=$(bo VL-E3-WAIT gold "happy to wait, don't substitute" ship_complete "")
step "Order B — note: \"need it by Friday or cancel\" (silver)"
B=$(bo VL-E3-FRIDAY silver "need it by Friday or cancel" ship_as_available "")
step "Order C — note: \"send whatever is in stock now, the laptop can follow\" (laptop + sleeve + cable)"
C=$(bo VL-E3-SPLIT standard "send whatever is in stock now, the laptop can follow" ship_as_available ",$(line 2 CAS-AERO-SLV 1 39.00),$(line 3 CBL-TB4-1M 1 59.00)")
say "orders: A=$A  B=$B  C=$C"; wf_settle 8
for o in $A $B $C; do echo "  $o"; show_order "$o"; done
step "The backordered SLA (P7D) would ask backorder_next_action on breach; ask it now with the same line context"
bo_input() { printf '{"line":{"sku":"LAP-AERO-14","category":"laptop","days_backordered":7,"expected_restock_days":9,"substitute_available":true,"substitute_sku":"LAP-FORGE-16"},"order":{"channel":"web","intent":"%s","notes":"%s","other_lines_in_stock":%s,"needed_by_days":%s},"customer":{"tier":"%s"},"network":{"atp_elsewhere":0}}' "$1" "$2" "$3" "$4" "$5"; }
say "A: the customer's words are policy → rule customer_declined_substitute → wait"
evaluate backorder_next_action order_line "$A:1" "$(bo_input ship_complete "happy to wait, don't substitute" 0 -1 gold)"
say "B: Friday is in 3 days, the PO lands in 9 → rule deadline_already_missed → cancel (refund, line released)"
evaluate backorder_next_action order_line "$B:1" "$(bo_input ship_as_available "need it by Friday or cancel" 0 3 silver)"
say "C: two lines are in stock and the buyer accepts partials → split (model, T1 act ≥ 0.8)"
evaluate backorder_next_action order_line "$C:1" "$(bo_input ship_as_available "send whatever is in stock now, the laptop can follow" 2 -1 standard)"
step "Apply B: cancel the line with the decision as the reason"
api POST "/api/v1/orders/$B/cancel" '{"reason":"backorder_next_action: eta_misses_customer_deadline (need it by Friday or cancel)"}' | jq_ 'print("    cancel →", d.get("status") or d)'
wf_settle 4; echo "  order B"; show_order "$B"
say "Open $BASE/orders/$A, $B, $C → Decisions → Why. Talk track: 'wait / substitute / cancel / split' is a typed answer with the customer's own words as L0 rules; the model only decides when nobody said anything."
fi

# ======================================================================================================================
if run E4; then
hr; echo "E4 · Warranty triage and disposition — headphones, \"left ear cuts out\""; hr
step "Capture, ship and deliver Nova ANC Pro headphones (serial in order extensions)"
W=$(api POST /api/v1/orders "{\"channel\":\"web\",\"channelOrderId\":\"VL-E4-$TS\",\"orderType\":\"standard_ship\",\"customerRef\":\"cust-e4-$TS\",
  \"customer\":$(cust "cust-e4-$TS" "Felix Moreau" standard 1 0 0.5),
  \"lines\":[$(line 1 HDP-ANC-PRO 1 349.00)],
  \"addresses\":[$(addr "Felix Moreau" "2800 N Lake Shore Dr Apt 3312" Chicago IL 60657 residential)],
  \"tenders\":[$(tender 373.43)],\"intentChoices\":[\"ship_complete\"],\"shipMethod\":\"ground\",
  \"extensions\":{\"serials\":{\"1\":[\"NAP-2026-0831-118204\"]}}}" | j 'd.get("id") or json.dumps(d)')
say "order $W"; wf_settle 6
NODE=$(order_node "$W"); ship_and_deliver "$W" "$NODE"; wf_settle 4
show_order "$W"
step "Customer opens the RMA: reason defective, text \"left ear cuts out\""
RMA=$(api POST /api/v1/returns "{\"orderId\":\"$W\",\"channel\":\"web\",\"receiptPresent\":true,\"lines\":[{\"lineId\":\"1\",\"qty\":1,\"reasonCode\":\"defective\",\"customerReasonText\":\"left ear cuts out\"}]}" | j 'd.get("rma") or json.dumps(d)')
say "rma $RMA"; wf_settle 3
# a fast fast-lane/keep-it RMA can be auto-resolved by the ReturnWorkflow before this call lands; read the RMA's
# current (already-decided) state instead of surfacing that race as a 409
DBODY=$(api POST "/api/v1/returns/$RMA/decide" '')
echo "$DBODY" | python3 -c "import sys,json; sys.exit(0 if 'eligibility' in json.load(sys.stdin) else 1)" 2>/dev/null || DBODY=$(api GET "/api/v1/returns/$RMA")
echo "$DBODY" | jq_ 'e=d.get("eligibility") or {}; print("    eligible:", e.get("eligible"), "| status:", d.get("status"), "| refund timing:", d.get("refundTiming"), "| risk:", d.get("riskLevel") or d.get("riskScore"), "| reason codes:", [c["code"] for c in (d.get("reasonCodes") or [])])'
REC=$(decision_for "$RMA" return return_risk); [ -n "$REC" ] && why "$REC"
step "Carrier scan, receipt at the Louisville refurbishment centre, bench inspection"
api POST "/api/v1/returns/$RMA/scan" '' >/dev/null
api POST "/api/v1/returns/$RMA/receive" '{"nodeId":"RFB-LOU"}' | jq_ 'print("    received:", d.get("status"))'
step "warranty_triage (T1): the customer's words, the shipment history and what the bench sees"
evaluate warranty_triage return "$RMA" '{"return":{"customer_reason_text":"left ear cuts out","reason_code":"defective","days_since_delivery":12,"item_condition":"like_new","box_opened":true,"inspection_notes":"intermittent left channel reproduced on bench"},"item":{"category":"audio","serial_matches_order":true},"shipment":{"carrier_exception":false,"oversized":false}}'
api POST "/api/v1/returns/$RMA/inspect" '{"condition":"like_new","note":"triage=defective; intermittent left channel reproduced on bench, firmware current, serial NAP-2026-0831-118204 matches order"}' | jq_ 'print("    inspected:", d.get("status"), "| condition:", d.get("condition"))'
wf_settle 3
step "return_disposition (T1): defective + vendor warranty eligible → return_to_vendor (RTV claim), full refund, no restocking fee"
api POST "/api/v1/returns/$RMA/disposition" '' | jq_ 'print("    disposition:", d.get("disposition"), "| source:", d.get("dispositionSource") or "", "| status:", d.get("status"))'
REC=$(decision_for "$RMA" return return_disposition); [ -n "$REC" ] && why "$REC"
api GET "/api/v1/returns/$RMA/customer-message" | jq_ 'print("    customer-safe message:", d.get("message") or d)'
step "Contrast: the same question for a sealed-box \"changed my mind\" phone and a crushed TV with a carrier exception on file"
evaluate warranty_triage return "$RMA-remorse" '{"return":{"customer_reason_text":"changed my mind, box unopened","reason_code":"changed_mind","days_since_delivery":3,"item_condition":"new","box_opened":false,"inspection_notes":""},"item":{"category":"phone","serial_matches_order":true},"shipment":{"carrier_exception":false,"oversized":false}}'
evaluate warranty_triage return "$RMA-transit" '{"return":{"customer_reason_text":"screen cracked, box was crushed on arrival","reason_code":"damaged_in_transit","days_since_delivery":2,"item_condition":"damaged","box_opened":true,"inspection_notes":"panel cracked, carton crushed on one side"},"item":{"category":"tv","serial_matches_order":true},"shipment":{"carrier_exception":true,"oversized":true}}'
say "Open $BASE/returns/$RMA (timeline, Decisions → Why on return_risk, warranty_triage and return_disposition) and the vendor_ops queue for the RTV claim."
say "Talk track: triage is a typed choice; 'buyer's remorse' carries the restocking fee, 'defective' raises the RTV claim, 'transit damage' files the carrier claim — each with reason codes CS can read out."
fi

# ======================================================================================================================
if run E5; then
hr; echo "E5 · Drop-ship vendor SLA breach re-promise — a 65in OLED from Lumen Display"; hr
step "Capture a drop-ship TV order (sources to the vendor node VND-LUMEN; 48 h vendor ship SLA on released)"
V=$(api POST /api/v1/orders "{\"channel\":\"web\",\"channelOrderId\":\"VL-E5-$TS\",\"orderType\":\"drop_ship\",\"customerRef\":\"cust-e5-$TS\",
  \"customer\":$(cust "cust-e5-$TS" "Nathan Price" silver 3 0 0.5),
  \"lines\":[$(line 1 TV-OLED-65 1 1799.00),$(line 2 WTY-TV-4Y 1 199.00)],
  \"addresses\":[$(addr "Nathan Price" "4100 Spruce St" Philadelphia PA 19104 residential)],
  \"tenders\":[$(tender 2117.88)],\"intentChoices\":[\"ship_complete\"],\"shipMethod\":\"ground\",
  \"extensions\":{\"warranty\":{\"plan\":\"WTY-TV-4Y\",\"coveredLineId\":\"1\"}}}" | j 'd.get("id") or json.dumps(d)')
say "order $V"; wf_settle 6; show_order "$V"
step "52 hours later the vendor's ASN has not arrived: the SLA sweeper would raise this; here the vendor feed reports it"
X=$(api POST /api/v1/exceptions "{\"source\":\"wms\",\"code\":\"VENDOR_SHIP_SLA_MISSED\",\"type\":\"vendor_sla_breach\",\"orderId\":\"$V\",\"nodeId\":\"VND-LUMEN\",\"message\":\"Lumen Display EDI 856 not received 52 hours after release; vendor ship SLA is 48 hours, revised ship date +5 days\",\"details\":{\"hoursSinceRelease\":52,\"vendorRevisedShipDays\":5}}" | j 'd.get("id") or json.dumps(d)')
say "exception $X"; wf_settle 5
api GET "/api/v1/exceptions/$X" | jq_ 'print("    type:", d.get("type"), "| severity:", d.get("severity"), "| queue:", d.get("queue"), "| playbook:", d.get("playbook"), "| status:", d.get("status")); [print("      step", s.get("action"), "→", s.get("status"), s.get("note") or "") for s in (d.get("playbookSteps") or d.get("steps") or [])]'
step "Playbook vendor_repromise: new promise from the vendor's revised date, customer told, vendor_ops task, Slack ping"
show_order "$V"; tasks_in vendor_ops
step "sla_breach_risk for the order as the control tower sees it (vendor past its SLA → 4)"
evaluate sla_breach_risk order "$V" '{"order":{"state":"released","order_type":"drop_ship","hours_in_state":52,"promise_hours_remaining":20,"ship_method":"ground"},"node":{"type":"vendor","backlog_units":180,"reject_rate":0.02,"minutes_to_cutoff":-600},"carrier":{"on_time_rate_7d":0.93}}'
step "If the vendor slips another day the playbook re-sources (minConfidence 0.8, exception.age_minutes > 1440): the 43in LED and 27in monitor are also stocked in our DCs, the 65in OLED is vendor-only — so the re-promise, not a re-source, is the honest answer here"
api GET /api/v1/inventory/TV-OLED-65 | jq_ 'print("    TV-OLED-65 available by node:", {a["nodeId"]: a["available"] for a in d if a.get("available")})'
say "Open $BASE/orders/$V (promise card shows the re-promise and the delay notice), $BASE/exceptions/$X (playbook steps), the vendor_ops queue."
say "Talk track: the vendor SLA is configuration (sla.yaml drop_ship released PT48H → playbook.vendor_repromise); nobody is watching an EDI inbox."
fi
hr; echo "Done. Tenant $TENANT · scenarios run: $ONLY"; hr
