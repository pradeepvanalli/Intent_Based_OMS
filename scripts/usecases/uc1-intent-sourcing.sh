#!/usr/bin/env bash
# UC1 — "Send it together": intent-driven split policy + margin-aware sourcing (PRD P2/P3, FR-CAP-06, FR-SRC-02..06)
# Two identical baskets to Seattle. One buyer says "it's a gift, send everything together"; the other says
# "I need the shoes before Friday, the rest whenever". Same catalogue, same network, two different plans — chosen by
# the buyer's intent and the order's economics, not by a static per-channel rule. Then a third order that no plan can
# ship above the profitability floor: the system offers alternatives and asks finance instead of silently losing money.
source "$(dirname "$0")/lib.sh"
hr; echo "UC1 · The gift that must arrive together — intent-driven, margin-aware sourcing"; hr
JEV=$(jev_enabled)
say "Jev L1 provider available: $JEV  (without a TYPESAFE_API_KEY the buyer's checkout choice drives intent; with it, the free-text note alone does)"

step "Data setup: the navy cap is only stocked at the New Jersey DC — a real split dilemma for a Seattle order"
TS=$(date +%s)
api POST /api/v1/inventory/feed '{"source":"demo-uc1","positions":[
 {"sku":"CAP-NVY","nodeId":"DC-NV","supplyType":"on_hand","qty":0,"asOf":"2099-01-01T00:00:00Z"},
 {"sku":"CAP-NVY","nodeId":"DC-TX","supplyType":"on_hand","qty":0,"asOf":"2099-01-01T00:00:00Z"},
 {"sku":"CAP-NVY","nodeId":"3PL-ATL","supplyType":"on_hand","qty":0,"asOf":"2099-01-01T00:00:00Z"},
 {"sku":"CAP-NVY","nodeId":"ST-LA-02","supplyType":"on_hand","qty":0,"asOf":"2099-01-01T00:00:00Z"},
 {"sku":"CAP-NVY","nodeId":"ST-CHI-01","supplyType":"on_hand","qty":0,"asOf":"2099-01-01T00:00:00Z"},
 {"sku":"CAP-NVY","nodeId":"ST-AUS-03","supplyType":"on_hand","qty":0,"asOf":"2099-01-01T00:00:00Z"},
 {"sku":"CAP-NVY","nodeId":"VND-MX","supplyType":"on_hand","qty":0,"asOf":"2099-01-01T00:00:00Z"},
 {"sku":"CAP-NVY","nodeId":"DC-NJ","supplyType":"on_hand","qty":120,"asOf":"2099-01-01T00:00:00Z"}]}' >/dev/null
api GET "/api/v1/inventory/CAP-NVY" | jq_ 'print("    CAP-NVY available by node:", {a["nodeId"]: a["available"] for a in d if a["available"]>0})'

