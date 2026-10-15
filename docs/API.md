# API reference

Base path `/api/v1`. JSON in and out. Errors are RFC 9457 problem details with a `code` (for example
`validation_failed`, `insufficient_atp`, `guardrail_violation`) and optional `fields` or detail maps. List endpoints
accept `offset` and `limit` and return `Page<T> = {items, total, offset, limit}`. Mutations accept an optional
`Idempotency-Key` header. Interactive documentation is served at `/swagger-ui` (`/v3/api-docs` for the OpenAPI
document). This page lists the surface by module with the request shapes that matter; `docs/MODULE_NOTES.md` is the
authoritative as-built record.

## Authentication

| Method | How | Who |
|---|---|---|
| Session JWT | `POST /api/v1/auth/login {tenantId, userId, password}` → `{token, principal}`; send `Authorization: Bearer <token>`; `429 rate_limited` after 10 attempts/min per IP or per user; `POST /api/v1/auth/logout` → 204 (client-side session end) | People (local accounts or `OMS_DEV_USERS`) |
| Local accounts | `POST /api/v1/auth/users {userId, displayName?, password, roles[]}` (needs `config.admin`, 201); `POST /api/v1/auth/change-password {currentPassword, newPassword}` → 204 (min 12 chars; 403 for SSO principals) | Administrators, people |
| OIDC | Bearer access token from the configured issuer; roles mapped from claims | People via SSO |
| API key | `POST /api/v1/auth/api-keys {subjectId, type?, roles?, permissions?, trustTier?, label}` (needs `config.admin`) → `{keyId, apiKey}`; send `X-Api-Key: oms_...` or as bearer; `DELETE /api/v1/auth/api-keys/{keyId}` | Integrations, agents |
| Agent signature | `X-Agent-Id`, `X-Agent-Timestamp`, `X-Agent-Signature` (section "Agents") | Agents on `/mcp` and `/ucp/**` |
| Webhook signature | `X-Webhook-Signature` / `X-Shopify-Hmac-Sha256` (section "Webhooks") | WMS, carriers, inventory, Shopify |

`GET /api/v1/auth/me` returns the current principal `{type, id, tenantId, roles, permissions, trustTier}`. The tenant
is taken from the principal; unauthenticated public endpoints (`/api/v1/promise`, webhooks, `/.well-known/ucp`) take
`X-Tenant-Id`.

## Orders (oms-order)

| Endpoint | Body / params | Notes |
|---|---|---|
| `POST /orders` | `CaptureRequest` (below) | 201 with the order; 200 on idempotent replay; 422 `validation_failed` with `issues[]` |
| `GET /orders` | `q, status, channel, orderType, nodeId, customerRef, placedFrom, placedTo, atRisk, ageBucket, offset, limit` | `Page<OrderSummary>` with `total, lineCount, nodeIds, held, atRisk, riskScore, promiseDate, agentId` |
| `GET /orders/{id}` | | Canonical order |
| `GET /orders/{id}/view` | | `OrderView{order, header, lineStatuses[], fulfilmentGroups[], holds, shipments, payments, refunds, returns, decisions, workflow, timeline, ledger, communications, risk, nextExpectedEvent}` |
| `GET /orders/{id}/timeline` | | Merged order/workflow/decision timeline |
| `POST /orders/{id}/modifications/check` | `Modification` | `Feasibility{allowed, reasons, costDelta, requiresApproval, carrierIntercept?, newPromise?}` |
| `POST /orders/{id}/modifications` | `{modification: Modification, reason}` | Applies and signals the workflow `order.modified` |
| `POST /orders/{id}/cancel` | `{reason}` | |
| `POST /orders/{id}/holds` | `{type, blocks[], reasonCode, note, ttl}` | `blocks` are trigger names or state ids (`release`, `event.shipped`) |
| `POST /orders/{id}/notes` | `{text, source?, author?, customerVisible?}` | |
| `POST /orders/{id}/signals` | `{name, payload}` | Raw workflow signal (ops); prefer webhooks |
| `GET/PUT /order-types[/{name}]` | `OrderType` | |
| `GET/PUT /catalog[/{sku}]` | `{sku, name, attributes}` | |
| `GET/PUT/DELETE /extension-schema` | JSON Schema | Validates `extensions` on capture |

`CaptureRequest`:

```json
{"channel":"web","channelOrderId":"WEB-1001","orderType":"standard_ship","customerRef":"cust-1001",
 "customer":{"ref":"cust-1001","name":"...","email":"...","phone":"...","tier":"gold","ageVerified":true,"age":34,"history":{"split_acceptance_rate":0.2,"returns_12m":1}},
 "lines":[{"lineId":"1","sku":"HOOD-GRY-M","description":"...","qty":2,"unitPrice":{"amount":68.00,"currency":"USD"},"discount":{"amount":0,"currency":"USD"},"taxCode":"...","attributes":{"urgent":true}}],
 "addresses":[{"type":"ship_to","name":"...","line1":"...","line2":"...","city":"...","state":"IL","zip":"60606","country":"US","phone":"...","addressType":"residential"}],
 "charges":[{"type":"shipping","code":"GROUND","amount":{"amount":9.90,"currency":"USD"},"description":"..."}],
 "tenders":[{"type":"card","token":"tok_visa_ok","amount":{"amount":320.00,"currency":"USD"},"details":{}}],
 "notes":[{"source":"customer","author":"...","text":"...","customerVisible":true}],
 "intentChoices":["ship_complete"],
 "shipMethod":"ground","placedAt":"2026-10-06T10:00:00Z","currency":"USD","extensions":{},
 "provenance":{"agentId":"...","protocol":"mcp","mandateId":"...","spendLimit":{"amount":400,"currency":"USD"},"instructionText":"...","signature":"...","buyerRef":"..."}}
```

Tender types: `card, paypal, gift_card, net_terms, agent_token`. Mock gateway tokens ending in `_decline`,
`_expired`, `_refund_fail` force the corresponding failure. `Modification` variants (`type` discriminator):
`ChangeAddress{address}`, `ChangeQty{lineId, qty}`, `CancelLine{lineId}`, `CancelOrder{}`,
`ChangeShipMethod{shipMethod}`, `ChangeItem{lineId, newSku}`.

### Customer tracking links (oms-order)

| Endpoint | Body / params | Notes |
|---|---|---|
| `POST /orders/{id}/tracking-link` | | `{url, token, expiresAt}`; `order.read`. Stateless HMAC-SHA256 token over `tenant|orderId|exp` signed with the JWT secret, valid 30 days; `url` uses tenant config `tracking.public_base_url` when set, else the request origin (`/track/<token>`) |
| `GET /public/track/{token}` | | **Unauthenticated**; tenant resolved from the token, never from headers; 60 requests/min per client IP (429 `rate_limited`). Returns `TrackingView{orderNumber, status, statusLabel, stage placed\|confirmed\|shipped\|delivered\|cancelled, pickup, placedAt, promise{earliest, latest, shipMethod}, items[{name, qty, status}], shipments[{carrier, service, tracking, trackingUrl, status, shippedAt, deliveredAt, eta, items[]}], pickupLocation{storeName, city, state}, narrative, narrativeGenerated, nextStep, expiresAt, generatedAt}` — customer-safe only (no costs, margins, node ids, holds or decisions). Malformed, tampered, expired or unknown tokens all answer 404 `not_found` |

