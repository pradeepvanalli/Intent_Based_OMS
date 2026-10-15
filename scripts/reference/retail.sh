#!/usr/bin/env bash
# Maple & Main General Store (tenant `retail`) — the five reference scenarios against a running Intent OMS.
#   R1 store-reject reroute   R2 hazmat + oversized routing   R3 margin-floor marketplace order
#   R4 holiday surge capacity R5 curbside promise
# Requires the service seeded with OMS_DEMO_TENANTS including `retail` (scripts/bootstrap.sh does this by default).
# Safe to re-run: every order gets a fresh channelOrderId. Story and what to look at in the UI: config/tenants/retail/README.md
#   BASE=http://localhost:8080 scripts/reference/retail.sh [R1|R2|R3|R4|R5]   (default: all five)
export TENANT=retail
source "$(dirname "$0")/../usecases/lib.sh"
ONLY=${1:-all}
TS=$(date +%s)
hr; echo "Maple & Main General Store · tenant $TENANT · $BASE"; hr
say "Jev L1 provider available: $(jev_enabled)  (without an AI key the deterministic rules and the heuristic fallback decide; the same scenarios run either way)"

# ---- helpers ----------------------------------------------------------------------------------------------------------
order_json() { # order_json <channel> <ref> <orderType> <shipMethod> <customerRef> <linesJson> <addressJson> <tenderAmount> <extraJson>
  printf '{"channel":"%s","channelOrderId":"%s","orderType":"%s","shipMethod":"%s","customerRef":"%s","customer":{"ref":"%s","name":"Reference Shopper","email":"shopper@example.com","tier":"standard","ageVerified":true,"age":40,"history":{"split_acceptance_rate":0.5}},"lines":%s,"addresses":[%s],"tenders":[{"type":"card","token":"tok_visa_ok","amount":{"amount":%s,"currency":"USD"}}]%s}' \
    "$1" "$2" "$3" "$4" "$5" "$5" "$6" "$7" "$8" "${9:-}"
}
addr() { printf '{"type":"ship_to","name":"Reference Shopper","line1":"%s","city":"%s","state":"%s","zip":"%s","country":"US","addressType":"residential"}' "$1" "$2" "$3" "$4"; }
line() { printf '{"lineId":"%s","sku":"%s","qty":%s,"unitPrice":{"amount":%s,"currency":"USD"}}' "$1" "$2" "$3" "$4"; }
show_plan() { # show_plan <orderId>
  api GET "/api/v1/orders/$1/view" | jq_ '
o=d["order"]; gs=d.get("fulfilmentGroups") or []; m=o.get("margin") or {}
print("    status:", o.get("status"), "| type:", o.get("orderType"), "| groups:", len(gs))
for g in gs: print("      ·", g.get("status"), "@", g.get("nodeId"), [(a["sku"], a["qty"]) for a in g.get("allocations",[])], "promise", g.get("promiseDate"))
if m: print("    margin:", str(round((m.get("marginRatio") or 0)*100,1))+"%", "vs floor", str(round((m.get("floor") or 0)*100))+"%", "| below floor:", m.get("belowFloor"), "| approval present:", m.get("approvalPresent"))
h=d.get("holds") or []
if h: print("    holds:", [(x.get("type"), x.get("reasonCode")) for x in h])'
}
active_node() { api GET "/api/v1/orders/$1/view" | j 'next((g["nodeId"] for g in d.get("fulfilmentGroups",[]) if g.get("status") in ("released","planned")), "")'; }
wms() { # wms <nodeId> <type> <orderId> [extraJson]
  curl -s -X POST "$BASE/webhooks/wms/$1" -H "Content-Type: application/json" -H "X-Tenant-Id: $TENANT" \
    -d "{\"eventId\":\"ref-$TS-$2-$3\",\"type\":\"$2\",\"orderId\":\"$3\"${4:+,$4}}" >/dev/null
}
evaluate() { # evaluate <decision> <inputJson>  → prints value / confidence / band / rules
  api POST "/api/v1/decisions/$1/evaluate-now" "{\"input\":$2,\"mode\":\"LIVE\"}" | jq_ '
r=d.get("result") or d
print("    decision", "'"$1"'", "→ value:", r.get("value"), "| band:", r.get("band"), "| action:", r.get("action"), "| confidence:", round(r.get("confidence") or 0,2), "| layer:", r.get("layer"), "| provider:", r.get("provider"))
[print("    rule fired:", x) for x in (r.get("rulesFired") or [])]
[print("    reason code:", x) for x in (r.get("reasonCodes") or [])]'
}

