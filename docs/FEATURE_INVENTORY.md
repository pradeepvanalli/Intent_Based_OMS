# Intent OMS — feature inventory and enhancement roadmap

*Internal reference, not marketing copy. Every claim below was verified against the actual code in this repo (not
against README/docs claims) by reading the relevant service, controller, and model classes directly. Where
something is a gap or an overlap worth resolving, it's called out as such rather than smoothed over. Use this as
the map for deciding what to build next.*

---

## 1. Core commerce: order, inventory, sourcing, finance

**Order capture and lifecycle** (`oms-order`)
- Multi-channel intake: REST, GraphQL, Shopify webhook mapping, Amazon SP-API polling, MCP, UCP — all through one
  CEL-based validation pipeline (`ValidationPipeline`), with address verification and order-type-driven extension
  schemas for fields that vary per tenant.
- Idempotency is a first-class mechanism, not a side effect: `IdempotentRequests` claims an `Idempotency-Key` in
  its own transaction before the request body runs; a replay with a different body gets `409
  idempotency_key_reused`, a concurrent in-flight replay gets `409 request_in_progress`.
- Order modification feasibility is computed live against the order's actual state machine, not a static table — a
  cancel is only offered if the specific line's current state genuinely permits it right now.
- Signed public tracking links (`TrackingTokenService`) serve order status with no authentication required.
- Intent inference (`IntentService`) reads free-text/structured intent and feeds it into sourcing policy selection.
- `AgentProvenance` stamps every order with which agent and mandate placed it, when applicable.

**Inventory** (`oms-inventory`)
- ATP is a pure, unit-tested formula: on-hand plus inbound (optional) minus reserved minus allocated minus safety
  stock, clamped to zero per node so one oversold node can't offset another in a network view.
- Every reservation takes a row lock (`SELECT ... FOR UPDATE`) on the exact `(sku, node)` position and recomputes
  ATP under that lock — concurrent reservations against the same position are strictly serialized, not
  best-effort.
- TTL-based reservation expiry via a background sweeper, with release/convert/consume endpoints.
- Confidence scoring on inbound inventory feeds, plus a cached network-wide ATP view for fast reads.

**Sourcing** (`oms-sourcing`)
- Node selection scores every candidate once through a typed decision (`sourcing_node_score`), then builds a
  fulfillment plan per policy (ship-complete, ship-as-available, split-up-to-k, earliest-partial,
  pickup-consolidated) and picks the plan matching the order's inferred or explicit intent, falling back through an
  ordered list if the preferred policy isn't feasible.
- A margin-floor gate blocks any plan that would source at a loss without an existing approval, and offers
  alternative plans that do clear the floor instead of silently eating the margin hit.
- A second typed decision (`split_acceptability`) checks a multi-package plan against the customer's own
  split-complaint history before committing to it, falling back to ship-complete on a confident "no."
- Store-pickup rejections trigger re-sourcing that penalizes the rejecting node/SKU pair for future candidate
  generation, without deleting the rejected fulfillment group's history.
- **A real delivery-promise service already exists** (`DefaultPromiseService`, target p95 under 300ms): given a
  requested order, it returns the earliest-ETA node with the best on-time probability and lowest cost, with a
  fallback path when no single node covers the full order, a computed backorder date when nothing is in stock
  anywhere, and support for simulated what-if overrides (closed node, carrier outage). This is already exposed to
  agents as the `promise` MCP tool.

**Finance** (`oms-finance`)
- Payments run through a real Stripe PaymentIntents gateway (manual capture) or a mock gateway, toggled by config
  — not a hardcoded mock. Flow: authorize → reauthorize before expiry → partial/split capture across multiple
  tenders → proportional refund allocation across those tenders → void.
- Every payment or refund mutation is its own short, row-locked transaction that re-validates balances under the
  lock before calling the gateway, so a transport failure mid-capture can't double-charge or double-refund on
  retry.
- A spend guard checks every refund against the placing agent's mandate when the actor is an agent, not just at
  order-placement time.
- **A real landed-cost engine already exists** (`DefaultLandedCostService` + `TariffController`): table-driven
  tariff lookup by longest HS-code-prefix match, then country-specific over wildcard, then latest effective date;
  returns an explicit zero-with-no-rate-id (not a silent guess) when nothing matches, so sourcing can flag the
  coverage gap instead of miscalculating duties.
