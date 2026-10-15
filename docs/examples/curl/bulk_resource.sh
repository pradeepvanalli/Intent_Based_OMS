#!/usr/bin/env bash
# Take a node out and bulk re-source the orders released to it: dry run, real run, per-order report (FR-WF-07, FR-SRC-07).
# Usage: NODE=DC-TX ./bulk_resource.sh
source "$(dirname "$0")/_lib.sh"
NODE=${NODE:-DC-TX}

step "Orders released at $NODE"
IDS=$(api GET "/api/v1/orders?nodeId=$NODE&status=released&limit=50" | j 'json.dumps([o["id"] for o in d.get("items",[])])')
echo "$IDS"; [ "$IDS" = "[]" ] && { echo "nothing to re-source (release some orders to $NODE first)"; exit 0; }

step "Deactivate the node: PUT /api/v1/nodes/$NODE (same shape as nodes.yaml)"
DEF=$(api GET "/api/v1/nodes/$NODE" | j 'json.dumps(d.get("node", d))')
api PUT "/api/v1/nodes/$NODE" "$(printf '%s' "$DEF" | python3 -c 'import sys,json; n=json.load(sys.stdin); n["active"]=False; n.pop("penalty",None); print(json.dumps(n))')" | j '"active="+str(d["active"])'

step "Dry run: POST /api/v1/bulk-operations"
DRY=$(api POST /api/v1/bulk-operations "{\"action\":\"resource\",\"subjectIds\":$IDS,\"params\":{\"nodeId\":\"$NODE\",\"reason\":\"example outage\"},\"dryRun\":true,\"perSecond\":5}" | j 'd["id"]')
sleep 3; api GET "/api/v1/bulk-operations/$DRY" | j '"dry run "+d["status"]+" total="+str(d.get("total"))+" results="+str([(r.get("subjectId"), r.get("status")) for r in d.get("results",[])][:5])'

step "Real run, throttled to 5/s"
RUN=$(api POST /api/v1/bulk-operations "{\"action\":\"resource\",\"subjectIds\":$IDS,\"params\":{\"nodeId\":\"$NODE\",\"reason\":\"example outage\"},\"dryRun\":false,\"perSecond\":5}" | j 'd["id"]')
for i in $(seq 1 30); do S=$(api GET "/api/v1/bulk-operations/$RUN" | j 'd["status"]'); case "$S" in COMPLETED|FAILED) break;; esac; sleep 1; done
api GET "/api/v1/bulk-operations/$RUN" | j '"run "+d["status"]+" succeeded="+str(d.get("succeeded"))+" failed="+str(d.get("failed"))'
echo "rollback is available: POST /api/v1/bulk-operations/$RUN/rollback"

step "Bring the node back and clear learned penalties"
api PUT "/api/v1/nodes/$NODE" "$(printf '%s' "$DEF" | python3 -c 'import sys,json; n=json.load(sys.stdin); n["active"]=True; n.pop("penalty",None); print(json.dumps(n))')" | j '"active="+str(d["active"])'
api DELETE "/api/v1/nodes/$NODE/penalties?reason=example%20over" | j 'd'