# ======================================================================================================================
r1() {
hr; echo "R1 · Store-reject reroute — Chicago cannot find the dutch oven; the order moves on without a phone call"; hr
step "Data: the dutch oven is at Chicago (3), Dallas (6), Atlanta (4) — a ship-from-store order from Lincoln Park should pick Chicago first"
api POST /api/v1/inventory/feed '{"source":"ref-r1","positions":[
 {"sku":"KIT-DUTCH-6QT","nodeId":"ST-CHI","supplyType":"on_hand","qty":3,"asOf":"2099-01-01T00:00:00Z"},
 {"sku":"KIT-DUTCH-6QT","nodeId":"ST-DAL","supplyType":"on_hand","qty":6,"asOf":"2099-01-01T00:00:00Z"},
 {"sku":"KIT-DUTCH-6QT","nodeId":"ST-ATL","supplyType":"on_hand","qty":4,"asOf":"2099-01-01T00:00:00Z"}]}' >/dev/null
api GET "/api/v1/inventory/KIT-DUTCH-6QT" | jq_ 'print("    KIT-DUTCH-6QT available by node:", {a["nodeId"]: a["available"] for a in d if a["available"]>0})'
step "Capture: ship_from_store, 1 x KIT-DUTCH-6QT (\$119.00) to 3300 N Clark St, Chicago"
O=$(api POST /api/v1/orders "$(order_json web "R1-SFS-$TS" ship_from_store ground cust-r1 "[$(line 1 KIT-DUTCH-6QT 1 119.00)]" "$(addr '3300 N Clark St' Chicago IL 60657)" 131.00)" | j 'd.get("id") or json.dumps(d)')
say "order $O"; wf_settle 6; show_plan "$O"
NODE=$(active_node "$O"); say "released to $NODE"
step "The store rejects the pick (WMS webhook store_rejected) → the workflow re-sources, penalises $NODE, raises a store_reject exception"
wms "$NODE" store_rejected "$O" '"reason":"not found on shelf"'
wf_settle 6; show_plan "$O"
NEW=$(active_node "$O"); say "rerouted to: ${NEW:-<no active group yet>}  (Chicago excluded for this order; penalty 0.4 applied to ST-CHI × KIT-DUTCH-6QT for the next rounds)"
step "The retail decision behind the playbook: store_reject_reroute (T1 choice) — what did the business want to happen?"
evaluate store_reject_reroute "{\"exception\":{\"type\":\"store_reject\",\"code\":\"STORE_SHORT\",\"node_id\":\"$NODE\",\"occurrences\":1,\"age_minutes\":2},\"order\":{\"order_type\":\"ship_from_store\",\"channel\":\"web\",\"ship_method\":\"ground\",\"line_count\":1,\"customer_tier\":\"gold\",\"total\":119.0,\"intent\":\"ship_complete\"}}"
say "same reject on a marketplace order → the L0 rule marketplace_ships_from_dc decides without a model call:"
evaluate store_reject_reroute "{\"exception\":{\"type\":\"store_reject\",\"code\":\"STORE_SHORT\",\"node_id\":\"$NODE\",\"occurrences\":1,\"age_minutes\":2},\"order\":{\"order_type\":\"standard_ship\",\"channel\":\"marketplace\",\"ship_method\":\"ground\",\"line_count\":1,\"customer_tier\":\"standard\",\"total\":119.0,\"intent\":\"ship_complete\"}}"
api GET "/api/v1/exceptions?orderId=$O&limit=5" | jq_ '[print("    exception:", e.get("type"), "|", e.get("status"), "| queue", e.get("queue"), "| playbook", e.get("playbook"), "| steps", [(s.get("action"), s.get("status")) for s in (e.get("playbookSteps") or [])]) for e in (d.get("items") if isinstance(d,dict) else d)]' 2>/dev/null || true
say "UI: $BASE/orders/$O → Fulfilment groups (rejected + new), Exceptions tab, Decisions → Why on store_reject_reroute"
}