## Sourcing and promise (oms-sourcing)

| Endpoint | Body / params | Notes |
|---|---|---|
| `POST /promise` | `{lines[{sku, qty}], destinationZip, destinationState, channel, shipMethod}` | Public with `X-Tenant-Id`; `Promise{date, onTimeProbability, fromNodeId, shipMethod, fromCache, latencyMs, alternatives[]}` |
| `GET /promise/cache` | | Cache statistics |
| `GET /orders/{id}/sourcing/options` | | Plans for each policy with economics |
| `GET /orders/{id}/sourcing/plans` | | Plans considered for the order with `chosen` |
| `POST /orders/{id}/sourcing` | | Re-run sourcing |
| `POST /orders/{id}/resource` | `{reason, nodeId?}` | Penalise `nodeId` and re-source |
| `GET /nodes?includeInactive=` · `GET/PUT /nodes/{id}` | node definition (same shape as `nodes.yaml`) | `PUT` creates or replaces; set `active: false` to take a node out |
| `POST /nodes/{id}/health` | `{backlog, pick_rate, reject_rate, staffing, weather, carrier_capacity}` | Merge health signals |
| `POST /nodes/{id}/penalty` · `DELETE /nodes/{id}/penalties?reason=` | `{sku, delta, reason}` | Learned penalties |
| `GET/PUT /shipping-rates` · `GET /shipping-rates/quote?service&distanceKm&weightKg` | rate card document | |

## Inventory (oms-inventory)

| Endpoint | Body / params |
|---|---|
| `GET /inventory/network?skus=A,B` | `{sku: available}` with channel buffer applied |
| `GET /inventory/{sku}` · `GET /inventory/{sku}/nodes/{nodeId}` | `Atp{sku, nodeId, onHand, inbound, reserved, allocated, safety, available, confidence}` |
| `POST /inventory/feed` | `{source, positions[{sku, nodeId, supplyType on_hand\|po\|asn\|in_transit, demandType?, qty, confidence?, asOf?}]}` |
| `POST /inventory/adjust` | `{sku, nodeId, delta, reason}` |
| `POST /inventory/reconcile` | `{positions[]}` → drift report |
| `POST /reservations` | `{source, refId, ttlSeconds?, sku, nodeId, qty}` or `{..., lines[{sku, nodeId, qty}]}` (atomic); 409 `insufficient_atp` |
| `GET /reservations?refId=` · `GET /reservations/{id}` · `POST /reservations/{id}/release {reason}` · `/convert {orderId}` · `/consume` | |

## State models and holds (oms-statemachine)

| Endpoint | Body |
|---|---|
| `GET /state-models` | `[{modelId, latestVersion, entity, quantityLevel, states, transitions, versions[]}]` |
| `GET /state-models/{id}` · `GET /state-models/{id}/versions/{v}` | Definition with YAML |
| `POST /state-models/validate` | `{yaml}` → `{ok, issues[{severity, code, message, ref}]}` |
| `POST /state-models/publish` | `{yaml, reason}`; 422 with `issues` when static checks fail |
| `POST /state-models/{id}/migrate` | `{fromVersion, toVersion, dryRun}` → `{total, migrated, blocked, entities[]}` |
| `GET /entities/{type}/{id}/status` · `GET .../transitions` | Status with quantity buckets; available transitions |
| `POST /entities/{type}/{id}/transitions` | `{trigger, qty?, fromState?, vars?, correlationId?, causationId?}` |
| `POST /holds` | `{type, scopeType, scopeId, blocks[], reasonCode, note, ttl}` |
| `GET /holds?scopeType&scopeId` · `GET /holds/{id}` · `POST /holds/{id}/release {resolution}` | |

Line entity ids are `<orderId>:<lineId>`; entity types `order, order_line, return, return_line, shipment, payment, refund`.

## Workflows, tasks, queues, bulk (oms-workflow)

| Endpoint | Body |
|---|---|
| `GET /workflows/{id}` · `GET /workflows/{id}/timeline` · `GET /workflows/by-key/{type}/{key}` | `WorkflowView{status RUNNING\|WAITING_SIGNAL\|WAITING_TIMER\|WAITING_TASK\|COMPENSATING\|COMPLETED\|FAILED\|COMPENSATED\|CANCELLED, currentStep, vars, nextWakeAt, lastError}` |
| `POST /workflows/{id}/signal` | `{name, payload, idempotencyKey}` |
| `POST /workflows/{id}/retry` · `POST /workflows/{id}/cancel {reason}` | |
| `GET /tasks?queue&status&assignee&subjectType&subjectId` · `GET /tasks/{id}` | `HumanTask{id, type, queue, subjectType, subjectId, title, payload, status OPEN\|CLAIMED\|COMPLETED\|CANCELLED\|EXPIRED, assignee, slaDueAt, ...}` |
| `POST /tasks` | `{type, queue, subjectType, subjectId, title, payload, sla, requiredSkills, priority}` |
| `POST /tasks/{id}/claim {assignee}` · `/complete {outcome, reason, details}` · `/cancel {reason}` | Completion signals the attached workflow `task:<taskId>` |
| `GET /queues` · `GET/PUT /queues/{name}` | `{defaultSlaMinutes, escalationQueue, skills[], description}` |
| `POST /bulk-operations` | `{action, subjectIds[], params, dryRun, perSecond}` → `BulkOperation{id, status, dryRun, total, succeeded, failed, results[{subjectId, status, message}]}` |
| `GET /bulk-operations[/{id}]` · `GET /bulk-operations/actions` · `POST /bulk-operations/{id}/rollback` | Actions: `resource, repromise, hold, release, cancel` |

## Decisions and the studio (oms-decision)

