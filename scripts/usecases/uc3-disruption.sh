#!/usr/bin/env bash
# UC3 — Peak-day disruption (UC-13, FR-SIM-03, FR-WF-07, FR-SRC-07): the Dallas DC goes dark. Simulate the impact first,
# then take the node out of the network and bulk re-source every affected open order — with a dry run, throttling and
# a per-order report. The learned node penalty keeps new orders away until the node recovers.
source "$(dirname "$0")/lib.sh"
hr; echo "UC3 · The Dallas DC goes dark — what-if, then bulk re-source"; hr
TS=$(date +%s)
TODAY=$(date -u +%Y-%m-%d); FROM=$(date -u -v-7d +%Y-%m-%d 2>/dev/null || date -u -d '-7 days' +%Y-%m-%d); TO=$(date -u -v+1d +%Y-%m-%d 2>/dev/null || date -u -d '+1 day' +%Y-%m-%d)

step "Data setup: DC-TX healthy and unpenalised; three Texas orders routed to it (nearest node) and released to the warehouse"
NODE0=$(api GET /api/v1/nodes/DC-TX | j 'json.dumps(d.get("node", d))')
api PUT /api/v1/nodes/DC-TX "$(printf '%s' "$NODE0" | python3 -c "import sys,json; n=json.load(sys.stdin); n['active']=True; h=dict(n.get('health') or {}); h.pop('outage', None); n['health']=h; print(json.dumps(n))")" >/dev/null
api DELETE "/api/v1/nodes/DC-TX/penalties?reason=demo%20reset" | j '"    DC-TX learned penalties cleared: " + str(d.get("cleared"))'
for i in 1 2 3; do
  api POST /api/v1/orders "{\"channel\":\"web\",\"channelOrderId\":\"UC3-TX-$i-$TS\",\"orderType\":\"standard_ship\",\"customerRef\":\"cust-tx-$i\",
   \"customer\":{\"ref\":\"cust-tx-$i\",\"name\":\"Texas Customer $i\",\"email\":\"tx$i@example.com\",\"tier\":\"silver\"},
   \"lines\":[{\"lineId\":\"1\",\"sku\":\"BLND-PRO\",\"qty\":1,\"unitPrice\":{\"amount\":179.00,\"currency\":\"USD\"}},{\"lineId\":\"2\",\"sku\":\"MUG-CER\",\"qty\":2,\"unitPrice\":{\"amount\":18.00,\"currency\":\"USD\"}}],
   \"addresses\":[{\"type\":\"ship_to\",\"name\":\"Texas Customer $i\",\"line1\":\"${i}00 Main St\",\"city\":\"Dallas\",\"state\":\"TX\",\"zip\":\"75201\",\"country\":\"US\"}],
   \"tenders\":[{\"type\":\"card\",\"token\":\"tok_visa_ok\",\"amount\":{\"amount\":260.00,\"currency\":\"USD\"}}],\"intentChoices\":[\"ship_complete\"],\"shipMethod\":\"ground\"}" >/dev/null
done
wf_settle 7
api GET "/api/v1/orders?nodeId=DC-TX&status=released&limit=50" | jq_ 'print("    released orders at DC-TX:", d.get("total"), [i["id"] for i in d.get("items",[])])'
IDS=$(api GET "/api/v1/orders?nodeId=DC-TX&status=released&limit=50" | j 'json.dumps([i["id"] for i in d.get("items",[])])')
[ "$IDS" = "[]" ] && { echo "no released orders at DC-TX — check inventory/penalties"; exit 1; }

step "1) What-if before acting: simulate closing DC-TX over the last week of orders (FR-SIM-03)"
JOB=$(api POST /api/v1/simulations/what-if "{\"scenario\":\"node_closure\",\"nodeId\":\"DC-TX\",\"from\":\"${FROM}T00:00:00Z\",\"to\":\"${TO}T00:00:00Z\",\"sampleLimit\":200}" | j 'd["id"]')
for i in $(seq 1 30); do S=$(api GET "/api/v1/simulations/$JOB" | j 'd["status"]'); [ "$S" = COMPLETED ] || [ "$S" = FAILED ] && break; sleep 1; done
api GET "/api/v1/simulations/$JOB" | jq_ '
r=d.get("results") or {}; b=r.get("baseline") or {}; s=r.get("simulated") or {}
print("    status", d["status"], "| orders simulated", r.get("processed", b.get("orders")))
print("    cost/order  baseline $"+str(round(b.get("costPerOrder",0),2)), "→ simulated $"+str(round(s.get("costPerOrder",0),2)))
print("    on-time     baseline", str(round((b.get("onTimeRate") or 0)*100,1))+"%", "→", str(round((s.get("onTimeRate") or 0)*100,1))+"%")
print("    split rate  baseline", str(round((b.get("splitRate") or 0)*100,1))+"%", "→", str(round((s.get("splitRate") or 0)*100,1))+"%")
print("    margin      baseline", str(round((b.get("margin") or 0)*100,1))+"%", "→", str(round((s.get("margin") or 0)*100,1))+"%")'

