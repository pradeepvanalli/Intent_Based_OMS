#!/usr/bin/env python3
"""Signed MCP client for a registered agent (FR-AGT-01/03/04, GR-08).

Signing (HmacSigner): string_to_sign = f"{timestamp}\n{METHOD}\n{path}\n{sha256hex(body)}";
X-Agent-Signature = hex(HMAC-SHA256(agent_secret, string_to_sign)); X-Agent-Timestamp is epoch seconds (±300 s).
The path excludes the query string. Send X-Tenant-Id when the agent id exists in more than one tenant.

Usage with the demo agents (secrets are exposed in tenant_config only because oms.agent.demo-expose-secrets=true):
    python3 agent_mcp_client.py                   # reads agents.demo_secrets via the admin API, uses shopbot-ucp
    AGENT_ID=my-agent AGENT_SECRET=... python3 agent_mcp_client.py
"""
from __future__ import annotations

import hashlib
import hmac
import json
import os
import time
import urllib.request
from typing import Any, Optional

BASE = os.environ.get("BASE", "http://localhost:8080")
TENANT = os.environ.get("TENANT", "demo")


class McpAgent:
    def __init__(self, base: str, tenant: str, agent_id: str, secret: str):
        self.base, self.tenant, self.agent_id, self.secret = base.rstrip("/"), tenant, agent_id, secret
        self._id = 0

    def _signed_headers(self, method: str, path: str, body: bytes) -> dict:
        ts = str(int(time.time()))
        body_hash = hashlib.sha256(body).hexdigest()
        to_sign = f"{ts}\n{method.upper()}\n{path}\n{body_hash}"
        sig = hmac.new(self.secret.encode(), to_sign.encode(), hashlib.sha256).hexdigest()
        return {"X-Agent-Id": self.agent_id, "X-Agent-Timestamp": ts, "X-Agent-Signature": sig, "X-Tenant-Id": self.tenant}

    def rpc(self, method: str, params: Optional[dict] = None) -> Any:
        self._id += 1
        payload = {"jsonrpc": "2.0", "id": self._id, "method": method}
        if params is not None:
            payload["params"] = params
        body = json.dumps(payload, separators=(",", ":")).encode()
        headers = {"Content-Type": "application/json", "MCP-Protocol-Version": "2025-06-18", **self._signed_headers("POST", "/mcp", body)}
        req = urllib.request.Request(self.base + "/mcp", data=body, method="POST", headers=headers)
        try:
            with urllib.request.urlopen(req, timeout=30) as resp:
                out = json.loads(resp.read())
        except urllib.error.HTTPError as e:
            raise RuntimeError(f"HTTP {e.code}: {e.read().decode(errors='replace')}") from None
        if "error" in out:
            err = out["error"]
            raise RuntimeError(f"JSON-RPC error {err.get('code')}: {err.get('message')} {err.get('data')}")
        return out["result"]

    def tools(self) -> list[str]:
        return [t["name"] for t in self.rpc("tools/list")["tools"]]

    def call(self, name: str, arguments: dict) -> dict:
        result = self.rpc("tools/call", {"name": name, "arguments": arguments})
        if result.get("isError"):
            raise RuntimeError(f"tool {name} failed: {result.get('structuredContent')}")
        return result["structuredContent"]


def demo_secret(agent_id: str) -> str:
    """Reads agents.demo_secrets through the admin API (demo installs only)."""
    tok = json.loads(urllib.request.urlopen(urllib.request.Request(
        f"{BASE}/api/v1/auth/login", data=json.dumps({"tenantId": TENANT, "userId": "admin", "password": "admin"}).encode(),
        headers={"Content-Type": "application/json"}, method="POST")).read())["token"]
    cfg = json.loads(urllib.request.urlopen(urllib.request.Request(
        f"{BASE}/api/v1/admin/config?prefix=agents.demo_secrets", headers={"Authorization": f"Bearer {tok}"})).read())
    return cfg["agents.demo_secrets"][agent_id]["secret"]


def create_mandate(agent_id: str, buyer_ref: str, limit: float, instruction: str) -> str:
    """Mandates are issued by the merchant side (config.admin / agent.admin); here the demo admin does it."""
    tok = json.loads(urllib.request.urlopen(urllib.request.Request(
        f"{BASE}/api/v1/auth/login", data=json.dumps({"tenantId": TENANT, "userId": "admin", "password": "admin"}).encode(),
        headers={"Content-Type": "application/json"}, method="POST")).read())["token"]
    body = {"agentId": agent_id, "buyerRef": buyer_ref, "scope": {"merchant": TENANT, "categories": ["apparel", "accessories"]},
            "spendLimit": {"amount": limit, "currency": "USD"}, "instructionText": instruction, "ttl": "PT24H"}
    r = urllib.request.urlopen(urllib.request.Request(f"{BASE}/api/v1/mandates", data=json.dumps(body).encode(), method="POST",
                                                      headers={"Content-Type": "application/json", "Authorization": f"Bearer {tok}"}))
    return json.loads(r.read())["id"]


if __name__ == "__main__":
    agent_id = os.environ.get("AGENT_ID", "shopbot-ucp")
    secret = os.environ.get("AGENT_SECRET") or demo_secret(agent_id)
    agent = McpAgent(BASE, TENANT, agent_id, secret)

    print("initialize:", agent.rpc("initialize", {"protocolVersion": "2025-06-18", "capabilities": {}, "clientInfo": {"name": "example", "version": "0"}})["serverInfo"])
    print("tools allowed for", agent_id, "→", agent.tools())

    ts = int(time.time())
    instruction = "Buy the grey hoodie in M and a navy cap for Jordan, ship to the Chicago office, keep it under 400 dollars."
    mandate_id = create_mandate(agent_id, "cust-agent-1", 400, instruction)
    print("mandate", mandate_id)

    order = {"channel": "agent", "channelOrderId": f"MCP-{ts}", "orderType": "standard_ship", "customerRef": "cust-agent-1",
             "customer": {"ref": "cust-agent-1", "name": "Jordan Lee", "email": "jordan@example.com", "tier": "gold"},
             "lines": [{"lineId": "1", "sku": "HOOD-GRY-M", "qty": 1, "unitPrice": {"amount": 68.00, "currency": "USD"}},
                       {"lineId": "2", "sku": "CAP-NVY", "qty": 1, "unitPrice": {"amount": 32.00, "currency": "USD"}}],
             "addresses": [{"type": "ship_to", "name": "Jordan Lee", "line1": "233 S Wacker Dr", "city": "Chicago", "state": "IL", "zip": "60606", "country": "US"}],
             "tenders": [{"type": "agent_token", "token": f"agt_tok_{ts}", "amount": {"amount": 120.00, "currency": "USD"}}],
             "shipMethod": "ground"}
    placed = agent.call("order_create", {"order": order, "mandateId": mandate_id, "buyerRef": "cust-agent-1",
                                         "instructionText": instruction, "idempotencyKey": f"mcp-{ts}"})
    print("order_create →", {k: placed.get(k) for k in ("status", "orderId", "onHold", "taskId", "queue")})

    if placed.get("orderId"):
        got = agent.call("order_get", {"orderId": placed["orderId"]})
        print("order_get →", {k: got.get(k) for k in ("id", "status", "channel")} if isinstance(got, dict) else got)

    print("inventory_check →", agent.call("inventory_check", {"skus": ["HOOD-GRY-M", "CAP-NVY"]}))