| Endpoint | Body | Notes |
|---|---|---|
| `GET /decisions` | | Summaries `{name, liveVersion, latestVersion, status, tier, owner, provider, volume30d, overrideRate, escalationRate, killSwitch}` |
| `GET /decisions/providers` | | `{jev: bool, "llm.anthropic": bool, "llm.openai": bool, heuristic: true}` |
| `GET /decisions/{name}` · `GET /decisions/{name}/versions/{v}` | | Versions; a version carries `definition` (parsed, with `yaml`) and studio state (`approvals`, `evaluationReport`, `rollout`) |
| `POST /decisions/{name}/drafts` | `{yaml}` | Next version as `draft`; YAML `decision` must equal `{name}` |
| `POST /decisions/{name}/versions/{v}/yaml` | `{yaml}` | Replace a draft's YAML |
| `PATCH /decisions/{name}/versions/{v}` | `{"bands.act.confidence_gte": 0.85, ...}` | Dotted-path edits; `decision`, `version`, `status`, `canaryPercent` are managed |
| `POST /decisions/{name}/lint` · `POST .../versions/{v}/lint` | `{yaml}` / none | `{ok, issues[{severity, code, message, path}]}` |
| `POST .../versions/{v}/impact` | `{days?, limit?}` | `{sampled, flips, flipRate, bandMixBefore, bandMixAfter, escalationsBefore, escalationsAfter, costDeltaUsd, latencyDeltaMs}` |
| `POST .../versions/{v}/evaluate` | | `{goldenSet, cases, accuracyInActBand, calibrationError, passed, gateFailures[], bins[10]}` |
| `POST .../versions/{v}/submit` | `{reason, ticket}` | Both mandatory (FR-PRM-06) |
| `POST .../versions/{v}/approve` | | Submitter refused (GR-14); T2/T3 need two distinct approvers |
| `POST .../versions/{v}/publish` | `{rollout: "shadow" \| "canary:<1-99>" \| "live", emergency?}` | Requires approvals, a passed evaluation and clean lint; `emergency: true` (config.admin) skips them and schedules a 48 h retro review |
| `POST /decisions/{name}/rollback` | `{reason}` | Most recently retired version becomes live |
| `POST /decisions/{name}/kill-switch` | `{on, reason?}` | Decision falls to its `default` |
| `POST /decisions/{name}/evaluate-now` | `{input, mode LIVE\|SHADOW\|SIMULATION\|REPLAY, version?, subjectType?, subjectId?}` | Ad-hoc evaluation; `DecisionResult` |
| `GET /decisions/{name}/analytics?days=` · `POST .../versions/{v}/drift-check` | | Per-version analytics; calibration drift |
| `GET /decision-records?subjectType&subjectId` · `GET /decision-records/{id}` | | Records for a subject |
| `GET /decision-records/{id}/why?audience=internal\|customer` | | `DecisionTrace` with inputs (tokenised), questions, answers with probabilities, rules fired, checks, band, action, layers, escalation, override, label, explanation |
| `POST /decision-records/{id}/label` | `{label, details}` | Outcome labels feed calibration (FR-DEC-10) |
| `POST /decision-records/{id}/override` | `{value, reason}` | Needs `decision.override`; recorded (GR-13) |
| `GET /golden-sets` · `GET/PUT/DELETE /golden-sets/{name}` | `[{caseId, input, expected{value}, notes}]` | |

`DecisionResult`: `{recordId, decision, version, layer L0_RULE|L1_SYSTEM_ONE|L2_LLM|L3_HUMAN|DEFAULT, band ACT|CONFIRM|ESCALATE,
value, answers{key: {type, value, probabilities, confidence, legend}}, confidence, action act|confirm|escalate|blocked|default,
requiresHuman, escalationId, rulesFired[], reasonCodes[], latencyMs, costUsd, provider, modelVersion, details}`.

## Returns (oms-returns)

| Endpoint | Body |
|---|---|
| `POST /returns` | `{orderId, lines[{lineId, qty, reasonCode?, customerReasonText?}], channel web\|store\|agent\|cs\|ucp, receiptPresent, storeNodeId?, customerReasonText?, agentId?, mandateId?}` → `ReturnRecord` |
| `POST /returns/in-store` | `{orderId?, channel, channelOrderId?, lines[], storeNodeId, receiptPresent}` |
| `GET /returns?status&orderId&customerRef` · `GET /returns/{rma}` · `GET /returns/for-order/{orderId}` | |
| `POST /returns/{rma}/decide` | Runs L0 policy + `return_risk`; sets `refundTiming` (`instant, on_carrier_scan, on_inspection, store_credit_only, escalate`) or opens a `risk_review` task |
| `POST /returns/{rma}/scan` · `/receive {nodeId}` · `/inspect {condition, note}` · `/refund` · `/approve-refund` | Lifecycle |
| `POST /returns/{rma}/disposition {disposition?, reason?}` | Runs or sets `return_disposition` |
| `POST /returns/{rma}/deny {approved, reason}` · `/review {refundTiming\|"reject", reason}` · `/reject {reason}` · `/cancel {reason}` | Task outcomes |
| `GET /returns/{rma}/customer-message` | Customer-safe text with typed slots (GR-15) |

## Finance (oms-finance)

| Endpoint | Body |
|---|---|
| `GET /payments?orderId` · `GET /payments/{id}` | Authorisations and captures |
| `GET /refunds?orderId` · `POST /refunds` | `{orderId, amount, currency, reason, returnId?}`; SpendGuard applies (`guardrail_violation`) |
| `POST /tax/quote` | `TaxRequest{lines[{lineId, sku, amount, taxCode}], shipping, destination{state, zip, country, line1, city}, customerCode}` |
| `POST /pnl/compute` | `PnlInput` |
| `GET /compliance/rules` · `PUT /compliance/rules {yaml}` · `POST /compliance/check` | `ComplianceInput{lines[{lineId, sku, qty, attributes}], destination{state, zip, country, addressType}, customer{ageVerified, age}, shipMethod}` |
| `GET/PUT/DELETE /tariffs[/{id}]` · `GET /tariffs/estimate?origin&dest&hsCode&value&currency` | Landed cost |

## Control tower, exceptions, playbooks, SLAs, alerts (oms-monitoring)

| Endpoint | Body / params |
|---|---|
| `GET /control-tower/pipeline?channel&orderType&nodeId` | `{states[], ageBuckets[], cells[{state, ageBucket, count}], byChannel, byNode, byOrderType, openOrders, atRisk, held, generatedAt}` |
| `GET /control-tower/at-risk?limit` · `POST /control-tower/sweep` | `AtRiskOrder[]{orderId, riskScore 0–4, riskLevel, reasons[], ageMinutes, decisionRecordId}` |
| `GET /exceptions?status&type&queue&orderId` · `GET /exceptions/{id}` · `GET /exception-types` | Rows carry `classificationRecordId, playbook, playbookSteps[{action, status, detail, gatedBy}], slaDueAt, taskId` |
| `POST /exceptions` | `{source, code, message, orderId?, lineId?, nodeId?, details?, type?}` (`type` null → classify) |
| `POST /exceptions/{id}/assign {assignee}` · `/resolve {resolution, note}` · `/run-playbook` | |
| `GET /playbooks` · `GET/PUT /playbooks/{name} {description, steps[]}` · `GET /playbooks/-/actions` | |
| `GET /sla-definitions` · `PUT /sla-definitions {orderType, entity, state, maxAge, onBreach, expectedNextEventWithin}` · `DELETE /sla-definitions/{id}` | |
| `GET /alerts` | Recent alerts |

## Agents, mandates, MCP, UCP (oms-agent)

