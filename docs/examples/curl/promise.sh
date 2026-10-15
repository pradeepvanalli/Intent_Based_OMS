#!/usr/bin/env bash
# Delivery promise at cart time. Public endpoint: no token, tenant from the X-Tenant-Id header (FR-SRC-05).
source "$(dirname "$0")/_lib.sh"
step "POST /api/v1/promise"
curl -s -X POST "$BASE/api/v1/promise" -H 'Content-Type: application/json' -H "X-Tenant-Id: $TENANT" -d '{
  "lines": [{"sku": "HOOD-GRY-M", "qty": 1}, {"sku": "SNK-RUN-10", "qty": 1}],
  "destinationZip": "60611", "destinationState": "IL", "channel": "web", "shipMethod": "ground"
}' | pp
# Response: {date, onTimeProbability, fromNodeId, shipMethod, fromCache, latencyMs, alternatives[{shipMethod, date, onTimeProbability, costDelta}]}
# Repeat the call: fromCache becomes true (30 s cache keyed by sku set + zip + method).
