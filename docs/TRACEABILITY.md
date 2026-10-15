# Requirements traceability (PRD v0.1 → implementation)

> **Open-source release note (2026-10).** This matrix is kept as the engineering record of PRD coverage. For adopters,
> the user-facing documentation set is: `README.md`, `docs/GETTING_STARTED.md`, `docs/CONFIGURATION.md`, `docs/API.md`,
> `docs/DECISIONS_GUIDE.md`, `docs/STATE_MODELS_GUIDE.md`, `docs/DEPLOYMENT.md`, `SECURITY.md`,
> `docs/THREAT_MODEL.md` and `docs/COMPLIANCE.md`. Items marked ○ (deferred) are candidates for community
> contributions; see `CONTRIBUTING.md`. The copilot module and the platform hardening work (OIDC, production checks,
> security headers, CI scanning) added for the release extend NFR-07 and GR-14 and are tracked in `CHANGELOG.md`;
> the rows below are unchanged.

Status legend: ✅ implemented · ◐ partial / foundation in place · ○ deferred (P1/P2 not in this build). Module = Maven module under `backend/`.

## 7.1 Order capture and canonical order model
| ID | Pri | Status | Where |
|---|---|---|---|
| FR-CAP-01 | P0 | ✅ P0 | REST `OrderController`, **GraphQL `/graphql`** (oms-integrations), Shopify webhook, **marketplace webhook/poller (Amazon SP-API shape)**, MCP `order_create`, UCP `POST /ucp/v1/orders`. EDI X12 (P1) deferred. |
| FR-CAP-02 | P0 | ✅ | `ai.intentoms.order.model.*`, typed extensions validated against `extension_schema` (networknt JSON Schema) |
| FR-CAP-03 | P0/P1 | ✅ P0 | `order-types.yaml`, `OrderTypeRegistry`; standard_ship, bopis, ship_from_store, same_day_local, drop_ship, pre_order, backorder bound to state models + workflows + decision sets |
| FR-CAP-04 | P0 | ✅ | Unique (tenant, channel, channel_order_id) + `Idempotency-Key` table; replay returns the existing order |
| FR-CAP-05 | P0 | ✅ | `ValidationPipeline` steps schema, item_price_sanity, address (AddressVerifier SPI), payment_status, compliance (CEL rules), agent_provenance; order from `capture.validation_steps` |
| FR-CAP-06 | P0 | ✅ | `IntentService` → decision `fulfilment_intent` (Jev Choice + Nouls split_ok/is_gift) → `IntentProfile` |
| FR-CAP-07 | P0 | ✅ | `AgentProvenance` on the order; `mandate` table, mandate decision `agent_mandate_match` (T3) |
| FR-CAP-08/09 | P1 | ○ | — |

## 7.2 Configurable state machine engine (oms-statemachine)
| ID | Status | Where |
|---|---|---|
| FR-SM-01 | ✅ | YAML models per entity (order, order_line, return, shipment …) with meta-states Created/Active/Terminal |
| FR-SM-02 | ✅ | `entity_state_qty` quantity buckets; partial `fire(qty)` |
| FR-SM-03 | ✅ | triggers event./api./timer./decision., CEL guards, actions, permission on `api.*` |
| FR-SM-04 | P1 ◐ | `parent` on states parsed; parallel regions deferred |
| FR-SM-05 | ✅ | `rollup:` rules (CEL over `state`, `lines`) + fallback |
| FR-SM-06 | ✅ | versioned `state_model`, entities pin version, `migrate(dryRun)` per-entity report |
| FR-SM-07 | ✅ | `HoldService`, typed holds, blocks triggers/states, expiry sweeper |
| FR-SM-08 | ✅ | `sla:` on states → `sla_timer` → `sla.breached` + `SlaBreachedEvent` |
| FR-SM-09 | ✅ P0 | YAML in `config/tenants/<t>/state-models` (Git); UI shows diagram + YAML (designer round-trip P1 deferred) |
| FR-SM-10 | ✅ | `StateModelValidator`: unreachable, dead ends, missing permission, unknown fields/roots, loops, SLA format |
| FR-SM-11 | ✅ | `<agg>.transitioned` events with actor, before/after, correlation/causation |
| FR-SM-12 | P1 ✅ | Copilot proposes state-model diffs from plain English; they go through `StateModelRegistry.validate` and are applied only by a human (`oms-copilot`) |

