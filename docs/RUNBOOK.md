# Intent OMS — Operations runbook

## Health & observability
* Liveness/readiness: `GET /actuator/health/liveness`, `/actuator/health/readiness`.
* Metrics: `GET /actuator/prometheus` — key series: `oms_decision_latency` (per decision/provider), `oms_decision_evaluated_total{layer,band}`,
  `oms_statemachine_transition`, `oms_workflow_step`, `oms_outbox_delivered_total` / `oms_outbox_failed_total`, `oms_inventory_reserve`, `oms_promise_latency`.
* Traces: OpenTelemetry OTLP exporter (`OTEL_EXPORTER_OTLP_ENDPOINT`), sampling `OTEL_SAMPLING`.
* Logs: JSON-friendly single-line; correlation id = order id on every workflow/decision log line.

## Degraded mode (NFR-05, GR-06/07)
| Symptom | Action |
|---|---|
| Jev 429/529 or latency > 800 ms | Automatic: fallback provider → L0 default. Verify in `/api/v1/analytics/decisions/<name>` layer mix. No action needed unless sustained. |
| Sustained provider outage | `PUT /api/v1/admin/config/provider.kill_switch.jev {"value": true, "reason": "..."}` — all decisions degrade to fallback/default instantly. Re-enable by deleting the key. |
| One decision misbehaving | `POST /api/v1/decisions/<name>/kill-switch {"on": true}` or `POST .../rollback`. |
| AI spend spike | Budget `ai.budget_usd_per_day` is enforced per tenant; when exceeded decisions run on L0 defaults and an alert fires. |
| Refund/appeasement spike | GR-05 circuit breaker opens for 15 min (`guardrail.breaker_open_until`); refunds route to the `refund_approvals` queue. |

Monthly kill-switch drill (GR-06): flip `ai.kill_switch` on for 10 minutes in staging and confirm orders still capture, source and ship.

## Outbox & events
* `GET /api/v1/admin/outbox` shows pending and failing deliveries. Failing rows retry with exponential backoff up to 12 attempts.
* A parked event (attempts ≥ 12) is re-driven by `update outbox set attempts = 0, next_attempt_at = now() where event_id = …`.

## Workflows
* Stuck instance: `GET /api/v1/workflows/{id}/timeline` → `POST /api/v1/workflows/{id}/retry`. Failed instances keep `lastError`.
* In-flight instances stay on their definition version (FR-WF-08). Deploying a new version only affects new starts.

## State models & decisions
* Publishing is gated by static checks / lint / golden set / approvals. Emergency publishes require `config.admin` and are flagged for a 48 h retro review (GR-10).
* Migrating in-flight orders to a new state-model version: `POST /api/v1/state-models/{id}/migrate {from,to,dryRun:true}` first, read the per-entity report, then run without dryRun.

## Data
* Ledger (`event`), `audit_log` and decision traces are append-only (database triggers). Retention 7 years by default (`retention.years`).
* CCPA deletion: `Tokenizer.shred(tenant)` rotates the PII key (crypto-shredding); raw PII lives only in the order tables, which are deleted per request.
* Backups: PostgreSQL PITR; RPO 0 with synchronous replication (NFR-04).

## Load testing
`k6 run -e TOKEN=$(./scripts/token.sh) load/promise.js` — thresholds encode NFR-02 (promise p95 < 300 ms, capture p95 < 200 ms).