| Endpoint | Body |
|---|---|
| `GET /agents` · `GET /agents/{id}` · `GET /agents/{id}/activity?limit` | `AgentIdentity{id, owner, protocol, verification hmac\|public_key, trustTier 0–3, limits{allowedActions[], maxOrderValueUsd, maxRefundUsd, ratePerMinute, confirmActions[]}, active, callbackUrl, orders30d}` |
| `POST /agents` | `{id, owner, protocol ucp\|acp\|mcp, trustTier, limits, callbackUrl?, publicKey?}` → `{agent, secret, apiKey, apiKeyId, note}` (secret shown once) |
| `PUT /agents/{id}` | `{owner?, trustTier?, limits?, callbackUrl?, publicKey?, active?}` |
| `POST /agents/{id}/rotate-secret` · `POST /agents/{id}/suspend {reason}` | |
| `GET /mandates?agentId` · `GET /mandates/{id}` · `POST /mandates/{id}/revoke` | |
| `POST /mandates` | `{agentId, buyerRef, scope, spendLimit{amount, currency}, instructionText, signature?, paymentToken?, expiresAt \| ttl}` |
| `GET /orders/{id}/evidence-pack` | Identity, mandate (with `signatureValid`), instruction text, decision trail, delivery proof, ledger digest; 404 without provenance |
| `POST /orders/{id}/agent-verification` · `POST /orders/{id}/agent-verification/confirm {approved, reason}` | Runs or resolves the `agent_mandate_match` review |

### MCP server

`POST /mcp` with JSON-RPC 2.0 (protocol `2025-06-18`; `2025-03-26` and `2024-11-05` accepted). Methods `initialize`,
`notifications/initialized`, `ping`, `tools/list`, `tools/call`. `GET /mcp` is 405 (no SSE), `DELETE /mcp` is 204.
Tool results are `{content:[{type:"text", text:<json>}], structuredContent, isError}`; application errors come back as
JSON-RPC errors with `data.reason` (`limit_exceeded`, `mandate_invalid`, `not_owner`, `tool_not_allowed`, ...).

Request signing (`HmacSigner`): string to sign `timestamp + "\n" + METHOD + "\n" + path + "\n" + sha256hex(body)`;
signature = lower-case hex HMAC-SHA256 under the agent secret; headers `X-Agent-Id`, `X-Agent-Timestamp` (epoch
seconds, millis or ISO-8601; ±300 s), `X-Agent-Signature` (optionally prefixed `sha256=`). `path` excludes the query
string. `X-Tenant-Id` is required when the agent id exists in more than one tenant.

| Tool | Arguments | Permission / limit |
|---|---|---|
| `order_get` | `{orderId}` or `{channel, channelOrderId}` | order.read; tier-0 agents only see their own orders |
| `order_search` | `{q?, status?, channel?, customerRef?, limit?}` | order.read |
| `promise` | `{lines[{sku, qty}], destinationZip, destinationState?, channel?, shipMethod?}` | promise.read |
| `order_create` | `{order: CaptureRequest subset, mandateId, buyerRef?, instructionText?, idempotencyKey?, buyerAcknowledgement?}` | order.create; mandate scope and `spendLimit`, `maxOrderValueUsd`; `confirmActions` → `{status: pending_confirmation, taskId, queue}` |
| `order_modify` | `{orderId, modification{type ChangeAddress\|ChangeQty\|CancelLine\|ChangeShipMethod\|ChangeItem, ...}, reason?}` | order.modify |
| `order_cancel` | `{orderId, reason}` | order.cancel |
| `return_create` | `{orderId, lines[{lineId, qty, reasonCode?, reasonText?}], reason?, receiptPresent?}` | return.create; text screened and stored as data |
| `inventory_check` | `{skus[]}` | inventory.read |
| `decision_why` | `{recordId}` | decision.read (customer-safe for external agents) |
| `task_list` | `{queue?, status?, limit?}` | task.read |
| `task_complete` | `{taskId, outcome, reason, details?}` | task.work |

`tools/list` returns only the tools in the agent's `allowedActions`.

### UCP order capability

Discovery `GET /.well-known/ucp` (public). Versioned by `UCP-Version` (default `2026-08-25`, echoed on responses).
Bodies are snake_case.

| Endpoint | Body / response |
|---|---|
| `POST /ucp/v1/orders` | `{mandate{id} \| mandate{buyer_ref, scope, spend_limit{amount, currency}, instruction_text, signature?, payment_token?, expires_at}, buyer{ref, email, name, phone, tier}, line_items[{line_id?, sku, quantity, unit_price{amount, currency}, discount?}], ship_to{name, line1, line2, city, state, zip, country, phone}, payment{token, type?, amount}, instructions?, ship_method?, order_type?, client_order_id, charges?[], currency?, buyer_acknowledgement?}` → 201 `{ucp_version, order_id, client_order_id, status, mandate_id, on_hold, order}` or 202 `{status: pending_confirmation, task_id, mandate_id}` |
| `GET /ucp/v1/orders/{id}` | Order with per-line fulfilment, tracking and returns |
| `GET /ucp/v1/orders/{id}/events` | Customer-safe event ledger |
| `POST /ucp/v1/orders/{id}/returns` | `{line_items[{line_id, quantity, reason_code?}], reason?, receipt_present?}` → 201 `{return_id, status, injection_flagged, return}` |

Lifecycle webhooks `order.shipped`, `order.delivered`, `return.created`, `refund.issued` are POSTed to the agent's
`callbackUrl` with `X-UCP-Signature: sha256=<hex HMAC-SHA256(agent secret, body)>`, `X-UCP-Event-Id`,
`X-UCP-Event-Type`, `UCP-Version`.

## Simulation and analytics

| Endpoint | Body |
|---|---|
| `POST /simulations/replay/{orderId}` | `{decisionVersionOverrides{name: version}}` → `{decisions[{before, after, changed}]}` |
| `GET /simulations/rebuild/{orderId}` | Order rebuilt from the ledger |
| `POST /simulations/batch` | `{from, to, overrides{closedNodes[], carrierOutage, tariffDelta}}` → `SimulationJob` |
| `POST /simulations/what-if` | `{scenario node_closure\|carrier_outage\|tariff, nodeId?, nodeIds?, carriers?, tariffDelta?, from, to, sampleLimit?}` |
| `POST /simulations/synthetic` | `{spec, capture}` |
| `GET /simulations` · `GET /simulations/{id}` · `POST /simulations/{id}/cancel` | `{status, progress, results{baseline, simulated, processed}}` |
| `GET /analytics/kpis?range=` | `{range, kpis[{key, label, value, unit, target, targetDirection, trend[], delta, definition}]}` |
| `GET /analytics/decisions` · `GET /analytics/decisions/{name}?days=` | Per-version volume, band mix, layer mix, override and escalation rates, latency p50/p95, cost, accuracy, calibration bins, daily volume |
| `GET /analytics/ai-cost?from&to` | Cost by decision |
| `GET /analytics/export/events?from&to` | NDJSON event export |

## Admin (oms-platform)

### Tenants (platform administrators only)
Any principal holding the wildcard permission (`admin` / `platform_engineer` roles) can create tenants; everything else
in the API is tenant-scoped.

```http
POST /api/v1/admin/tenants
{ "tenantId": "acme", "name": "ACME Outdoors", "admin": { "userId": "admin", "password": "<12+ chars>", "roles": ["admin"] } }
→ 201 { "tenantId": "acme", "created": true, "adminCreated": true, "adminUserId": "admin" }

GET /api/v1/admin/tenants → [{ "tenant_id", "name", "status", "created_at" }]
```
`scripts/new-tenant.sh` uses this endpoint (falling back to psql only when no platform admin login is available).