## 7.3 Workflow orchestration (oms-workflow, oms-orchestration)
| ID | Status | Where |
|---|---|---|
| FR-WF-01 | ✅ | Postgres durable step engine (ADR-002); `StandardShipOrderWorkflow`, `BopisOrderWorkflow`, `ReturnWorkflow` |
| FR-WF-02 | ✅ | `ctx.activity` with `RetryPolicy`, timeouts, named compensations (`void_auth`, `release_reservations`) |
| FR-WF-03 | P1 ◐ | Copilot adds review states/holds/playbook steps from a sentence ("add a risk review for agent orders over $500"); workflow code itself stays versioned |
| FR-WF-04 | ✅ | `HumanTaskService`, queues with SLA/skills/escalation, sweeper |
| FR-WF-05 | ✅ | `WorkflowEngine.signal` per-instance ordering + idempotency keys; WMS/carrier webhooks |
| FR-WF-06 | ✅ | `timeline()` with steps, activities, signals, retries, pending timer, next expected event; merged into the order view |
| FR-WF-07 | ✅ | `BulkOperationService` + handlers resource/repromise/hold/release/cancel, dry run, throttle, rollback |
| FR-WF-08 | ✅ | instances pin `version`; registry keeps older definitions |

## 7.4 Decision fabric (oms-decision)
| ID | Status | Where |
|---|---|---|
| FR-DEC-01 | ✅ | `DecisionDefinition` YAML (Appendix B + rules/composite/topK/cache) |
| FR-DEC-02 | ✅ | `DecisionRuntime`: L0 rules → L1 Jev → L2 LLM (confirm band) → L3 `EscalationHandler`; layers skippable per definition |
| FR-DEC-03 | ✅ | `rules:` CEL first-match by priority; unit-testable via evaluate-now/golden sets (DMN P2 deferred) |
| FR-DEC-04 | ✅ | `composite.weights` → `CompositeScorer` |
| FR-DEC-05 | ✅ | `TopKStage` (Score chunks → top K → Choice) |
| FR-DEC-06 | ✅ | `decision_record` (value, probabilities, confidence, band, provider, model, definition version, input hash, latency, cost, action, layer, mode) |
| FR-DEC-07 | ✅ | sync `decide`, async shadow, batch `decideAll`; risk scorer sweeps |
| FR-DEC-08 | ✅ | `DecisionProvider`: jev (default), llm.anthropic, llm.openai, heuristic; routing + fallback per definition; kill switches |
| FR-DEC-09 | ✅ | shadow status + SHADOW records + impact diff |
| FR-DEC-10 | ✅ | `label()` outcome labels, calibration uses them |
| FR-DEC-11 | ✅ | `cache: {ttl}` by input hash |

## 7.5 Policy and prompt studio
| ID | Status | Where |
|---|---|---|
| FR-PRM-01 | ✅ | Structured editor (UI `StructuredEditor`) + `PATCH` dotted-path edits + YAML tab |
| FR-PRM-02 | ✅ | draft → lint → evaluate (golden set gate) → submit → approve (1 or 2 approvers by tier) → publish |
| FR-PRM-03 | ✅ | `impactDiff` re-runs stored inputs; flips, band mix, cost/latency/escalation deltas |
| FR-PRM-04 | ✅ | rollout shadow / canary:% / live, one-click rollback |
| FR-PRM-05 | ✅ | `stateInclude` paths validated against `decision.state_schema` (warn) |
| FR-PRM-06 | ✅ | reason + ticket mandatory on submit; `audit_log` |
| FR-PRM-07 | P1 ◐ | 14 catalogue definitions ship in `config/tenants/demo/decisions` |
| FR-PRM-08 | P1 ✅ | `DecisionLinter` (overlap, escape, vague rubric, PII, L0 contradiction, protected attributes) |
| FR-PRM-09 | P2 ○ | — |

