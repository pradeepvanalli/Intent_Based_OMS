#!/usr/bin/env bash
# Capture a web order, read the aggregate view, ask why the intent decision chose what it chose (FR-CAP-01/06, FR-XAI-01).
source "$(dirname "$0")/_lib.sh"
TS=$(date +%s)

step "POST /api/v1/orders (Idempotency-Key makes retries safe; channel + channelOrderId is unique too)"
ORDER=$(api POST /api/v1/orders "$(cat <<JSON
{"channel":"web","channelOrderId":"EX-$TS","orderType":"standard_ship","customerRef":"cust-ex-1",
 "customer":{"ref":"cust-ex-1","name":"Example Customer","email":"customer@example.com","tier":"gold","history":{"split_acceptance_rate":0.2,"returns_12m":0}},
 "lines":[{"lineId":"1","sku":"HOOD-GRY-M","qty":2,"unitPrice":{"amount":68.00,"currency":"USD"}},
          {"lineId":"2","sku":"SNK-RUN-10","qty":1,"unitPrice":{"amount":145.00,"currency":"USD"}}],
 "addresses":[{"type":"ship_to","name":"Example Customer","line1":"233 S Wacker Dr","city":"Chicago","state":"IL","zip":"60606","country":"US"}],
 "tenders":[{"type":"card","token":"tok_visa_ok","amount":{"amount":320.00,"currency":"USD"}}],
 "notes":[{"source":"customer","text":"Birthday gift, please send everything together in one box."}],
 "intentChoices":["ship_complete"],
 "shipMethod":"ground"}
JSON
)")
OID=$(printf '%s' "$ORDER" | j 'd.get("id") or json.dumps(d)')
echo "order id: $OID"
sleep 4   # the order.standard_ship workflow infers intent, authorises payment, sources and releases

step "GET /api/v1/orders/$OID/view"
api GET "/api/v1/orders/$OID/view" | j '"status="+d["order"]["status"]+" intent="+str((d["order"].get("intent") or {}).get("policy"))+" groups="+str([(g["nodeId"],g["status"]) for g in d.get("fulfilmentGroups",[])])+" workflow="+str((d.get("workflow") or {}).get("currentStep"))'

step "Why? GET /api/v1/decision-records?subjectType=order&subjectId=... then /why"
REC=$(api GET "/api/v1/decision-records?subjectType=order&subjectId=$OID" | j 'next((r["id"] for r in d if r["decision"]=="sourcing_node_score"), "")')
[ -n "$REC" ] && api GET "/api/v1/decision-records/$REC/why" | j '"decision="+d["decision"]+" layer="+str(d["layer"])+" band="+str(d["band"])+" value="+str(d["value"])+" confidence="+str(round(d.get("confidence") or 0,2))'
[ -n "$REC" ] && api GET "/api/v1/decision-records/$REC/why?audience=customer" | j 'd.get("customerSafeExplanation") or d.get("explanation")'

step "Timeline and ledger"
api GET "/api/v1/orders/$OID/timeline" | j '[e.get("kind","")+":"+str(e.get("name") or e.get("type")) for e in d][:12]'
api GET "/api/v1/admin/ledger/order/$OID" | j '[e["type"] for e in d]'
echo "export OID=$OID   # for modify_order.sh / return.sh"