step "2) The outage is real: take DC-TX out of the network (business user, no deploy)"
NODE=$(api GET /api/v1/nodes/DC-TX | j 'json.dumps(d.get("node", d))')
api PUT /api/v1/nodes/DC-TX "$(printf '%s' "$NODE" | python3 -c "import sys,json; n=json.load(sys.stdin); n['active']=False; n['health']=dict(n.get('health') or {}, outage=True); print(json.dumps(n))")" | j '"    DC-TX active=" + str(d.get("active"))'

step "3) Bulk re-source the affected orders — dry run first (FR-WF-07)"
DRY=$(api POST /api/v1/bulk-operations "{\"action\":\"resource\",\"subjectIds\":$IDS,\"params\":{\"nodeId\":\"DC-TX\",\"reason\":\"DC-TX outage $TS\"},\"dryRun\":true,\"perSecond\":5}" | j 'd["id"]')
sleep 3; api GET "/api/v1/bulk-operations/$DRY" | jq_ 'print("    dry run:", d["status"], "| total", d.get("total"), "| would re-source:", [ (r.get("subjectId"), (r.get("result") or r).get("skipped") or "ok") for r in (d.get("results") or [])][:5])'
step "4) Apply it (throttled 5/s, per-order report, rollback available)"
RUN=$(api POST /api/v1/bulk-operations "{\"action\":\"resource\",\"subjectIds\":$IDS,\"params\":{\"nodeId\":\"DC-TX\",\"reason\":\"DC-TX outage $TS\"},\"dryRun\":false,\"perSecond\":5}" | j 'd["id"]')
for i in $(seq 1 30); do S=$(api GET "/api/v1/bulk-operations/$RUN" | j 'd["status"]'); [ "$S" = COMPLETED ] || [ "$S" = FAILED ] && break; sleep 1; done
api GET "/api/v1/bulk-operations/$RUN" | jq_ 'print("    bulk:", d["status"], "| succeeded", d.get("succeeded", d.get("done")), "| failed", d.get("failed")); [print("      ·", r.get("subjectId"), "→", (r.get("result") or r).get("newNodes") or (r.get("result") or r).get("status") or r.get("status")) for r in (d.get("results") or [])]'
wf_settle 5
api GET "/api/v1/orders?nodeId=DC-TX&status=released&limit=50" | jq_ 'print("    orders still at DC-TX:", d.get("total"))'
api GET "/api/v1/orders?q=UC3-TX&limit=10" | jq_ '[print("    ", i["id"], i.get("status"), "now at", i.get("nodeIds") or i.get("primaryNodeId"), "promise", i.get("promiseDate")) for i in d.get("items",[])]'

step "5) The network learned: DC-TX carries a penalty and the control tower shows the exceptions"
api GET /api/v1/nodes/DC-TX | jq_ 'n=d.get("node", d); print("    DC-TX active:", n.get("active"), "| penalty:", round(n.get("penalty") or 0,2), "| penalties:", d.get("penalties"))'
api GET "/api/v1/exceptions?status=active&limit=5" | jq_ 'print("    active exceptions:", d.get("total"), [ (e.get("type"), e.get("orderId")) for e in d.get("items",[])][:5])'

step "Recovery: bring DC-TX back"
api PUT /api/v1/nodes/DC-TX "$(printf '%s' "$NODE" | python3 -c "import sys,json; n=json.load(sys.stdin); n['active']=True; print(json.dumps(n))")" | j '"    DC-TX active=" + str(d.get("active"))'
echo; say "Open $BASE/simulation (what-if results) and $BASE/orders?nodeId=DC-TX. Talk track: simulate → decide → act in bulk with a dry run; the same bulk engine handles re-promise, holds and cancels."