## 7.6 Sourcing & promising (oms-sourcing)
| ID | Status | Where |
|---|---|---|
| FR-SRC-01 | ✅ | `DefaultCandidateGenerator` (inventory, capabilities, capacity, cutoffs, distance; limit 100) |
| FR-SRC-02 | ✅ | `EconomicsCalculator` + `sourcing_node_score` composite soft scores + `PlanScorer` weights |
| FR-SRC-03 | ✅ | policy from `IntentProfile` / channel defaults; ship_complete, ship_as_available, split_up_to_k, pickup_consolidated, earliest_partial |
| FR-SRC-04 | ✅ | margin floor → REQUIRES_APPROVAL with alternatives; approval task; guard on `decision.sourcing_plan` |
| FR-SRC-05 | ✅ | `PromiseService` cached, deterministic; k6 threshold p95 < 300 ms |
| FR-SRC-06 | P1 ✅ | `Promise.alternatives` trade-offs |
| FR-SRC-07 | ✅ | `resource()` penalises node+sku (decaying) and re-plans |
| FR-SRC-08 | P1 ◐ | `NodeRegistry.updateHealth` feeds scores |
| FR-SRC-09 | P1 ◐ | `repromise` playbook action + `sla_breach_risk` |
| FR-SRC-10/11 | P1/P2 ○ | — |

## 7.7 Inventory (oms-inventory)
FR-INV-01 ✅ ATP by supply/demand type · FR-INV-02 ✅ row-locked reservations with TTL, atomic reserveAll, convert/consume · FR-INV-03 ✅ channel buffer, network ATP endpoint (also used by UCP/MCP `inventory_check`) · FR-INV-04 ✅ feed upsert + reconcile drift events · FR-INV-05 P1 ◐ `InventoryConfidenceUpdater` hook.

## 7.8 Monitoring (oms-monitoring)
FR-MON-01 ✅ pipeline view (state × age × channel × node × type) · FR-MON-02 ✅ `sla_definition` + `sla_breach_risk` scorer → `order_risk` · FR-MON-03 ✅ taxonomy + `exception_classify` · FR-MON-04 ✅ playbooks gated by confidence/spend · FR-MON-05 ✅ `StuckOrderSweeper` · FR-MON-06 ✅ Slack/Teams/email/PagerDuty with de-dup · FR-MON-07 P1 ○ · FR-MON-08 ✅ ops copilot (`POST /api/v1/copilot/ops/ask`, whitelisted parameterised queries, SQL shown).

## 7.9 Customer service (oms-order)
FR-CS-01 ✅ `OrderView` · FR-CS-02 ✅ `OrderModificationService` feasibility + cost + carrier intercept · FR-CS-03 ◐ WISMO via MCP `order_get`/UCP order retrieval (chat/voice front ends integrate on top) · FR-CS-04 ◐ `appeasement` decision + role limits config; CS console flow P1 · FR-CS-05 ✅ CS reply copilot grounded in order facts (`POST /api/v1/copilot/cs/draft`) · FR-CS-06 P1 ○.

## 7.10 Returns (oms-returns)
FR-RET-01 ✅ any channel incl. in-store and agent · FR-RET-02 ✅ L0 window/final sale + `return_risk` Score → instant / on_carrier_scan / on_inspection / store_credit_only / escalate · FR-RET-03 ✅ reason-code Nouls stored · FR-RET-04 ✅ original-tender split, partial, restocking fee, store credit · FR-RET-05 ✅ keep-it · FR-RET-06 ✅ `return_disposition` · FR-RET-07 ◐ cross-channel history by customer ref + e-mail token.

## 7.11 Payments, tax, landed cost, compliance (oms-finance)
FR-FIN-01 ✅ PSP-agnostic (mock + Stripe), re-auth, capture on shipment, split tender, partial · FR-FIN-02 ✅ TaxEngine SPI (flat-rate + Avalara skeleton) · FR-FIN-03 ✅ CEL compliance rules · FR-FIN-04 ✅ `OrderPnlCalculator` → `MarginSnapshot` · FR-FIN-05 ✅ tariff tables with effective dates · FR-FIN-06 ◐ mandates + agent payment tokens stored as evidence.