- A compliance engine (`DefaultComplianceService`) evaluates CEL rules per line or per order — restricted
  ship-to zones, age verification, hazmat/air-shipping restrictions — and fails closed: a rule that errors during
  evaluation is treated as a hold, never silently skipped.
- Per-order P&L and a tax-quote endpoint also exist (tax quote's provider depth wasn't fully audited in this pass).

---

## 2. Fulfillment operations and the Agentic Control Tower

**State machine** (`oms-statemachine`)
- A generic, YAML-configured statechart engine addressing any `(entityType, entityId)`, with CEL-guarded
  transitions, hold checks, versioning and migration support between model versions, and quantity-bucket tracking
  so a single order line can be partially transitioned (e.g., half shipped, half still sourcing).
- Per-state SLA timers that fire breach events on their own sweep cycle, independent of the Agentic Control Tower's
  anomaly scan.
- Rollup rules derive a header-level order status from line/child statuses automatically.
- A typed holds subsystem (scope: order/line/node, reason, placed-by actor) used both by compliance and by the
  autonomy layer when it needs to pause something reversibly.

**Workflow engine** (`oms-workflow`)
- A custom, JDBC-backed durable workflow engine — not a third-party dependency — where every step and mutation is
  persisted and appended to the event ledger, with retry policy, activity timeouts, and external signals to wake a
  paused workflow.
- A full human-task queueing system (typed task requests, queue routing, SLA sweeps) — this is the same queue the
  Agentic Control Tower escalates into when it can't act autonomously.
- A separate bulk-operations subsystem for batch actions across many entities with progress tracking.

**Orchestration actions and connectors** (`oms-orchestration`)
- Concrete fulfillment actions: re-source to a new node, re-promise and notify of a new delivery date, retry a
  declined payment, contact the customer with a templated message.
- Carrier and WMS connectors are pluggable SPIs; a real EasyPost carrier connector exists alongside the mock, and
  inbound carrier/inventory/WMS webhooks are idempotent and signature-verified.

**The Agentic Control Tower** (`orchestration.autonomy` package) — the most load-bearing feature in the system,
worth describing precisely:
- **Detection.** `AnomalyScanner` runs on a schedule (default every 120 seconds, per tenant) across four
  independent signal sources: orders with no progress past a configured age, orders whose breach-risk score
  (computed separately by `oms-monitoring`) crosses a threshold, stale margin-floor approval tasks, and overdue
  exceptions. Candidates are deduplicated by order id with a 60-minute re-raise window, and every candidate is
  diagnosed through the real decision fabric (`stuck_order_diagnosis`, same `DecisionService.decide()` path as
  every other decision — never bypassed).
- **Guardrails**, checked in this exact order: kill switch → master enable switch → hard-never-autonomous rule →
  allow-list membership → minimum confidence override → daily action-count cap → per-action dollar-impact cap.
  Refund and cancel are excluded from the allow-list at the configuration-loading layer itself — a tenant cannot
  configure them into autonomy even by mistake.
- **Action mapping.** Diagnoses map deterministically to actions: payment-retry-needed → retry payment,
  inventory-reallocation-needed → re-source, address-confirmation-needed or awaiting-customer-response → notify
  customer, carrier-lost-in-transit or node-capacity-backlog → re-promise.
- **Honest escalation.** If the decision itself needed a human, a guardrail blocked the action, or the mapped
  service call found nothing actionable, the system escalates to a human task with the exact reason attached —
  which decision band it hit, or which guardrail and by how much it was exceeded.
- **Undo.** Only re-source, re-promise, and release-hold are reversible by design; payment retry and customer
  notification are rejected outright as not meaningfully undoable. Undo restores the exact prior state (the node
  sourced away from, the prior promise date, the exact hold that was released) and refuses if the order has already
  shipped.
- REST surface: `GET /anomalies`, `GET /actions`, `POST /actions/{id}/undo`, `GET`/`PUT /guardrails`, `POST
  /kill-switch`, `GET /stats`, `POST /scan-now`.

**Returns** (`oms-returns`)
- Eligibility is deterministic (return window, final-sale flag, digital-goods flag); fraud/abuse risk is not local
  logic — it's delegated to the same typed decision fabric (`return_risk`), consistent with how the rest of the
  system treats judgment calls.
- A returnless-refund evaluator weighs label-and-handling cost against expected resale recovery value (adjusted by
  item condition) and the customer's abuse-risk score, with hazmat and digital items excluded by rule.
- Refund calculation handles proportional tax and shipping refunds, restocking fees on triggering conditions, and
  either original-tender or store-credit payout, all through banker's rounding.
