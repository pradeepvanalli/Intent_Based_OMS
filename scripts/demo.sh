#!/usr/bin/env bash
# End-to-end demo against a running Intent OMS (http://localhost:8080 by default).
# Walks UC-01..UC-08: capture → intent → sourcing → release → WMS ship → delivery → return → refund, plus control tower,
# MCP tools and KPIs. Requires curl + python3.
set -euo pipefail
BASE=${BASE:-http://localhost:8080}
TENANT=${TENANT:-demo}
TOKEN=${TOKEN:-$("$(dirname "$0")/token.sh" admin admin "$TENANT" "$BASE")}
H=(-H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" -H "X-Tenant-Id: $TENANT")
j() { python3 -c "import sys,json; d=json.load(sys.stdin); print($1)"; }
step() { printf '\n\033[1;36m▶ %s\033[0m\n' "$*"; }

step "1. Promise at cart (FR-SRC-05)"
curl -s -X POST "$BASE/api/v1/promise" -H "Content-Type: application/json" -H "X-Tenant-Id: $TENANT" \
  -d '{"lines":[{"sku":"HOOD-GRY-M","qty":1},{"sku":"SNK-RUN-10","qty":1}],"destinationZip":"60611","destinationState":"IL","channel":"web","shipMethod":"ground"}' | python3 -m json.tool | head -20

step "2. Capture a web order with a free-text note (FR-CAP-01/06)"
TS=$(date +%s)
OID=$(curl -s -X POST "$BASE/api/v1/orders" "${H[@]}" -H "Idempotency-Key: demo-$TS" -d @- <<JSON | j 'd["id"]'
{"channel":"web","channelOrderId":"WEB-$TS","orderType":"standard_ship","customerRef":"cust-1001",
 "customer":{"ref":"cust-1001","name":"Dana Whitfield","email":"dana@example.com","tier":"gold","history":{"split_acceptance_rate":0.2,"returns_12m":1}},
 "lines":[{"lineId":"1","sku":"HOOD-GRY-M","qty":2,"unitPrice":{"amount":68.00,"currency":"USD"}},
          {"lineId":"2","sku":"SNK-RUN-10","qty":1,"unitPrice":{"amount":145.00,"currency":"USD"}}],
 "addresses":[{"type":"ship_to","name":"Dana Whitfield","line1":"233 S Wacker Dr","city":"Chicago","state":"IL","zip":"60606","country":"US"}],
 "tenders":[{"type":"card","token":"tok_visa_ok","amount":{"amount":320.00,"currency":"USD"}}],
 "notes":[{"source":"customer","text":"This is a birthday gift, please send everything together in one box."}],
 "shipMethod":"ground"}
JSON
)
echo "order id: $OID"
sleep 3

step "3. Order view: intent inferred, sourcing plan, line quantity status (FR-CS-01)"
curl -s "$BASE/api/v1/orders/$OID/view" "${H[@]}" | j '"status="+str(d["order"].get("status"))+" intent="+str((d["order"].get("intent") or {}).get("policy"))+" groups="+str(len(d.get("fulfilmentGroups") or []))+" decisions="+str(len(d.get("decisions") or []))'

step "4. Why? — full trace of the fulfilment-intent decision (FR-XAI-01)"
REC=$(curl -s "$BASE/api/v1/decision-records?subjectType=order&subjectId=$OID" "${H[@]}" | j 'next((r["id"] for r in d if r.get("decision")=="fulfilment_intent"), "")')
[ -n "$REC" ] && curl -s "$BASE/api/v1/decision-records/$REC/why" "${H[@]}" | python3 -m json.tool | head -60

step "5. WMS ships from the chosen node (webhook → workflow signal → state transition → payment capture)"
NODE=$(curl -s "$BASE/api/v1/orders/$OID/view" "${H[@]}" | j '(d.get("fulfilmentGroups") or [{}])[0].get("nodeId","DC-NJ")')
curl -s -X POST "$BASE/webhooks/wms/$NODE" -H "Content-Type: application/json" -H "X-Tenant-Id: $TENANT" \
  -d "{\"eventId\":\"wms-$TS\",\"type\":\"shipped\",\"orderId\":\"$OID\",\"carrier\":\"UPS\",\"service\":\"ground\",\"tracking\":\"1Z999\"}" | head -c 300; echo
sleep 2
curl -s -X POST "$BASE/webhooks/carrier" -H "Content-Type: application/json" -H "X-Tenant-Id: $TENANT" \
  -d "{\"eventId\":\"car-$TS\",\"type\":\"delivered\",\"orderId\":\"$OID\",\"tracking\":\"1Z999\"}" | head -c 300; echo
sleep 2
curl -s "$BASE/api/v1/orders/$OID/view" "${H[@]}" | j '"status="+str(d["order"].get("status"))+" lines="+str([ (l.get("state"), [ (q.get("state"),q.get("qty")) for q in (l.get("quantities") or [])]) for l in (d.get("lineStatuses") or [])])'

step "6. Return one hoodie in store (BORIS) → risk score + refund timing (FR-RET-01..04)"
RMA=$(curl -s -X POST "$BASE/api/v1/returns" "${H[@]}" -d "{\"orderId\":\"$OID\",\"channel\":\"store\",\"storeNodeId\":\"ST-CHI-01\",\"receiptPresent\":true,\"lines\":[{\"lineId\":\"1\",\"qty\":1,\"customerReasonText\":\"too small\"}]}" | j 'd.get("rma") or d.get("rmaId") or d.get("id")')
echo "rma: $RMA"
curl -s -X POST "$BASE/api/v1/returns/$RMA/decide" "${H[@]}" | j '"eligible="+str((d.get("eligibility") or {}).get("eligible"))+" timing="+str(d.get("refundTiming"))+" risk="+str(d.get("riskScore"))+" codes="+str(d.get("reasonCodes"))'

step "7. Control tower pipeline (FR-MON-01/02)"
curl -s "$BASE/api/v1/control-tower/pipeline" "${H[@]}" | j '"open="+str(d.get("openOrders"))+" atRisk="+str(d.get("atRisk"))+" held="+str(d.get("held"))'

step "8. MCP tools for agents (FR-AGT-01)"
curl -s -X POST "$BASE/mcp" -H "Content-Type: application/json" -H "X-Tenant-Id: $TENANT" -H "Authorization: Bearer $TOKEN" \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/list"}' | j '[t["name"] for t in d["result"]["tools"]]'

step "9. KPIs (FR-ANL-01)"
curl -s "$BASE/api/v1/analytics/kpis" "${H[@]}" | j '{k["key"]: k["value"] for k in d["kpis"]}'
echo; echo "Done. Open $BASE (admin/admin) to explore the order, decision traces and the policy studio."