| Endpoint | Body |
|---|---|
| `GET /admin/config?prefix=` · `PUT /admin/config/{key} {value, reason}` · `DELETE /admin/config/{key}?reason=` | Tenant runtime keys (`docs/CONFIGURATION.md` §3); every write is audited |
| `POST /admin/webhooks {url, eventFilter[]}` → `{id, secret}` · `GET /admin/webhooks` | Outbound subscriptions; filters are event-type prefixes or `*` |
| `GET /admin/outbox` | `{pending, failing[{event_id, type, attempts, last_error, next_attempt_at}]}` |
| `GET /admin/ledger/{aggregateType}/{aggregateId}` | Events of one aggregate |
| `GET /admin/audit?objectType&objectId&limit` | Audit rows |

## Integrations (oms-integrations)

### GraphQL

`POST /graphql`, authenticated exactly like REST (bearer JWT, OIDC token or API key; tenant from the principal or
`X-Tenant-Id`). The schema (`backend/oms-integrations/src/main/resources/graphql/schema.graphqls`) mirrors the REST
JSON of orders, promise, inventory and returns so clients can switch transport without re-mapping. GraphiQL is served
at `/graphiql` when `oms.integrations.graphiql=true` (`OMS_GRAPHIQL`, development only). Permissions are the same as
REST (`order.read`, `order.create`, `order.modify`, `order.cancel`, `promise.read`, `inventory.read`, `return.create`).

| Operation | Signature | REST equivalent |
|---|---|---|
| `order(id: ID!): Order` | one order with `header`, `lineStatuses`, `shipments`, `returns`, `margin` (null for agents) | `GET /orders/{id}/view` |
| `orderByChannelRef(channel, channelOrderId): Order` | | `GET /orders?...` |
| `orders(filter: OrderFilter, offset, limit): OrderPage!` | `OrderFilter{statuses, channel, orderType, customerRef, nodeId, placedFrom, placedTo, text, agentOrders, belowFloor}` | `GET /orders` |
| `promise(input: PromiseInput!): Promise!` | `{lines[{sku, qty}], destinationZip, destinationState, channel, shipMethod}` | `POST /promise` |
| `inventory(skus: [String!]!): [NetworkAtp!]!` | `{sku, available}` | `GET /inventory/network` |
| `returns(orderId: ID!): [ReturnSummary!]!` | | `GET /returns/for-order/{id}` |
| `captureOrder(input: CaptureOrderInput!, idempotencyKey: String): CaptureResult!` | `CaptureOrderInput` mirrors `CaptureRequest`; result `{order, replayed}` | `POST /orders` |
| `modifyOrder(orderId, modification: ModificationInput!, reason: String!): Order!` | `ModificationInput{type, lineId, qty, newSku, shipMethod, address}` | `POST /orders/{id}/modifications` |
| `cancelOrder(orderId, reason): Order!` | | `POST /orders/{id}/cancel` |
| `createReturn(input: ReturnInput!): ReturnRecord!` | `ReturnInput{orderId, lines[{lineId, qty, reasonCode, customerReasonText}], channel, receiptPresent, storeNodeId, ...}` | `POST /returns` |

Scalars: `DateTime` (ISO instant), `Date`, `Decimal` (money amounts), `JSON`. Errors carry the problem code and HTTP
status in `extensions` (`extensions.code = validation_failed | forbidden | not_found | conflict | ...`,
`extensions.status`, plus details such as `issues`); anything unexpected is `internal_error` without internals.

```graphql
mutation Capture($in: CaptureOrderInput!) {
  captureOrder(input: $in, idempotencyKey: "web-1001") {
    replayed
    order { id status intent { policy confidence } lineStatuses { entityId quantities { state qty } } }
  }
}
```

### Connector endpoints

| Endpoint | Body / params | Notes |
|---|---|---|
| `GET /api/v1/integrations/status` | | `config.admin`; which adapters are active (graphql, marketplace, notify, address, carrier, wms) and whether credentials are present; never the values |
| `GET /api/v1/notifications?orderId&limit` | | `order.read`; provider attempts behind an order's communications (`notification_log`: channel, template, tokenised recipient, status `sent\|failed\|skipped_no_provider\|skipped_no_recipient`, provider, providerRef) |
| `GET /api/v1/shipments/{id}/label` | | `order.read`; the aggregator label bought for a shipment (label URL, rate, carrier/service, provider ids) when `oms.integrations.carrier.provider=easypost` |

### Outbound WMS release documents (`oms.integrations.wms.provider=http`)

`HttpWmsConnector` posts a JSON document to `tenant_config` `wms.<nodeId>.release_url` (fallback `wms.release_url`;
cancels to `wms.<nodeId>.cancel_url` / `wms.cancel_url`, defaulting to the release URL):

```json
{"documentType":"release","documentId":"<fulfilmentGroupId>:release","tenantId":"demo","orderId":"...","channelOrderId":"...",
 "orderType":"standard_ship","fulfilmentGroupId":"...","nodeId":"DC-NJ","policy":"ship_complete","shipMethod":"ground","service":"ground",
 "priority":5,"promiseDate":"2026-10-09",
 "allocations":[{"lineId":"1","sku":"HOOD-GRY-M","qty":2,"description":"...","hazmat":false}],
 "shipTo":{"name":"...","line1":"...","line2":null,"city":"...","state":"IL","zip":"60606","country":"US","phone":null,"addressType":"residential"},
 "customer":{"ref":"cust-1001","name":"..."},"giftNote":"...","sentAt":"2026-10-06T12:00:00Z"}
```

`documentType` is `release` or `cancel` (`documentId` `<fulfilmentGroupId>:cancel:<n>`, plus `reason`); `priority`
is 1 (next-day/same-day) to 5 (ground). Headers: `Content-Type: application/json`, `X-Tenant-Id`,
`X-Oms-Document-Id`, `X-Oms-Document-Type`, and `X-Webhook-Signature: sha256=<hex HMAC-SHA256(wms.webhook_secret,
body)>` when the tenant set that key. Any 2xx acknowledges; 4xx other than 408/429 is permanent; 5xx/408/429/I-O errors
retry `oms.integrations.wms.http.retries` times with linear backoff; every attempt is logged in `wms_outbound_log` and an
acknowledged release is never re-sent on a workflow replay. The WMS reports back through `POST /webhooks/wms/{nodeId}`
(below). Full contract: the `HttpWmsConnector` Javadoc.

## Copilot (oms-copilot)

Base path `/api/v1/copilot`. The copilot turns English into reviewable artefacts and never acts on its own: proposals
are **drafts** until a person stages or applies them (GR-14); the LLM is forced into strict JSON schemas and anything
outside them is discarded (GR-04); the text you send and every order fact is framed as data, never as instructions
(GR-08); calls count against the tenant's `ai.budget_usd_per_day` (GR-17); ops questions run only whitelisted,
parameterised SQL templates and the SQL used is returned; numbers in explanations, CS drafts and digests are
post-checked against the stored facts (`generated: false` plus `fallbackReason` when the model was unavailable or its
answer failed the checks). `GET /api/v1/copilot/status` reports whether a model is configured. Prompt templates are
files under `config/copilot/*.md` (`oms.copilot.templates-dir`).