# ======================================================================================================================
r2() {
hr; echo "R2 · Hazmat + oversized routing — aerosols only leave a hazmat-certified DC, by ground, with a signature"; hr
step "Capture: 2 x CLN-SPRAY-DISINF (aerosol, class 2.1) + 1 x OUT-COOLER-45 (oversized) to Indianapolis, ground"
O=$(api POST /api/v1/orders "$(order_json web "R2-HAZ-$TS" standard_ship ground cust-r2 "[$(line 1 CLN-SPRAY-DISINF 2 14.00),$(line 2 OUT-COOLER-45 1 249.00)]" "$(addr '50 W Washington St' Indianapolis IN 46204)" 305.00)" | j 'd.get("id") or json.dumps(d)')
say "order $O"; wf_settle 6; show_plan "$O"
say "the stores are nearer but have no hazmat capability: the planner only considered DC-COL / DC-RNO (requiredCapabilities includes hazmat)"
api GET "/api/v1/orders/$O/sourcing/options" | jq_ 'print("    required capabilities:", (d.get("explain") or d).get("requiredCapabilities") if isinstance(d,dict) else d)' 2>/dev/null || true
api GET "/api/v1/orders/$O/view" | jq_ 'print("    compliance:", [ (c.get("ruleId") or c.get("code"), c.get("action")) for c in ((d.get("order") or {}).get("compliance") or (d.get("order") or {}).get("violations") or [])] or "see timeline: HAZMAT_SIGNATURE require_signature")' 2>/dev/null || true
step "Same basket by air (two_day) → the L0 rule hazmat_no_air blocks capture with a 422 (nothing is created)"
api POST /api/v1/orders "$(order_json web "R2-AIR-$TS" standard_ship two_day cust-r2 "[$(line 1 CLN-SPRAY-DISINF 2 14.00)]" "$(addr '50 W Washington St' Indianapolis IN 46204)" 31.00)" | jq_ 'print("    →", d.get("status") or d.get("code"), d.get("title") or "", "|", d.get("detail") or ""); [print("      issue:", i.get("code") or i.get("reasonCode"), i.get("message")) for i in (d.get("issues") or [])]'
step "Oversized to a PO box → OVERSIZED_PO_BOX blocks (freight cannot deliver there)"
api POST /api/v1/orders "$(order_json web "R2-POB-$TS" standard_ship ground cust-r2 "[$(line 1 OUT-GRILL-22 1 189.00)]" '{"type":"ship_to","name":"Reference Shopper","line1":"PO Box 4410","city":"Indianapolis","state":"IN","zip":"46204","country":"US","addressType":"po_box"}' 208.00)" | jq_ 'print("    →", d.get("status") or d.get("code"), "|", d.get("detail") or ""); [print("      issue:", i.get("code") or i.get("reasonCode"), i.get("message")) for i in (d.get("issues") or [])]'
say "UI: $BASE/orders/$O → Compliance badges on the lines, Fulfilment group at a DC; Settings → Compliance rules"
}

