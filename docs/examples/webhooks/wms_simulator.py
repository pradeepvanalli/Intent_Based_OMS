#!/usr/bin/env python3
"""Plays the warehouse and the carrier for an order: pick_started → packed → shipped → in_transit → out_for_delivery →
delivered, with correct X-Webhook-Signature headers when the tenant has webhooks.inbound_secret set.

    python3 wms_simulator.py <orderId> [--node DC-NJ] [--partial lineId:qty] [--reject] [--short-pick]
    WEBHOOK_SECRET=... python3 wms_simulator.py <orderId>

Body shapes (WebhookPayloads): WMS {eventId, type, orderId, fulfilmentGroupId?, lines?[{lineId, qty}], carrier?, service?,
tracking?}; carrier {eventId, type, orderId?, tracking}. Both are idempotent on eventId.
"""
import argparse
import hashlib
import hmac
import json
import os
import sys
import time
import urllib.error
import urllib.request

BASE = os.environ.get("BASE", "http://localhost:8080")
TENANT = os.environ.get("TENANT", "demo")
SECRET = os.environ.get("WEBHOOK_SECRET")


def post(path: str, body: dict) -> dict:
    raw = json.dumps(body, separators=(",", ":")).encode()
    headers = {"Content-Type": "application/json", "X-Tenant-Id": TENANT}
    if SECRET:
        headers["X-Webhook-Signature"] = "sha256=" + hmac.new(SECRET.encode(), raw, hashlib.sha256).hexdigest()
    req = urllib.request.Request(BASE + path, data=raw, method="POST", headers=headers)
    try:
        with urllib.request.urlopen(req, timeout=30) as resp:
            text = resp.read().decode()
            return json.loads(text) if text else {}
    except urllib.error.HTTPError as e:
        print(f"  {path} → HTTP {e.code}: {e.read().decode(errors='replace')[:300]}", file=sys.stderr)
        return {}


def wms(node: str, type_: str, order_id: str, **extra) -> dict:
    body = {"eventId": f"sim-{type_}-{order_id}-{int(time.time()*1000)}", "type": type_, "orderId": order_id, **extra}
    r = post(f"/webhooks/wms/{node}", body)
    print(f"  wms/{node} {type_:18s} → {json.dumps(r)[:140]}")
    return r


def carrier(type_: str, order_id: str, tracking: str) -> dict:
    body = {"eventId": f"sim-{type_}-{order_id}-{int(time.time()*1000)}", "type": type_, "orderId": order_id, "tracking": tracking}
    r = post("/webhooks/carrier", body)
    print(f"  carrier     {type_:18s} → {json.dumps(r)[:140]}")
    return r


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("order_id")
    ap.add_argument("--node", default=os.environ.get("NODE", "DC-NJ"), help="node id the release went to (see the order view's fulfilmentGroups)")
    ap.add_argument("--partial", help="ship only part of a line: lineId:qty")
    ap.add_argument("--reject", action="store_true", help="store rejects the release instead of shipping (triggers re-sourcing)")
    ap.add_argument("--short-pick", action="store_true", help="short pick after pick_started (triggers re-sourcing)")
    ap.add_argument("--delay", type=float, default=1.5, help="seconds between events")
    a = ap.parse_args()

    oid, node, d = a.order_id, a.node, a.delay
    tracking = f"1Z{int(time.time())}"
    print(f"order {oid} at {node} ({'signed' if SECRET else 'unsigned'} webhooks)")

    if a.reject:
        wms(node, "store_rejected", oid, reason="no stock on shelf"); return
    wms(node, "pick_started", oid); time.sleep(d)
    if a.short_pick:
        wms(node, "short_pick", oid, reason="bin empty"); return
    wms(node, "packed", oid); time.sleep(d)
    extra = {"carrier": "UPS", "service": "ground", "tracking": tracking}
    if a.partial:
        line_id, qty = a.partial.split(":")
        extra["lines"] = [{"lineId": line_id, "qty": int(qty)}]
    wms(node, "shipped", oid, **extra); time.sleep(d)
    carrier("in_transit", oid, tracking); time.sleep(d)
    carrier("out_for_delivery", oid, tracking); time.sleep(d)
    carrier("delivered", oid, tracking)
    print("done; check GET /api/v1/orders/%s/view" % oid)


if __name__ == "__main__":
    main()