### Configuration changes

| Endpoint | Body / params | Notes |
|---|---|---|
| `POST /copilot/changes` | `{text, scope?, context?{answers{...}, previousChangeSetId}}` | 201 `ChangeSet`; `scope` narrows to a decision or area; `context.answers` answers the `questions` of a previous change set. Needs `decision.edit`, `statemodel.edit` or `config.admin` |
| `GET /copilot/changes?status&offset&limit` · `GET /copilot/changes/{id}` | status `proposed\|staged\|rejected` | `Page<ChangeSet>` |
| `POST /copilot/changes/{id}/stage` | `{changeIds[]}` (empty = all valid) | Decisions become studio drafts (`staged.target = name@version`, then lint → evaluate → submit → approve → publish as usual); everything else becomes a `DraftArtifact`. 422 when a selected change failed validation |
| `POST /copilot/changes/{id}/reject` | `{reason}` | |
| `GET /copilot/changes/{id}/changes/{changeId}/explain` | | Plain-language explanation of one proposed change |
| `POST /copilot/changes/{id}/changes/{changeId}/tests` | | Generates golden-set style `tests[{caseId, input, expected, notes}]` for a decision change (`decision.edit`) |
| `GET /copilot/artifacts?status&changesetId` · `GET /copilot/artifacts/{id}` | status `draft\|applied\|rejected` | Non-decision drafts (state models, order types, SLAs, playbooks, tenant config, compliance rules) |
| `POST /copilot/artifacts/{id}/apply` | `{reason}` | Applies through the same services as the REST endpoints (`config.admin` or `statemodel.publish`); audited |
| `POST /copilot/artifacts/{id}/reject` (alias `/discard`) | `{reason}` | |

`ChangeSet{id, request, summary, assumptions[], questions[], changes[ProposedChange], createdBy, createdAt, status, source, meta}`;
`ProposedChange{id, kind, target, rationale, before, after, diff, validation{ok, issues[], warnings[]}, tests[], riskTier, requiresApprovals, repaired, staged{...}, stageError}`;
`kind` is one of `decision_rule, decision_criteria, decision_bands, decision_new, state_model, order_type, sla, playbook, tenant_config, compliance_rule`.
A `decision_bands` change is only ever a proposal: thresholds change when a human stages and publishes the draft.
`DraftArtifact{id, changesetId, changeId, kind, target, content, summary, status, createdBy, createdAt, appliedBy, appliedAt, applyReason, result}`.

```bash
oms -X POST $BASE/api/v1/copilot/changes -d '{"text":"Gold customers returning under $250 with a receipt should get an instant refund","scope":"return_risk"}'
# → 201 {"id":"cs_...","summary":"...","changes":[{"id":"c1","kind":"decision_rule","target":"return_risk","validation":{"ok":true,...},"riskTier":"T2","requiresApprovals":2,...}],"status":"proposed"}
oms -X POST $BASE/api/v1/copilot/changes/cs_.../stage -d '{"changeIds":["c1"]}'      # → change.staged.target = "return_risk@3" (a studio draft)
```

### Tenant bootstrap

| Endpoint | Body | Notes |
|---|---|---|
| `POST /copilot/bootstrap/preview` | `BusinessDescription` | `TenantBlueprint{summary, assumptions[], questions[], sections{...}, changes[ProposedChange], generated}` without persisting anything |
| `POST /copilot/bootstrap` | `BusinessDescription` | 201 `ChangeSet`; staged when `stage: true` (drafts and studio drafts created), otherwise `proposed` |

`BusinessDescription{text, channels[], nodes[{id, name, type, ...}], categories[], returnWindowDays, restockingFeePct, keepItEnabled, marginFloor, hazmat, ageRestricted, international, stage}`.
Only `text` is required; the structured fields pin facts the model must not infer. The blueprint is derived from
`config/copilot/starter-blueprint.yaml` and the starter tenant.

### Operations, explanations, customer service, digest

| Endpoint | Body / params | Notes |
|---|---|---|
| `GET /copilot/ops/templates` | | The whitelisted query templates `{name, description, sql, params[{name, type, required, description}], keywords[], example}` |
| `POST /copilot/ops/ask` | `{question}` | `OpsAnswer{answer, queryName, parameters, sql, rows[], rowCount, generatedAt, generated, model, selectionReason}`: the model only selects a template and binds its parameters; the SQL that ran is returned (`analytics.read` or `order.read`) |
| `GET /copilot/explain/{recordId}?audience=internal\|customer` | | `Explanation{text, audience, generated, recordId, model, traceUrl, fallbackReason}` built from the stored decision trace; customer audience obeys GR-15 (`decision.read`) |
| `POST /copilot/cs/draft` | `{orderId, message, intent?}` | `CsDraft{draft, factsUsed[], slots, disclaimer, intent, generated, model, fallbackReason, facts}`; `intent` defaults to the `customer_request_intent` decision over `message`; slots are filled from order facts only (`order.read`) |
| `GET /copilot/digest?days=7` · `POST /copilot/digest/run?days=7` · `GET /copilot/digest/latest` · `GET /copilot/digest/history?limit=10` | | `Digest{id, period, headline, findings[{title, metric, value, delta, evidence, suggestedPrompt}], generated, model, facts, generatedAt, fallbackReason}` over KPIs and decision analytics; `run` persists (`analytics.read`) |
| `GET /copilot/status` | | Model configured, enabled, templates loaded, recent usage |

Properties (`oms.copilot.*`): `enabled`, `api-key` (defaults to `OPENROUTER_API_KEY`, then `OPENAI_API_KEY`),
`base-url` (chosen by key prefix: `sk-or-` → `https://openrouter.ai/api/v1`, else `https://api.openai.com/v1`),
`model` (default `anthropic/claude-sonnet-4.5` via OpenRouter, `gpt-5` direct), `timeout-ms`, `max-retries`,
`max-output-tokens`, `templates-dir`, `starter-dir`. Without a key every endpoint still answers with `generated: false`
and deterministic content where possible (templates, facts, traces).

## Webhooks (inbound)

All inbound webhooks are public paths that authenticate themselves. Tenant from `X-Tenant-Id` (Shopify also accepts
`?tenant=`). Idempotent on `eventId` (Shopify: webhook id).