# ======================================================================================================================
r3() {
hr; echo "R3 · Margin-floor marketplace order — a 14 kg fire pit (\$159.00) to Honolulu cannot ship above 12 %"; hr
step "Capture: marketplace, 1 x OUT-FIREPIT to Honolulu HI, ground (cheapest DC is Reno, 999-band oversized rate)"
O=$(api POST /api/v1/orders "$(order_json marketplace "R3-MKT-$TS" standard_ship ground cust-r3 "[$(line 1 OUT-FIREPIT 1 159.00)]" "$(addr '1 Aloha Tower Dr' Honolulu HI 96813)" 175.00)" | j 'd.get("id") or json.dumps(d)')
say "order $O"; wf_settle 7; show_plan "$O"
say "every plan the planner considered (none meets the floor):"
api GET "/api/v1/orders/$O/sourcing/plans" | jq_ '
for r in d:
    p=r.get("plan") or {}; print("     ", ("✔" if r.get("chosen") else " "), (r.get("policy") or "").ljust(20), "cost $"+str((p.get("totalCost") or {}).get("amount")), "| margin", str(round(((p.get("pnl") or {}).get("marginRatio") or 0)*100,1))+"%", "| meets floor:", p.get("meetsMarginFloor"))' 2>/dev/null || true
TASK=$(api GET "/api/v1/tasks?queue=margin_floor_approvals&status=OPEN&limit=50" | j "next((t['id'] for t in d.get('items',[]) if t.get('subjectId')=='$O'),'')")
say "finance task in margin_floor_approvals: ${TASK:-<none>}"
if [ -n "$TASK" ]; then
  step "Finance approves — recorded and auditable; the workflow resumes and releases the order"
  FIN=$(token finance finance)
  AS_TOKEN=$FIN api POST "/api/v1/tasks/$TASK/complete" '{"outcome":"approved","reason":"Marketplace rating matters more than this one margin","details":{}}' | j '"    task " + str(d.get("status")) + " outcome=" + str(d.get("outcome"))'
  wf_settle 6; show_plan "$O"
fi
say "UI: $BASE/orders/$O → margin card (below floor → approved), Tasks → margin_floor_approvals, Analytics → margin_floor_compliance KPI"
}

# ======================================================================================================================
r4() {
hr; echo "R4 · Holiday surge capacity — eight Denver orders in twenty minutes; the Cherry Creek store caps out at 60 units"; hr
step "Capacity today at ST-DEN (dailyCapacity 60, counts units released since midnight UTC)"
api GET "/api/v1/nodes/ST-DEN" | jq_ 'n=d.get("node") or d; print("    ST-DEN dailyCapacity:", n.get("dailyCapacity"), "| health:", n.get("health"))' 2>/dev/null || true
ZIPS=(80202 80206 80210 80218 80222 80112 80234 80302); CITIES=(Denver Denver Denver Denver Denver Englewood Westminster Boulder)
declare -a IDS=()
for i in 0 1 2 3 4 5 6 7; do
  O=$(api POST /api/v1/orders "$(order_json web "R4-SURGE-$TS-$i" standard_ship ground "cust-r4-$i" "[$(line 1 TOY-PUZZLE-500 6 19.00),$(line 2 TOY-PLUSH-FOX 4 24.00)]" "$(addr "$((100 + i)) Main St" "${CITIES[$i]}" CO "${ZIPS[$i]}")" 231.00)" | j 'd.get("id") or json.dumps(d)')
  IDS+=("$O"); say "order $((i+1)) → $O (${CITIES[$i]} ${ZIPS[$i]}, 10 units)"
done
wf_settle 10
step "Where did each order go? (the store first while it has capacity, then the DCs — nobody rerouted anything by hand)"
for O in "${IDS[@]}"; do api GET "/api/v1/orders/$O/view" | jq_ 'o=d["order"]; print("    ", o.get("id"), o.get("status"), "→", [ (g.get("nodeId"), g.get("status"), g.get("promiseDate")) for g in d.get("fulfilmentGroups",[]) ])'; done
api GET "/api/v1/control-tower/pipeline?nodeId=ST-DEN" | jq_ 'print("    control tower, ST-DEN open orders:", d.get("openOrders"), "| by node:", d.get("byNode"))' 2>/dev/null || true
say "UI: $BASE/control-tower (filter node ST-DEN vs DC-RNO), Orders list filtered by nodeId; raise dailyCapacity in nodes.yaml or PUT /api/v1/nodes/ST-DEN to change the cap"
}

