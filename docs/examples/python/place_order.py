#!/usr/bin/env python3
"""Capture an order, follow it through sourcing, read the decision trace, then ship and deliver it through the
WMS and carrier webhooks. Run against a bootstrapped demo: `python3 place_order.py`.

Environment: BASE (http://localhost:8080), TENANT (demo), OMS_USER/OMS_PASSWORD (admin/admin),
WEBHOOK_SECRET (only if the tenant set webhooks.inbound_secret).
"""
import os
import time

from intentoms_client import IntentOms, OmsError

BASE = os.environ.get("BASE", "http://localhost:8080")
TENANT = os.environ.get("TENANT", "demo")
SECRET = os.environ.get("WEBHOOK_SECRET")

oms = IntentOms(BASE, tenant=TENANT)
me = oms.login(os.environ.get("OMS_USER", "admin"), os.environ.get("OMS_PASSWORD", "admin"))
print("logged in as", me["id"], "with", len(me["permissions"]), "permissions")

ts = int(time.time())
print("\n1. promise at cart")
promise = oms.promise([{"sku": "HOOD-GRY-M", "qty": 1}, {"sku": "SNK-RUN-10", "qty": 1}], "60611", "IL")
print("   ", promise["date"], "from", promise["fromNodeId"], f"on-time {promise['onTimeProbability']:.0%}")

print("\n2. capture")
try:
    order = oms.capture_order({
        "channel": "web", "channelOrderId": f"PY-{ts}", "orderType": "standard_ship", "customerRef": "cust-py-1",
        "customer": {"ref": "cust-py-1", "name": "Py Customer", "email": "py@example.com", "tier": "gold",
                     "history": {"split_acceptance_rate": 0.2, "returns_12m": 0}},
        "lines": [{"lineId": "1", "sku": "HOOD-GRY-M", "qty": 1, "unitPrice": {"amount": 68.00, "currency": "USD"}},
                  {"lineId": "2", "sku": "SNK-RUN-10", "qty": 1, "unitPrice": {"amount": 145.00, "currency": "USD"}}],
        "addresses": [{"type": "ship_to", "name": "Py Customer", "line1": "233 S Wacker Dr", "city": "Chicago", "state": "IL", "zip": "60606", "country": "US"}],
        "tenders": [{"type": "card", "token": "tok_visa_ok", "amount": {"amount": 230.00, "currency": "USD"}}],
        "notes": [{"source": "customer", "text": "Please ship everything together."}],
        "intentChoices": ["ship_complete"],
        "shipMethod": "ground",
    }, idempotency_key=f"py-{ts}")
except OmsError as e:
    print("   capture failed:", e.code, e.detail, e.body.get("issues"))
    raise SystemExit(1)
oid = order["id"]
print("   order", oid, "status", order["status"])

print("\n3. wait for the workflow (intent → payment → sourcing → release)")
for _ in range(20):
    view = oms.order_view(oid)
    groups = view.get("fulfilmentGroups") or []
    if groups and view["order"]["status"] in ("released", "sourcing") and (view.get("workflow") or {}).get("currentStep") == "await_fulfilment":
        break
    time.sleep(1)
view = oms.order_view(oid)
print("   status", view["order"]["status"], "| intent", (view["order"].get("intent") or {}).get("policy"),
      "| groups", [(g["nodeId"], g["status"]) for g in view.get("fulfilmentGroups", [])],
      "| workflow at", (view.get("workflow") or {}).get("currentStep"))

print("\n4. why did sourcing pick that node?")
for rec in oms.decision_records("order", oid):
    trace = oms.why(rec["id"])
    print(f"   {trace['decision']:22s} layer={trace['layer']:14s} band={str(trace.get('band')):9s} value={str(trace.get('value'))[:30]} "
          f"confidence={trace.get('confidence') or 0:.2f} rules={trace.get('rulesFired')}")

print("\n5. ship and deliver through the webhooks")
node = view["fulfilmentGroups"][0]["nodeId"] if view.get("fulfilmentGroups") else "DC-NJ"
tracking = f"1Z{ts}"
oms.wms_event(node, {"eventId": f"py-ship-{ts}", "type": "shipped", "orderId": oid, "carrier": "UPS", "service": "ground", "tracking": tracking}, SECRET)
time.sleep(2)
oms.carrier_event({"eventId": f"py-deliver-{ts}", "type": "delivered", "orderId": oid, "tracking": tracking}, SECRET)
time.sleep(2)
view = oms.order_view(oid)
print("   status", view["order"]["status"], "| payments", [(p.get("status"), p.get("amount")) for p in view.get("payments", [])])
print("   line quantities", [(l["entityId"], [(q["state"], q["qty"]) for q in l["quantities"]]) for l in view["lineStatuses"]])
print("\nOID =", oid, " (use with curl/return.sh)")
