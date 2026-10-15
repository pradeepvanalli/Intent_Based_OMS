#!/usr/bin/env bash
# Return lifecycle: create → decide (L0 policy + return_risk) → scan/receive/inspect → refund (FR-RET-01..05).
# Usage: OID=<delivered order id> ./return.sh   (run webhooks/wms_simulator.py first to ship and deliver it)
source "$(dirname "$0")/_lib.sh"
: "${OID:?set OID to a delivered order id}"

step "POST /api/v1/returns"
RMA=$(api POST /api/v1/returns "{\"orderId\":\"$OID\",\"channel\":\"web\",\"receiptPresent\":true,\"lines\":[{\"lineId\":\"1\",\"qty\":1,\"reasonCode\":\"wrong_size\",\"customerReasonText\":\"too small\"}]}" | j 'd.get("rma") or d.get("rmaId") or json.dumps(d)')
echo "rma $RMA"

step "POST /returns/$RMA/decide → eligibility, risk score, refund timing"
api POST "/api/v1/returns/$RMA/decide" | j '"eligible="+str((d.get("eligibility") or {}).get("eligible"))+" timing="+str(d.get("refundTiming"))+" risk="+str(d.get("riskScore"))+" codes="+str([c["code"] for c in (d.get("reasonCodes") or [])])+" status="+str(d.get("status"))'
REC=$(api GET "/api/v1/returns/$RMA" | j 'd.get("riskDecisionRecordId") or ""')
[ -n "$REC" ] && api GET "/api/v1/decision-records/$REC/why" | j '"return_risk: layer="+str(d["layer"])+" band="+str(d["band"])+" rules="+str(d.get("rulesFired"))'

step "Customer-safe message (GR-15)"
api GET "/api/v1/returns/$RMA/customer-message" | j 'd.get("text")'

step "Carrier scan, receipt and inspection (what the carrier and WMS webhooks do), then refund"
api POST "/api/v1/returns/$RMA/scan" | j '"status="+str(d.get("status"))'
api POST "/api/v1/returns/$RMA/receive" '{"nodeId":"DC-NJ"}' | j '"status="+str(d.get("status"))'
api POST "/api/v1/returns/$RMA/inspect" '{"condition":"new","note":"tags attached"}' | j '"status="+str(d.get("status"))+" disposition="+str(d.get("disposition"))'
api POST "/api/v1/returns/$RMA/refund" | j '"status="+str(d.get("status"))+" refund="+str(d.get("refund"))'
api GET "/api/v1/refunds?orderId=$OID" | j '[(r.get("status"), r.get("amount")) for r in (d if isinstance(d,list) else d.get("items",[]))]'