| Path | Signature | Body |
|---|---|---|
| `POST /webhooks/wms/{nodeId}` | `X-Webhook-Signature` = hex or base64 HMAC-SHA256(`webhooks.inbound_secret`, raw body), optional `sha256=` prefix; required when the key is set | `{eventId, type shipped\|short_pick\|store_rejected\|pick_started\|packed\|picked\|staged_for_pickup\|picked_up\|received\|inspected, orderId, fulfilmentGroupId?, lines?[{lineId, qty, sku?}], carrier?, service?, tracking?, rmaId?, reason?, condition?, note?}` |
| `POST /webhooks/carrier` | same | `{eventId, type in_transit\|out_for_delivery\|delivered\|exception\|returned_to_sender\|return_scan, orderId?, tracking, rmaId?, shipmentId?}` |
| `POST /webhooks/inventory/{nodeId}` | same | `{eventId?, source?, asOf?, positions[{sku, qty, nodeId?, supplyType?, demandType?, confidence?, asOf?}]}` or a bare list |
| `POST /webhooks/shopify` | `X-Shopify-Hmac-Sha256` = base64 HMAC-SHA256(`shopify.webhook_secret`, raw body); required when the key is set; `X-Shopify-Topic` must start with `orders/` | Shopify `orders/create` payload, mapped by `ShopifyOrderMapper` |
| `POST /webhooks/marketplace/{marketplace}` | `X-Marketplace-Signature` = hex or base64 HMAC-SHA256(`marketplace.<name>.webhook_secret`, raw body); required when the key is set; tenant via `X-Tenant-Id` or `?tenant=` | An Amazon SP-API Orders v0 order with its `OrderItems` inlined, or the generic shape `{externalOrderId, placedAt, customer{ref, name, email, phone}, lines[{lineId?, sku, qty, unitPrice{amount, currency}\|price, discount?, description?}], shipTo{...}, total{amount, currency}, shipMethod, orderType?, currency?}`. Captured as channel `marketplace`, tender `net_terms` (settlement `marketplace_settlement`), order type from `marketplace.<name>.order_type`; `201 {orderId, channelOrderId, replayed:false}` or `200 {..., replayed:true}`; mapping failures → 422 `validation_failed` |
| `POST /webhooks/easypost?tenant=<id>` | `X-Hmac-Signature: hmac-sha256-hex=<hex>` over the raw body, required when `carrier.easypost.webhook_secret` is set (EasyPost cannot send custom headers, hence `?tenant=`) | EasyPost `tracker.updated` / `tracker.created` events mapped to carrier events (`in_transit\|pre_transit` → in_transit, `out_for_delivery\|available_for_pickup` → out_for_delivery, `delivered`, `failure\|error\|cancelled` → exception, `return_to_sender` → returned_to_sender; `unknown` ignored); the EasyPost event id is the OMS `eventId`; other event types acknowledged and ignored |

Verification in Python:

```python
import hmac, hashlib
expected = hmac.new(secret.encode(), raw_body, hashlib.sha256).hexdigest()      # WMS/carrier/inventory (hex)
ok = hmac.compare_digest(expected, header.removeprefix("sha256="))
```

## Webhooks (outbound)

Subscriptions created with `POST /admin/webhooks` receive each matching ledger event as a CloudEvent:

```json
{"specversion":"1.0","id":"<eventId>","source":"/tenants/<tenant>/order/<orderId>","type":"ai.intentoms.order.shipped",
 "subject":"<orderId>","time":"...","datacontenttype":"application/json","tenantid":"<tenant>","sequence":12,
 "actor":"system:wms-webhook","correlationid":"<orderId>","data":{...}}
```

Headers: `Content-Type: application/cloudevents+json`, `X-OMS-Signature: sha256=<hex HMAC-SHA256(secret, body)>`,
`X-OMS-Event-Id`. Failures are logged in `webhook_delivery` and retried by the outbox with exponential backoff (up to
`oms.outbox.max-attempts`). Verify with `docs/examples/webhooks/verify_signature.py`. Event types follow
`<aggregate>.<past_tense>` (`order.captured`, `order.sourced`, `order.transitioned`, `hold.placed`, `decision.evaluated`,
`return.refunded`, `sla.breached`, ...); the full vocabulary per module is in `docs/MODULE_NOTES.md`.

## AI economics (oms-analytics)

What the decision fabric automated over a day range, what it cost and what it (estimatedly) saved. `analytics.read`.

| Endpoint | Body |
|---|---|
| `GET /analytics/ai-economics?from&to` | `from`/`to` ISO dates (or instants), inclusive UTC days, default the last 30 days → see below |

```json
{
  "range": {"from": "2026-09-15", "to": "2026-10-14", "days": 30},
  "totalDecisions": 43051, "actingDecisions": 39870, "automationRate": 0.926113, "humanDecisionsAvoided": 2810,
  "overrides": 41, "overrideRate": 0.001028, "escalations": 1203, "escalationRate": 0.027944,
  "totalAiCostUsd": 0.877892, "costPer1kDecisionsUsd": 0.020392, "orders": 412, "costPerOrderUsd": 0.002131,
  "minutesPerHumanDecision": 2.0, "estimatedAnalystHoursSaved": 93.6667,
  "byLayer": [{"layer": "L0_RULE", "count": 2400, "share": 0.055748, "latencyP50Ms": 3, "latencyP95Ms": 9},
              {"layer": "L1_SYSTEM_ONE", "count": 39448, "share": 0.91631, "latencyP50Ms": 412, "latencyP95Ms": 845},
              {"layer": "L2_LLM", "count": 0, "share": 0, "latencyP50Ms": 0, "latencyP95Ms": 0},
              {"layer": "L3_HUMAN", "count": 1203, "share": 0.027944, "latencyP50Ms": 0, "latencyP95Ms": 0},
              {"layer": "DEFAULT", "count": 0, "share": 0, "latencyP50Ms": 0, "latencyP95Ms": 0}],
  "daily": [{"date": "2026-09-15", "byLayer": {"L0_RULE": 80, "L1_SYSTEM_ONE": 1310, "L2_LLM": 0, "L3_HUMAN": 40, "DEFAULT": 0}, "total": 1430}, "..."],
  "decisions": [{"name": "sla_breach_risk", "tier": "T1", "volume": 36877, "automationRate": 0.98, "avgConfidence": 0.91, "costPer1kUsd": 0.01974, "latencyP95Ms": 812, "overrideRate": 0.0, "goldenAccuracy": null}, "..."],
  "assumptions": ["Only live decision records count (shadow, simulation and replay runs are excluded).", "..."]
}
```

Definitions: automation rate = decisions with band ACT / all; human decisions avoided = acting decisions whose definition is T1 or T2; override rate = human overrides / acting
decisions; escalation rate = decisions that required a person / all; AI spend = provider cost recorded on the decision records; cost per order divides it by orders placed in the
range; `estimatedAnalystHoursSaved = humanDecisionsAvoided × analytics.minutes_per_human_decision / 60` (tenant config, default 2 — an estimate, labelled as such in
`assumptions`). `byLayer` always lists the five layers and `daily` one entry per day, so an empty range renders as zeros.

## Margin Replay (oms-simulation)

Upload a CSV export of real orders and get a report of what Intent OMS would have done differently and how much margin it
would have recovered. Nothing from the file is persisted as an order; the result is a `SimulationJob` of kind `import`.
Permission `simulation.run`.