- **Disposition routing already exists** as a typed decision outcome: restock, refurbish, liquidate,
  return-to-vendor, donate, or destroy — grading isn't hardcoded, it's modeled the same way every other judgment
  call in the system is.

**Monitoring** (`oms-monitoring`)
- Breach-risk scoring runs on a schedule, building its score from order age, promise slack, node backlog and
  reject rate, and seven-day carrier on-time rate.
- A declarative, CEL-gated playbook system (hold / notify / classify / create-task / log actions) exists as an
  earlier remediation mechanism — see the consolidation note in section 7, this overlaps conceptually with the
  Control Tower's own action executor.
- Alerting supports email and webhook channels, but the email channel is a logging stub with no real SMTP/provider
  integration behind it (separate from the real SendGrid integration used for customer-facing notifications — see
  section 4).

---

## 3. The decision fabric

Every judgment call in the system — not just the famous ones — runs through the same four-layer pipeline: an L0
CEL rule layer that wins outright on a match, an L1 typed-model call (confidence-scored, enumerated options only),
an L2 second-opinion LLM call in the confirm band, and L3 human escalation when nothing upstream is confident
enough or the risk tier requires a person regardless. Kill switches exist globally, per-decision, and per-provider.
Four provider implementations exist behind one interface: Jev/System One via OpenRouter, Anthropic, any
OpenAI-compatible endpoint, and a deterministic heuristic fallback that needs no AI key at all — the demo tenants
run entirely on the heuristic provider by default.

**The full roster — 24 named decisions** across the five tenant configs:

| Decision | Tier | Purpose |
|---|---|---|
| fulfilment_intent | T1 | What shipping/split outcome the buyer actually wants |
| split_acceptability | T2 | Whether a split shipment is acceptable for this customer |
| sourcing_node_score | T1 | On-time likelihood per candidate fulfillment node |
| return_risk | T2 | Return-abuse risk score plus reason-code flags |
| return_reason | T0 | Normalizes a free-text return reason into the tenant's taxonomy |
| return_disposition (+ apparel variant) | T1 | Restock / outlet / donate / discard routing |
| appeasement | T2 | What compensation, if any, to offer |
| address_risk (+ high-value variant) | T2 | Undeliverable/reshipper address flagging |
| sla_breach_risk | T1 | Per-order breach risk, 0–4 |
| backorder_next_action | T1 | What to do when a backordered line breaches SLA |
| exception_classify | T0 | Classifies an operational exception |
| customer_request_intent | T1 | CS intent: WISMO, cancel, change, warranty/trade-in |
| injection_screen | T0 | Flags prompt-injection attempts in any agent/customer text |
| agent_mandate_match | T3 | Recommends on agent orders within mandate (human always decides) |
| stuck_order_diagnosis | T2 | Root-causes a stalled order for the Agentic Control Tower |
| curbside_eta_promise | T1 | Retail: confidence a curbside pickup lands within promise |
| store_reject_reroute | T1 | Retail: re-sourcing after a store pick rejection |
| bracketing_likelihood | T1 | Apparel: likelihood of a multi-size "bracketing" order |
| drop_oversell_action | T1 | Apparel: handling an oversold flash-drop |
| exchange_fulfilment | T2 | Apparel: fastest legitimate path to deliver an exchange |
| preorder_allocation | T2 | Electronics: fair allocation on launch day |
| warranty_triage | T1 | Electronics: defective vs. buyer's remorse vs. transit damage |

**Golden-set gating is real, not aspirational.** A decision version publishes only if it passes lint, gets the
tier-required number of approvals from someone other than the author, and clears its golden-set evaluation
(accuracy in the act band plus calibration error against the real confidence distribution). An impact-diff tool
replays up to 500 recent live decisions through a draft version before anyone approves it, showing the band-mix,
flip-rate, cost, and latency delta. One-click rollback to the last retired version exists. An emergency-publish path
exists for config-admins that skips the gates but forces a 48-hour retro-review deadline — it doesn't let anyone
bypass review permanently, just defer it under real urgency.

---

## 4. AI-native tooling: copilot, simulation, analytics

**Policy copilot** (`oms-copilot`) turns a plain-English policy sentence into a changeset: an LLM proposes the edit
under a strict JSON schema, every individual change is independently re-validated against the real artifact schema
(not trusted from the model), diffed, and — if invalid — sent back for one automatic repair attempt. The result is
always staged as a draft; it still has to pass the same lint → evaluate → approve → publish gate as any other
decision change. The user's own request text is explicitly treated as untrusted data, never as instructions to
the model.