## 7.12 Agent-native interfaces (oms-agent)
FR-AGT-01 ✅ MCP server (`/mcp`, tools with per-tool scopes) · FR-AGT-02 ✅ UCP order capability + lifecycle webhooks, version negotiation · FR-AGT-03 ✅ registry, HMAC/API-key verification, trust tiers, ownership for unknown agents · FR-AGT-04 ✅ per-agent limits, rate limits, confirm actions · FR-AGT-05 ✅ `InjectionScreen` (Noul) + text kept as data · FR-AGT-06 ✅ same tools/permissions for internal agents · FR-AGT-07/08 P1 ○ · FR-AGT-09 ✅ evidence pack.

## 7.13–7.16
FR-SIM-01 ✅ replay from ledger with diff · FR-SIM-02 ✅ batch simulation · FR-SIM-03 ✅ what-if (node closure, carrier outage, tariff) · FR-SIM-04 P1 ○ · FR-SIM-05 ✅ synthetic generator · FR-XAI-01 ✅ `why()` trace · FR-XAI-02 ✅ append-only ledger/audit (DB triggers), retention config · FR-XAI-03 ✅ generated plain-language explanation with grounding check (`GET /api/v1/copilot/explain/{id}`) · FR-XAI-04 ✅ customer-safe trace view · FR-CFG-01 ✅ config copilot: plain-English request → validated, diffed, tested drafts with generated golden cases · FR-CFG-02 ✅ copilot never publishes · FR-CFG-03 ◐ bootstrap copilot builds a tenant blueprint from a business description (field mappings from sample payloads deferred) · FR-CFG-04 ○ · FR-ANL-01 ✅ KPI dashboard · FR-ANL-02 ✅ decision analytics per version · FR-ANL-03 ◐ NDJSON event export · FR-ANL-04 ✅ ops copilot shows the query used · Margin Guardian digest (`GET /api/v1/copilot/digest`).

## 8 Guardrails
GR-01 pre/post checks + guards ✅ · GR-02 bands, low band never acts ✅ · GR-03 escape option enforced by parser + lint ✅ · GR-04 enumerated answers only (provider schemas, LLM tool schema) ✅ · GR-05 `SpendGuard` velocity caps + circuit breaker ✅ · GR-06 kill switches per decision/provider/tenant ✅ (drill: runbook) · GR-07 timeouts → fallback → L0 default; capture never blocks on AI ✅ · GR-08 injection screen, untrusted text as data, L2 read-only ✅ · GR-09 PII tokenisation in state builder ✅ · GR-10 versions, reason, tests, simulation, approvals, emergency path with retro review ✅ · GR-11 golden set gate ✅ · GR-12 `CalibrationMonitor` weekly drift ✅ · GR-13 overrides recorded + fed to calibration ✅ · GR-14 separation of duties (submitter ≠ approver; copilot cannot publish; thresholds human-only) ✅ · GR-15 typed slots in customer text ✅ · GR-16 protected-attribute lint ✅ · GR-17 per-tenant AI budget + cost per order KPI ✅.

## 12 Non-functional
NFR-01 throughput: stateless instances, index-only ATP, cached promise, batched outbox (load test `load/promise.js`) · NFR-02 latency targets encoded as k6 thresholds; capture p95 measured 18 ms in tests · NFR-03/04 multi-instance deployment (`deploy/k8s`), Postgres sync replication expected from the platform · NFR-05 degraded mode verified by tests (kill switch → L0 defaults) · NFR-06 exactly-once per order via row locks + idempotency keys · NFR-07 JWT/API keys/HMAC, **OIDC resource-server SSO (Keycloak/Entra/Okta claim mapping)**, RBAC + permission grammar, bcrypt, login rate limit, security headers, production fail-fast; SCIM deferred · NFR-08 per-tenant PII keys with crypto-shredding · NFR-09 tenant scoping in every table and query, per-tenant rate limits and AI budgets · NFR-10 Micrometer + OTLP tracing · NFR-11 webhooks (HMAC-signed), Kafka outbox publisher, OpenAPI · NFR-13 UI built to WCAG 2.2 AA practices · NFR-14 retention config · NFR-15 AI cost per order KPI and daily budget.
