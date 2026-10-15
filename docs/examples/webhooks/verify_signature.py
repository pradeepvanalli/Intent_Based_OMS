#!/usr/bin/env python3
"""Verifies Intent OMS outbound webhook signatures, and runs as a tiny receiver.

Outbound tenant webhooks (WebhookPublisher): CloudEvents JSON, header X-OMS-Signature: sha256=<hex HMAC-SHA256(secret, body)>,
X-OMS-Event-Id. The secret is returned once by POST /api/v1/admin/webhooks {url, eventFilter}.
UCP lifecycle webhooks (UcpWebhookSender): header X-UCP-Signature: sha256=<hex HMAC-SHA256(agent secret, body)>,
X-UCP-Event-Id, X-UCP-Event-Type, UCP-Version.

    verify(secret, raw_body, header_value) -> bool

    WEBHOOK_SECRET=<secret> python3 verify_signature.py --serve 9099
    # then: POST /api/v1/admin/webhooks {"url":"http://host.docker.internal:9099/events","eventFilter":["order."]}
"""
import argparse
import hashlib
import hmac
import json
import os
import sys
from http.server import BaseHTTPRequestHandler, HTTPServer


def verify(secret: str, raw_body: bytes, header_value: str) -> bool:
    """Constant-time check of `sha256=<hex>` (prefix optional) against HMAC-SHA256(secret, raw body)."""
    if not header_value:
        return False
    presented = header_value.strip().lower()
    if presented.startswith("sha256="):
        presented = presented[7:]
    expected = hmac.new(secret.encode(), raw_body, hashlib.sha256).hexdigest()
    return hmac.compare_digest(expected, presented)


class Receiver(BaseHTTPRequestHandler):
    secret = os.environ.get("WEBHOOK_SECRET", "")

    def do_POST(self):
        length = int(self.headers.get("Content-Length", "0"))
        raw = self.rfile.read(length)
        sig = self.headers.get("X-OMS-Signature") or self.headers.get("X-UCP-Signature") or ""
        ok = verify(self.secret, raw, sig) if self.secret else None
        try:
            event = json.loads(raw)
        except json.JSONDecodeError:
            event = {}
        summary = {"type": event.get("type"), "subject": event.get("subject"), "id": event.get("id") or self.headers.get("X-OMS-Event-Id") or self.headers.get("X-UCP-Event-Id")}
        print(f"{'VALID  ' if ok else 'INVALID' if ok is False else 'UNCHECKED'} {summary}", flush=True)
        if ok is False:
            self.send_response(401); self.end_headers(); self.wfile.write(b"bad signature"); return
        # Always 2xx quickly; do real work asynchronously. Deliveries are retried by the outbox on non-2xx.
        self.send_response(204); self.end_headers()

    def log_message(self, *_):  # quiet
        pass


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--serve", type=int, metavar="PORT", help="run a receiver on this port")
    ap.add_argument("--check", nargs=3, metavar=("SECRET", "BODY_FILE", "SIGNATURE"), help="verify one body file against a signature")
    a = ap.parse_args()
    if a.check:
        secret, body_file, signature = a.check
        print("valid" if verify(secret, open(body_file, "rb").read(), signature) else "invalid")
    elif a.serve:
        if not Receiver.secret:
            print("WEBHOOK_SECRET not set: signatures will be reported UNCHECKED", file=sys.stderr)
        print(f"listening on :{a.serve} (POST anything)")
        HTTPServer(("", a.serve), Receiver).serve_forever()
    else:
        ap.print_help()