order() { # order <ref> <note> <intentChoicesJson> <shoesAttrsJson>
  local ref=$1 note=$2 intent=$3 attrs=$4
  api POST /api/v1/orders "{\"channel\":\"web\",\"channelOrderId\":\"$ref\",\"orderType\":\"standard_ship\",\"customerRef\":\"cust-seattle-$ref\",
   \"customer\":{\"ref\":\"cust-seattle-$ref\",\"name\":\"Priya Raman\",\"email\":\"priya@example.com\",\"tier\":\"gold\",\"history\":{\"split_acceptance_rate\":0.3}},
   \"lines\":[{\"lineId\":\"1\",\"sku\":\"HOOD-GRY-M\",\"qty\":1,\"unitPrice\":{\"amount\":68.00,\"currency\":\"USD\"}},
             {\"lineId\":\"2\",\"sku\":\"SNK-RUN-10\",\"qty\":1,\"unitPrice\":{\"amount\":145.00,\"currency\":\"USD\"},\"attributes\":$attrs},
             {\"lineId\":\"3\",\"sku\":\"CAP-NVY\",\"qty\":1,\"unitPrice\":{\"amount\":32.00,\"currency\":\"USD\"}}],
   \"addresses\":[{\"type\":\"ship_to\",\"name\":\"Priya Raman\",\"line1\":\"400 Pine St\",\"city\":\"Seattle\",\"state\":\"WA\",\"zip\":\"98101\",\"country\":\"US\"}],
   \"tenders\":[{\"type\":\"card\",\"token\":\"tok_visa_ok\",\"amount\":{\"amount\":280.00,\"currency\":\"USD\"}}],
   \"notes\":[{\"source\":\"customer\",\"text\":\"$note\"}],
   \"intentChoices\":$intent,\"shipMethod\":\"ground\"}" | j 'd.get("id") or json.dumps(d)'
}
NOTE_A="This is a birthday gift for my dad, please send everything together in one box."
NOTE_B="I need the running shoes before Friday's race, the rest can come whenever."
step "Order A — note: \"$NOTE_A\""
if [ "$JEV" = yes ]; then A=$(order "UC1-GIFT-$TS" "$NOTE_A" "[]" "{}"); else A=$(order "UC1-GIFT-$TS" "$NOTE_A" '["ship_complete"]' "{}"); fi
step "Order B — note: \"$NOTE_B\""
if [ "$JEV" = yes ]; then B=$(order "UC1-RACE-$TS" "$NOTE_B" "[]" '{"urgent":true}'); else B=$(order "UC1-RACE-$TS" "$NOTE_B" '["earliest_partial"]' '{"urgent":true}'); fi
say "orders: A=$A  B=$B"; wf_settle 7

show() {
  api GET "/api/v1/orders/$1/view" | jq_ '
o=d["order"]; it=o.get("intent") or {}
print("    intent:", it.get("policy"), "| source:", it.get("source"), "| confidence:", round(it.get("confidence") or 0,2), "| splitOk:", it.get("splitOk"), "| isGift:", it.get("isGift"))
gs=d.get("fulfilmentGroups") or []
print("    plan:", len(gs), "package(s)")
for g in gs: print("      ·", g.get("nodeId"), [ (a["sku"], a["qty"]) for a in g.get("allocations",[]) ], "ETA", g.get("promiseDate"), "on-time", str(round((g.get("onTimeProbability") or 0)*100))+"%", "cost $"+str((g.get("estimatedCost") or {}).get("amount")))
m=o.get("margin") or {}; print("    margin:", str(round((m.get("marginRatio") or 0)*100,1))+"%", "(floor", str(round((m.get("floor") or 0)*100))+"%)", "| status:", o.get("status"))'
}
plans() {
  api GET "/api/v1/orders/$1/sourcing/plans" | jq_ '
for r in d:
    p=r.get("plan") or {}; print("     ", ("✔" if r.get("chosen") else " "), r.get("policy").ljust(20), "packages", len(p.get("packages") or []), "| cost $"+str((p.get("totalCost") or {}).get("amount")), "| margin", str(round(((p.get("pnl") or {}).get("marginRatio") or 0)*100,1))+"%", "| latest ETA", max([k.get("eta","") for k in (p.get("packages") or [])] or [""]), "| score", round(r.get("score") or 0,3))'
}
step "Order A plan — one box, even though it means shipping cross-country from New Jersey"; show "$A"
say "every plan that was considered (FR-SRC-06 trade-offs the buyer could be offered at checkout):"; plans "$A"
step "Order B plan — the urgent shoes leave from the nearest DC today; the rest follows from New Jersey"; show "$B"; plans "$B"

step "Why? — the decisions behind order B"
if [ "$JEV" = yes ]; then REC=$(decision_for "$B" order fulfilment_intent); [ -n "$REC" ] && why "$REC"; else say "intent came from an explicit checkout choice, so no model was called (deterministic first, GR-07); with Jev the note alone would set it"; fi
REC=$(decision_for "$B" order sourcing_node_score); [ -n "$REC" ] && why "$REC"

step "Part 2 — the order nobody should ship at a loss: 2 × 20 kg dumbbell sets (\$70 each) to Honolulu"
C=$(api POST /api/v1/orders "{\"channel\":\"web\",\"channelOrderId\":\"UC1-HEAVY-$TS\",\"orderType\":\"standard_ship\",\"customerRef\":\"cust-hnl-1\",
   \"customer\":{\"ref\":\"cust-hnl-1\",\"name\":\"Kai Nakamura\",\"email\":\"kai@example.com\",\"tier\":\"silver\"},
   \"lines\":[{\"lineId\":\"1\",\"sku\":\"DUMB-10KG\",\"qty\":2,\"unitPrice\":{\"amount\":70.00,\"currency\":\"USD\"}}],
   \"addresses\":[{\"type\":\"ship_to\",\"name\":\"Kai Nakamura\",\"line1\":\"1 Aloha Tower Dr\",\"city\":\"Honolulu\",\"state\":\"HI\",\"zip\":\"96813\",\"country\":\"US\"}],
   \"tenders\":[{\"type\":\"card\",\"token\":\"tok_visa_ok\",\"amount\":{\"amount\":160.00,\"currency\":\"USD\"}}],\"intentChoices\":[\"ship_complete\"],\"shipMethod\":\"ground\"}" | j 'd.get("id") or json.dumps(d)')
say "order $C"; wf_settle 7
api GET "/api/v1/orders/$C/view" | jq_ 'o=d["order"]; m=o.get("margin") or {}; w=d.get("workflow") or {}; print("    status:", o.get("status"), "| workflow:", w.get("status"), "at step", w.get("currentStep"), "| margin", str(round((m.get("marginRatio") or 0)*100,1))+"%", "vs floor", str(round((m.get("floor") or 0)*100))+"%", "→ below floor:", m.get("belowFloor"))'
say "alternatives that would meet the floor, computed by the planner:"; plans "$C"
TASK=$(api GET "/api/v1/tasks?queue=margin_floor_approvals&status=OPEN&limit=50" | j "next((t['id'] for t in d.get('items',[]) if t.get('subjectId')=='$C'),'')")
say "finance controller sees task $TASK in the margin_floor_approvals queue with the plan economics and the alternatives"
step "Finance approves (recorded, auditable) → the workflow resumes and the order ships; the KPI 'margin-floor compliance' counts the approval"
FIN=$(token finance finance)
AS_TOKEN=$FIN api POST "/api/v1/tasks/$TASK/complete" '{"outcome":"approved","reason":"Loyal customer, accept the loss on this one","details":{}}' | j '"    task " + str(d.get("status")) + " outcome=" + str(d.get("outcome"))'
wf_settle 6
api GET "/api/v1/orders/$C/view" | jq_ 'o=d["order"]; m=o.get("margin") or {}; print("    status:", o.get("status"), "| approval present:", m.get("approvalPresent"), "| groups:", [(g.get("nodeId"), g.get("promiseDate")) for g in d.get("fulfilmentGroups",[])])'
echo; say "Open $BASE/orders/$A, $BASE/orders/$B and $BASE/orders/$C (margin card + Decisions → Why). Talk track: intent is a typed decision the business owns; the split penalty (\$12/package) and contribution margin live inside the plan score; nothing ships below the floor without a recorded approval."