# ======================================================================================================================
r5() {
hr; echo "R5 · Curbside promise — 'ready in 20 minutes' is a scored decision, not a hope"; hr
step "Capture: curbside at ST-DEN, 2 x HOME-CANDLE-CDR + 1 x HOME-THROW-GRY, blue Subaru arriving in 15 minutes"
O=$(api POST /api/v1/orders "{\"channel\":\"web\",\"channelOrderId\":\"R5-CURB-$TS\",\"orderType\":\"curbside\",\"shipMethod\":\"pickup\",\"customerRef\":\"cust-r5\",
 \"customer\":{\"ref\":\"cust-r5\",\"name\":\"Noah Davis\",\"email\":\"noah@example.com\",\"phone\":\"+1 720 555 0144\",\"tier\":\"standard\",\"ageVerified\":true,\"age\":29,\"history\":{\"split_acceptance_rate\":0.5}},
 \"lines\":[$(line 1 HOME-CANDLE-CDR 2 28.00),$(line 2 HOME-THROW-GRY 1 79.00)],
 \"addresses\":[{\"type\":\"pickup\",\"name\":\"Noah Davis\",\"line1\":\"ST-DEN\",\"city\":\"Denver\",\"state\":\"CO\",\"zip\":\"80206\",\"country\":\"US\",\"addressType\":\"commercial\"},{\"type\":\"ship_to\",\"name\":\"Noah Davis\",\"line1\":\"Store pickup ST-DEN\",\"city\":\"Denver\",\"state\":\"CO\",\"zip\":\"80206\",\"country\":\"US\",\"addressType\":\"commercial\"}],
 \"tenders\":[{\"type\":\"card\",\"token\":\"tok_visa_ok\",\"amount\":{\"amount\":150.00,\"currency\":\"USD\"}}],\"intentChoices\":[\"pickup_consolidated\"],
 \"extensions\":{\"pickupNodeId\":\"ST-DEN\",\"pickupMode\":\"curbside\",\"vehicle\":\"Blue Subaru Outback\",\"arrivalMinutes\":15}}" | j 'd.get("id") or json.dumps(d)')
say "order $O"; wf_settle 6; show_plan "$O"
say "only stores with the curbside capability qualified (order type sourcing: nodeTypes [store], capabilities [curbside]); pickup is free and same-day"
step "The promise: curbside_eta_promise (T1 score 0-4) from live store health + basket size"
HEALTH=$(api GET "/api/v1/nodes/ST-DEN" | j 'n=d.get("node") or d; h=n.get("health") or {}; json.dumps({"backlog_units": h.get("backlog", 6), "staffing": h.get("staffing", 0.95), "pick_rate": h.get("pick_rate", 0.94), "minutes_to_cutoff": 240, "open": True})' 2>/dev/null || echo '{"backlog_units":6,"staffing":0.95,"pick_rate":0.94,"minutes_to_cutoff":240,"open":true}')
evaluate curbside_eta_promise "{\"store\":$HEALTH,\"order\":{\"units\":3,\"line_count\":2,\"has_oversized\":false,\"promise_minutes\":20},\"customer\":{\"arrival_minutes\":15}}"
say "the same store on a surge afternoon (backlog 60, one associate) — the rubric says do not promise 20 minutes:"
evaluate curbside_eta_promise '{"store":{"backlog_units":60,"staffing":0.6,"pick_rate":0.84,"minutes_to_cutoff":45,"open":true},"order":{"units":3,"line_count":2,"has_oversized":false,"promise_minutes":20},"customer":{"arrival_minutes":10}}'
step "The store stages the bags at the curb, the customer collects → payment captured on pickup (FR-FIN-01)"
NODE=$(active_node "$O"); wms "${NODE:-ST-DEN}" staged_for_pickup "$O"; wf_settle 4
wms "${NODE:-ST-DEN}" picked_up "$O"; wf_settle 4; show_plan "$O"
api GET "/api/v1/orders/$O/view" | jq_ 'print("    payments:", [(p.get("status"), (p.get("amount") or {}).get("amount")) for p in d.get("payments",[])])' 2>/dev/null || true
say "UI: $BASE/orders/$O → timeline (ready_for_pickup → picked_up), Decisions → Why on curbside_eta_promise; Studio → curbside_eta_promise golden set"
}

case "$ONLY" in
  R1|r1) r1 ;; R2|r2) r2 ;; R3|r3) r3 ;; R4|r4) r4 ;; R5|r5) r5 ;;
  all) r1; r2; r3; r4; r5 ;;
  *) echo "usage: $0 [R1|R2|R3|R4|R5]" >&2; exit 2 ;;
esac
echo; hr; echo "Done. Business story, network map and what to change: config/tenants/retail/README.md"; hr