A second copilot surface, the **ops query catalog**, answers natural-language operational questions through 16
whitelisted, parameterized SQL templates — the model (or a keyword-matching fallback that needs no AI key at all)
can only pick a template name and typed, validated parameters. Nothing free-form ever reaches the database.

**Margin replay** (`oms-simulation`) takes an uploaded CSV — auto-detecting either a Shopify "Orders export" or a
generic layout — and replays every order through the real planning logic on a virtual thread, with nothing
persisted as a live order, to report what the system's policies would have changed: margin recovered, splits
avoided. The same module also powers deterministic historical replay (used by the decision fabric's impact-diff
tool) and what-if scenario overrides for testing against simulated disruptions.

**Analytics** (`oms-analytics`) tracks 17 defined KPIs with explicit targets — touchless order rate, promise
accuracy, split-shipment rate, cost to serve, margin-floor compliance, return-abuse loss, decision accuracy and
calibration error, and critically, **override rate**: how often an autonomous decision later gets reversed by a
human, capped at 5%. A separate AI-economics report computes automation rate, estimated analyst-hours saved, cost
per 1,000 decisions, and per-layer latency, all from real decision-record data — nothing in this module is stubbed.

---

## 5. Agent commerce: MCP, UCP, mandates

This is the layer that makes "let an AI agent place a real order" something you can actually stand behind.

- **MCP server**, 11 tools: inventory check, decision explanation (customer-safe), order cancel (pre-ship only),
  order get, order modify (address/qty/line/ship-method changes, feasibility-checked before accepting), order
  create (mandate-gated, routes to pending-confirmation for tenant-configured action types), order search, return
  create, task complete (can't self-approve its own proposals), promise (delivery date, on-time probability,
  alternate ship methods), task list.
- **UCP** (`/.well-known/ucp` discovery, `/ucp/v1/*`): real endpoints, not stubs — get order, order events, place
  order under a mandate with idempotency-key support, create a return. Lower-trust agents are restricted to orders
  they themselves placed. Webhook delivery for order/return lifecycle events is HMAC-signed to the agent's own
  callback URL.
- **Mandates** (AP2-style): full lifecycle — create (verifies an Ed25519/EC public-key or HMAC signature over the
  canonical request, screens the instruction text, defaults to a 24-hour TTL) → verify (agent match, not expired,
  active, currency and amount within the spend limit, buyer reference match, valid signature — seven distinctly
  named rejection reasons) → consume (single-use) → revoke.
- **Injection screening** runs the same typed-decision fabric as everything else by default (a dedicated
  `injection_screen` decision), and only falls back to a 12-pattern deterministic regex heuristic when the decision
  fabric itself is degraded — so screening never silently passes just because a provider is down.
- **Evidence packs** are a real, one-call service: agent identity and verification method, the mandate and its
  signature validity, the raw instruction text, the injection-screen result, the full decision trail for the order,
  a customer-safe order snapshot, payment and refund history, fulfillment and delivery proof, and a SHA-256 digest
  over the order's entire event ledger for tamper evidence.
- **Agent trust tiers**: four tiers (read-only, two shopper tiers, internal ops), each mapped to a fixed permission
  set, plus per-agent allow-lists of actions that need confirmation. Tier changes are an explicit admin action —
  there's no behavior-based reputation scoring that moves an agent between tiers automatically yet (see section 8).

---

## 6. Platform, security, and integrations

**Platform**: four authentication schemes behind one principal abstraction (JWT, API keys, OIDC, agent HMAC
signing), a role/permission model rather than roles alone, PII tokenization with per-tenant deterministic tokens
plus AES-GCM envelope encryption and crypto-shred-on-key-rotation for deletion requests, and a real
production-readiness check that fails the app at boot in the prod profile if the JWT secret is weak, dev users are
configured, CORS is wildcard, or no master key is set for PII encryption. Multi-tenancy is enforced at the
application layer (tenant context bound from the principal or an explicit header), not at the database
row-security level. Rate limiting is real (Bucket4j token buckets) but in-memory only — the code's own comment
flags that it needs to move to a shared backend before a multi-instance deployment.

