#!/usr/bin/env bash
# Customer-service changes with feasibility first (FR-CS-02): check → apply, then a hold and its release.
# Usage: OID=<order id> ./modify_order.sh   (capture one with capture_order.sh)
source "$(dirname "$0")/_lib.sh"
: "${OID:?set OID to an order id}"

step "Feasibility: POST /orders/$OID/modifications/check (ChangeQty)"
api POST "/api/v1/orders/$OID/modifications/check" '{"type":"ChangeQty","lineId":"1","qty":1}' | pp

step "Apply: POST /orders/$OID/modifications"
api POST "/api/v1/orders/$OID/modifications" '{"modification":{"type":"ChangeQty","lineId":"1","qty":1},"reason":"customer asked for one fewer"}' \
  | j '"line 1 qty now "+str(next(l["qty"] for l in d["lines"] if l["lineId"]=="1"))'

step "Address change feasibility (becomes a carrier intercept once shipped, if cs.carrier_intercept_enabled)"
api POST "/api/v1/orders/$OID/modifications/check" '{"type":"ChangeAddress","address":{"type":"ship_to","name":"Example Customer","line1":"1 N State St","city":"Chicago","state":"IL","zip":"60602","country":"US"}}' \
  | j '"allowed="+str(d["allowed"])+" reasons="+str(d.get("reasons"))+" carrierIntercept="+str(d.get("carrierIntercept"))'

step "Place a hold that blocks release and shipping, then release it (FR-SM-07)"
HOLD=$(api POST "/api/v1/orders/$OID/holds" '{"type":"fraud_review","blocks":["release","event.shipped"],"reasonCode":"MANUAL_REVIEW","note":"example hold","ttl":"PT2H"}')
HID=$(printf '%s' "$HOLD" | j 'd.get("id") or json.dumps(d)'); echo "hold $HID"
api GET "/api/v1/orders/$OID/view" | j '[(h["type"],h["reasonCode"]) for h in d.get("holds",[])]'
api POST "/api/v1/holds/$HID/release" '{"resolution":"reviewed, no issue"}' | j '"released at "+str(d.get("resolvedAt"))'

step "Cancel a line (fires api.cancel_line; needs order.cancel)"
api POST "/api/v1/orders/$OID/modifications" '{"modification":{"type":"CancelLine","lineId":"2"},"reason":"customer changed mind"}' | j '"status="+d["status"]'
