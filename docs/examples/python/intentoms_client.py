"""Minimal Intent OMS REST client (standard library only).

    from intentoms_client import IntentOms
    oms = IntentOms("http://localhost:8080", tenant="demo")
    oms.login("admin", "admin")                      # or IntentOms(..., api_key="oms_...")
    order = oms.capture_order({...}, idempotency_key="my-key-1")
    view = oms.order_view(order["id"])

Errors raise ``OmsError`` carrying the RFC 9457 problem detail (``status``, ``code``, ``detail``, ``body``).
Request and response shapes: docs/API.md.
"""
from __future__ import annotations

import json
import urllib.error
import urllib.parse
import urllib.request
from typing import Any, Optional


class OmsError(Exception):
    def __init__(self, status: int, body: Any):
        self.status = status
        self.body = body if isinstance(body, dict) else {"detail": body}
        self.code = self.body.get("code") or self.body.get("title")
        self.detail = self.body.get("detail")
        super().__init__(f"HTTP {status} {self.code or ''}: {self.detail or body}")


class IntentOms:
    def __init__(self, base: str = "http://localhost:8080", tenant: str = "demo", token: Optional[str] = None,
                 api_key: Optional[str] = None, timeout: float = 30.0):
        self.base = base.rstrip("/")
        self.tenant = tenant
        self.token = token
        self.api_key = api_key
        self.timeout = timeout

    # ------------------------------------------------------------------ transport
    def request(self, method: str, path: str, body: Any = None, *, headers: Optional[dict] = None,
                auth: bool = True, params: Optional[dict] = None) -> Any:
        url = self.base + path
        if params:
            url += ("&" if "?" in url else "?") + urllib.parse.urlencode({k: v for k, v in params.items() if v is not None})
        data = None if body is None else json.dumps(body).encode()
        h = {"Accept": "application/json", "X-Tenant-Id": self.tenant}
        if data is not None:
            h["Content-Type"] = "application/json"
        if auth:
            if self.api_key:
                h["X-Api-Key"] = self.api_key
            elif self.token:
                h["Authorization"] = f"Bearer {self.token}"
        h.update(headers or {})
        req = urllib.request.Request(url, data=data, method=method, headers=h)
        try:
            with urllib.request.urlopen(req, timeout=self.timeout) as resp:
                raw = resp.read()
                return json.loads(raw) if raw else None
        except urllib.error.HTTPError as e:
            raw = e.read()
            try:
                parsed = json.loads(raw) if raw else {}
            except json.JSONDecodeError:
                parsed = raw.decode(errors="replace")
            raise OmsError(e.code, parsed) from None

    def get(self, path: str, **params): return self.request("GET", path, params=params or None)
    def post(self, path: str, body: Any = None, **kw): return self.request("POST", path, body, **kw)
    def put(self, path: str, body: Any = None, **kw): return self.request("PUT", path, body, **kw)
    def delete(self, path: str, **kw): return self.request("DELETE", path, **kw)

    # ------------------------------------------------------------------ auth
    def login(self, user: str, password: str) -> dict:
        r = self.request("POST", "/api/v1/auth/login", {"tenantId": self.tenant, "userId": user, "password": password}, auth=False)
        self.token = r["token"]
        return r["principal"]

    def me(self) -> dict: return self.get("/api/v1/auth/me")

    # ------------------------------------------------------------------ orders
    def promise(self, lines: list[dict], destination_zip: str, destination_state: str, channel: str = "web", ship_method: str = "ground") -> dict:
        return self.request("POST", "/api/v1/promise", {"lines": lines, "destinationZip": destination_zip, "destinationState": destination_state,
                                                         "channel": channel, "shipMethod": ship_method}, auth=False)

    def capture_order(self, capture_request: dict, idempotency_key: Optional[str] = None) -> dict:
        headers = {"Idempotency-Key": idempotency_key} if idempotency_key else None
        return self.post("/api/v1/orders", capture_request, headers=headers)

    def order(self, order_id: str) -> dict: return self.get(f"/api/v1/orders/{order_id}")
    def order_view(self, order_id: str) -> dict: return self.get(f"/api/v1/orders/{order_id}/view")
    def order_timeline(self, order_id: str) -> list: return self.get(f"/api/v1/orders/{order_id}/timeline")
    def search_orders(self, **filters) -> dict: return self.get("/api/v1/orders", **filters)
    def check_modification(self, order_id: str, modification: dict) -> dict: return self.post(f"/api/v1/orders/{order_id}/modifications/check", modification)
    def modify(self, order_id: str, modification: dict, reason: str) -> dict: return self.post(f"/api/v1/orders/{order_id}/modifications", {"modification": modification, "reason": reason})
    def cancel(self, order_id: str, reason: str) -> dict: return self.post(f"/api/v1/orders/{order_id}/cancel", {"reason": reason})
    def hold(self, order_id: str, type_: str, blocks: list[str], reason_code: str, note: str = "", ttl: Optional[str] = None) -> dict:
        return self.post(f"/api/v1/orders/{order_id}/holds", {"type": type_, "blocks": blocks, "reasonCode": reason_code, "note": note, "ttl": ttl})
    def release_hold(self, hold_id: str, resolution: str) -> dict: return self.post(f"/api/v1/holds/{hold_id}/release", {"resolution": resolution})

    # ------------------------------------------------------------------ decisions
    def decision_records(self, subject_type: str, subject_id: str) -> list:
        return self.get("/api/v1/decision-records", subjectType=subject_type, subjectId=subject_id)
    def why(self, record_id: str, audience: Optional[str] = None) -> dict: return self.get(f"/api/v1/decision-records/{record_id}/why", audience=audience)
    def evaluate_now(self, name: str, input_: dict, mode: str = "SIMULATION", version: Optional[int] = None) -> dict:
        return self.post(f"/api/v1/decisions/{name}/evaluate-now", {"input": input_, "mode": mode, "version": version})
    def create_draft(self, name: str, yaml_text: str) -> dict: return self.post(f"/api/v1/decisions/{name}/drafts", {"yaml": yaml_text})

    # ------------------------------------------------------------------ returns, tasks, webhooks (inbound mocks)
    def create_return(self, order_id: str, lines: list[dict], channel: str = "web", receipt_present: bool = True, **extra) -> dict:
        return self.post("/api/v1/returns", {"orderId": order_id, "lines": lines, "channel": channel, "receiptPresent": receipt_present, **extra})
    def decide_return(self, rma: str) -> dict: return self.post(f"/api/v1/returns/{rma}/decide")
    def tasks(self, **filters) -> dict: return self.get("/api/v1/tasks", **filters)
    def complete_task(self, task_id: str, outcome: str, reason: str, details: Optional[dict] = None) -> dict:
        return self.post(f"/api/v1/tasks/{task_id}/complete", {"outcome": outcome, "reason": reason, "details": details or {}})

    def wms_event(self, node_id: str, event: dict, secret: Optional[str] = None) -> dict:
        """POST /webhooks/wms/{nodeId}; signs with HMAC-SHA256 when ``secret`` (tenant key webhooks.inbound_secret) is given."""
        return self._webhook(f"/webhooks/wms/{node_id}", event, secret)
    def carrier_event(self, event: dict, secret: Optional[str] = None) -> dict: return self._webhook("/webhooks/carrier", event, secret)

    def _webhook(self, path: str, event: dict, secret: Optional[str]) -> dict:
        import hashlib, hmac
        raw = json.dumps(event, separators=(",", ":")).encode()
        headers = {"Content-Type": "application/json", "X-Tenant-Id": self.tenant}
        if secret:
            headers["X-Webhook-Signature"] = "sha256=" + hmac.new(secret.encode(), raw, hashlib.sha256).hexdigest()
        req = urllib.request.Request(self.base + path, data=raw, method="POST", headers=headers)
        try:
            with urllib.request.urlopen(req, timeout=self.timeout) as resp:
                body = resp.read()
                return json.loads(body) if body else {}
        except urllib.error.HTTPError as e:
            raise OmsError(e.code, e.read().decode(errors="replace")) from None