**Integrations**: EasyPost (carrier), Smarty (address verification, fails open to "unverified" rather than blocking
capture), Amazon SP-API (real OAuth token refresh and rate-limited polling), SendGrid and Twilio (real, used for
customer-facing notifications — distinct from the ops-alerting email stub in section 2), and a generic outbound
WMS/3PL HTTP connector are all real external calls, gated cleanly behind config flags with mock fallbacks when
disabled. No unfinished stubs or TODOs were found among the files read — everything either fully implements its
contract or is an intentionally pluggable mock.

---

## 7. Frontend and reference tenants

The React console has one screen per major capability — Control Tower (live pipeline, at-risk orders, KPIs),
Agentic Ops and a Guardrails panel mounted into it, an Ops Copilot for templated natural-language queries, the
Playground (decision sandbox with a node-network visualization), order list/detail with a decision-trace "why"
drawer, a human-task queue, exceptions, returns, state-model visualizer, agent registry, margin replay, an AI
Economics and Margin Guardian analytics view, a Decision Studio for editing decision YAML, the policy copilot's
changeset review flow, a guided Showcase tour, and a public, unauthenticated order-tracking page. Live data
everywhere is polling-based (10–30 second intervals via React Query) — there's no WebSocket/SSE channel yet.
Theming covers light and dark mode with a dedicated teal accent reserved for autonomy/agentic surfaces, distinct
from the general indigo accent.

Five reference tenants exist — a general demo, electronics, general retail, apparel, and a blank starter template —
each with its own catalog, nodes, inventory, SLAs, playbooks, exception taxonomy, and 14–18 decisions configured
per vertical (apparel and electronics each add vertical-specific decisions on top of the shared set). Seven scripted
end-to-end use-case scenarios run across them, each tagged to specific requirement IDs, covering intent-driven
sourcing, returns-as-margin-recovery, peak-day disruption, agentic commerce with MCP proof, policy-change-before-
lunch, prompt-configured OMS setup, and margin-leak detection.

---

## 8. Internal consolidation opportunities

Two places in the codebase do overlapping work through different paths, worth resolving before they diverge
further rather than treating as a documentation nitpick:

1. **Two stuck-order detectors.** `StuckOrderSweeper` (older, simpler) and `AnomalyScanner` (the Agentic Control
   Tower's richer, multi-signal, guardrail-aware detector) both exist. Worth auditing whether the sweeper is still
   wired into anything live, and retiring it if the Control Tower has fully superseded it.
2. **Two remediation paths.** The monitoring module's CEL-gated playbook actions (hold, notify, classify,
   create-task, log) and the orchestration module's autonomy action executor both drive fulfillment-adjacent
   actions off different trigger mechanisms. Worth deciding whether playbooks should route through the same
   guardrail/undo infrastructure the Control Tower already has, rather than maintaining two action-execution
   surfaces with different safety properties.

---

## 9. Confirmed gaps and a prioritized enhancement roadmap

Everything here was checked against the code, not assumed. These are real absences, not just undocumented
features.

**Tier 1 — closes the biggest credibility gap with enterprise buyers**
1. **One ERP connector**, reference-implemented end to end. NetSuite is the lightest lift of the big three (SAP,
   NetSuite, Dynamics); this is the single most common "does it integrate with our systems" question and currently
   has no answer.
2. **Database-level tenant isolation** (row-level security), or at minimum a documented, tested defense-in-depth
   argument for why application-level isolation is sufficient. This is the first finding in any serious security
   review of a multi-tenant system.
3. **A shared-backend rate limiter** (Redis-backed), since the current in-memory Bucket4j buckets silently stop
   enforcing limits correctly the moment there's more than one app instance behind a load balancer.

**Tier 2 — extends the agent-commerce story, which is the system's sharpest differentiator**
4. **Agent reputation scoring.** The same typed-decision machinery already scoring customers and returns could
   score an agent's own mandate-compliance and injection-screen history over time, feeding (not replacing) the
   existing manual trust-tier admin action — right now tier changes are 100% manual with no behavioral signal.
5. **A dispute-network-formatted evidence export.** The evidence pack already contains everything a chargeback
   needs; formatting one variant specifically for Visa/Mastercard dispute submission turns "we have an audit
   trail" into "here's the file your ops team actually uploads."
6. **A public AP2/UCP conformance suite.** Publishing a self-certification test suite against the existing
   implementation turns Intent OMS from "an implementation of these protocols" into a reference other agent
   builders and OMS vendors test against — a much bigger claim for the same engineering investment.