| Endpoint | Body / params | Notes |
|---|---|---|
| `POST /simulations/import` | `multipart/form-data`: `file` (CSV ≤ 10 MB, ≤ 5 000 orders) + either `options` (JSON string) or form fields `format`, `assumeStock`, `unknownSkuCogsRatio`, `baseline`, `useDecisions` | 202 `SimulationJob{id, kind:"import", label, status QUEUED, progress, from, to, …}`; layout problems → 422 `validation_failed` |
| `POST /simulations/import/sample` | `MarginReplayOptions` (optional) | Runs the bundled `docs/examples/data/shopify-orders-sample.csv` (300 orders) |
| `GET /simulations/{id}` | | Poll `progress` (0..1) and `results` (partial while RUNNING, `results.partial=true`) |

`MarginReplayOptions{format: auto|shopify|generic (auto), assumeStock: true, unknownSkuCogsRatio: 0.6, baseline: "nearest_node" | "single_dc:<nodeId>", useDecisions: true}`.
`assumeStock=true` treats every active node as stocked for every SKU, so the comparison is about network, node economics and policy, not today's inventory.

Accepted layouts: Shopify "Orders export" (`Name, Email, Created at, Lineitem quantity, Lineitem name, Lineitem price, Lineitem sku, Shipping Address1, Shipping City, Shipping Province [Code], Shipping Zip, Shipping Country, Shipping Method, Notes, Total, Shipping`; rows with the same `Name` are one order) or generic (`order_id, placed_at, sku, qty, unit_price, city, state, zip, country, ship_method, note, customer_tier` + optional `name, shipping_paid`). Destinations are geocoded to US city centres (else ZIP3 / state centroid); non-US orders are skipped with a reason.

`results` (see MODULE_NOTES "Margin Replay" for every field):

```json
{"kind":"import","options":{...},"file":{"name":"orders.csv","format":"shopify","rows":466,"orders":300,"warnings":[]},
 "summary":{"orders":300,"planned":297,"skipped":3,"skippedReasons":{"cancelled":1,"non-US destination (CA)":1},
   "baselineShippingCost":2510.4,"intentShippingCost":2461.2,"shippingSaved":49.2,"marginRecoveredUsd":312.7,"marginRecoveredPerOrder":1.05,
   "splitsAvoided":0,"splitsAdded":9,"ordersBelowFloorFlagged":7,"intentsDetected":{"gift":9,"urgent":9,"together":7,"pickup":5},
   "avgOnTimeBefore":0.91,"avgOnTimeAfter":0.93,"annualisationDays":30,"estimatedAnnualisedSavings":3804.5,"estimateLabel":"Estimate: ..."},
 "topFindings":["297 orders over 30 days: $312.70 of contribution margin recovered ...","23 orders to California shipped from ST-LA-02; routing them from DC-NV recovers $29.85.", "..."],
 "byRegion":[{"state":"CA","region":"California","orders":58,"changed":40,"baselineCost":812.1,"intentCost":770.3,"saved":41.8}],
 "assumptions":["Every active node is assumed to hold stock of every SKU (assumeStock=true) ...","..."],
 "perOrder":[{"orderId":"#1001","state":"CA","nodesBefore":["ST-LA-02"],"nodesAfter":["DC-NV"],"packagesBefore":1,"packagesAfter":1,"costBefore":12.1,"costAfter":9.6,
   "marginBefore":0.31,"marginAfter":0.34,"marginDeltaUsd":2.5,"onTimeBefore":0.91,"onTimeAfter":0.94,"intent":"none","belowFloor":false,"poBox":false,"changed":true,"reason":"..."}],
 "markdownSummary":"# Margin Replay — orders.csv ...","partial":false,"processed":300,"durationMs":1400}
```

The numbers above are illustrative; the figures for the bundled sample come from `MarginReplayIntegrationTest`. The UI page is `/replay`.

## Agentic Control Tower (oms-orchestration)

Diagnoses *why* a stuck order has stalled (`stuck_order_diagnosis`, see `docs/MODULE_NOTES.md`) and, within guardrails,
acts on its own. Read endpoints need `exception.read`, undo needs `exception.work`, guardrail/kill-switch/scan-now
need `config.admin`.

| Endpoint | Body / params | Notes |
|---|---|---|
| `GET /autonomy/anomalies` | `offset, limit` | `Page<Anomaly>` — the live feed: candidate signal, diagnosis value/confidence/band, and how it resolved |
| `GET /autonomy/actions` | `from, to` (ISO date or instant), `actionType, outcome, offset, limit` | `Page<AutonomousAction>` — the audit trail of executed/attempted autonomous remediations |
| `POST /autonomy/actions/{id}/undo` | `{reason?}` | Reverses an undoable action for real (`re_source`, `re_promise`, `release_hold`); 409 `conflict` naming why when it cannot be undone (already undone, already shipped, no prior state recorded); 422 `validation_failed` for `retry_payment`/`notify_customer` (never undoable) |
| `GET /autonomy/guardrails` | | `{enabled, killSwitch, maxActionsPerDay, maxUsdImpactPerAction, allowedActions[], minConfidenceOverride}` |
| `PUT /autonomy/guardrails` | any subset of the fields above (admin) | Partial update; rejects `refund`/`cancel` in `allowedActions` (422) |
| `POST /autonomy/kill-switch` | `{enabled}` (admin) | Independent emergency stop; blocks autonomous execution even when `enabled` is true |
| `GET /autonomy/stats` | | `{actionsToday, maxActionsPerDay, usdImpactResolvedToday, maxUsdImpactPerAction, humanEscalations, automationRate}` — automation rate for this capability only |
| `POST /autonomy/scan-now` | (admin) | Runs a scan of the current tenant immediately (for live demos) → `{anomaliesFound, anomalies[]}` |

```json
{
  "id": "anom_...", "orderId": "ord_...", "anomalyType": "no_progress", "decisionRecordId": "...",
  "diagnosisValue": "inventory_reallocation_needed", "confidence": 0.95, "band": "ACT", "reasonCodes": [],
  "outcome": "executed", "actionId": "aut_...", "escalationTaskId": null, "escalationQueue": null, "escalationReason": null,
  "detectedAt": "2026-10-14T12:00:00Z"
}
```

```json
{
  "id": "aut_...", "tenantId": "demo", "orderId": "ord_...", "anomalyType": "no_progress", "decisionRecordId": "...",
  "diagnosisValue": "inventory_reallocation_needed", "confidence": 0.95, "actionTaken": "re_source",
  "guardrailSnapshot": {"enabled": true, "killSwitch": false, "maxActionsPerDay": 25, "actionsToday": 3,
    "maxUsdImpactPerAction": 150.0, "allowedActions": ["notify_customer","re_promise","re_source","release_hold","retry_payment"],
    "minConfidenceOverride": null, "estimatedImpactUsd": 86.0},
  "outcome": "executed", "undoable": true, "detail": "re-sourced ord_... away from ST-AUS-03: SOURCED plan plan_... (ship_complete)",
  "executedAt": "2026-10-14T12:00:01Z", "undoneAt": null, "undoneBy": null, "undoReason": null
}
```