7. **Pre-capture payment-fraud scoring as its own typed decision.** The system scores return-abuse risk and address
   risk, but there's no equivalent order-level payment/fraud decision before capture — a natural extension of the
   same decision-fabric pattern, and directly relevant to agent-placed orders where the usual behavioral signals a
   fraud team relies on (device fingerprint, browsing pattern) don't exist.

**Tier 3 — scale and operational maturity**
8. **A published load-test benchmark** (TPS and P99 latency at a stated order volume). Nothing in the repo claims
   or demonstrates scale today; this is the second question after ERP integration from any serious evaluator.
9. **A real SMTP/email provider behind ops alerting** — the customer-facing notification path already uses
   SendGrid; the internal alerting channel is still a logging stub.
10. **Broader carrier/3PL connector coverage.** One real carrier (EasyPost) today; enterprise rollouts expect
    several, plus a rate-shopping layer across them.
11. **B2B order support** — contract pricing, credit terms, multi-approver workflows — if the roadmap ever points
    at wholesale/B2B customers, this is a different order model, not an extension of the current one.

**Tier 4 — resolve before they become harder to unwind**
12. Act on the two consolidation items in section 8 before more logic accumulates on either side of either split.

This list is meant to be read in order of leverage, not in order of difficulty — items 4 through 7 are the ones
most likely to turn "an OMS with good AI" into "the reference implementation people point to" for agent commerce
specifically, which is the positioning this project already has a real head start on.

---

## 10. Self-Optimizing Order Orchestration (new since this document was first written)

A new module, `oms-learning`, closes the loop this document's section 9 implicitly assumed was still open: outcomes
were measured (section 4's analytics) but nothing turned them back into policy. That gap is now closed, with the
same engineering discipline as everything else in this inventory — real code, verified compiling and passing the
full test suite, not a roadmap slide. Full detail in `docs/UC_SELF_OPTIMIZING_ORCHESTRATION.md`; summary here for
anyone reading this inventory top to bottom.

- **Propensity logging on every decision.** `decision_record` (section 3's table) gained `context_features`,
  `arms`, `chosen_arm`, `propensity`, `policy_version` columns — the data an off-policy estimator needs to fairly
  judge a new policy against old, logged traffic.
- **Episodes and typed outcomes.** `EpisodeAssembler`/`OutcomeObserver` are ledger `EventSubscriber`s, not a new
  code path bolted onto order capture — every decision already made gets an episode, joined to a typed catalog of
  what happened next (delivery, return, payment, contact, inventory outcomes), with reward correction as late
  outcomes land (a return 45 days out, a chargeback 120 days out).
- **Fast-loop parameter learners**: node reliability (Beta-binomial), lane transit distributions, a contextual
  bandit over the five sourcing policies (the one place genuinely non-1.0 propensity gets logged, with a seeded,
  replayable random draw), customer preference facts with cold-start shrinkage, fraud-threshold tuning, and
  decision-fabric band/calibration self-tuning — all recommend-only or gated by explicit per-learner autonomy,
  updating in minutes, no LLM in the loop.
- **Slow-loop Analyst/Author/Critic**, extending `oms-copilot`'s existing policy-from-sentence infrastructure
  (section 4) rather than duplicating it: nightly hypothesis generation from aggregated outcome digests (never a
  single order), changeset authoring re-validated against the live artifact, and critic review as evidence for a
  human, never a gate on its own.
- **Proof before promotion**: an off-policy evaluator (inverse-propensity + doubly-robust, with a real 95%
  confidence interval, extending `oms-simulation`'s existing replay machinery from section 1), shadow mode, canary
  with a persistent holdout, and a tiered promotion gate that only ever auto-promotes a T0/T1 change, never T2/T3,
  and never a postal-code-scoped one regardless of tier.
- **Governance that actually blocks**: change budgets (max promotions per week, max step per promotion), freeze
  windows, automatic rollback on guardrail breach, and coverage/drift monitors that auto-pause learning — not just
  alert — on silent data loss or a feature-distribution shift.
- **Honest gaps, stated in the use-case doc rather than hidden**: safety-stock and reservation-TTL tuning are
  recommend-only proposals, not auto-applied; a weekly transfer-recommendation report, fact decay, cross-tenant
  shared priors, and a full sequential-testing early-stop for canaries are documented extension points, not built.

This also resolves part of section 9's Tier 2 roadmap ahead of schedule: agent reputation scoring (item 4) and
pre-capture payment-fraud scoring (item 7) are now real, working features (`FD-05`'s `AgentReputation` learner and
the new `payment_risk`/`rto_risk` decisions) rather than proposed next steps.
