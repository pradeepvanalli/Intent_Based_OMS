# Features

The complete feature matrix for Intent OMS. The README keeps a short version; this page is the long one. Endpoint
names and configuration keys come from the code; see `docs/API.md` and `docs/CONFIGURATION.md` for request shapes
and keys.

## Feature matrix

| Area | What is in the box |
|---|---|
| Order capture | REST, GraphQL, Shopify webhook, marketplace webhook and Amazon SP-API poller, MCP `order_create`, UCP `POST /ucp/v1/orders`; idempotent on channel + order id and `Idempotency-Key`; validation pipeline (schema, price sanity, address, payment, CEL compliance rules, agent provenance); typed tenant extensions (JSON Schema) |
| State engine | Tenant YAML statecharts with CEL guards; quantity-level status (4 shipped / 3 released / 3 backordered on one line); typed holds; SLA timers; versions with dry-run migration; static checks before publish |
| Workflow | Durable Postgres step engine: activities with retries, timers, signals ordered per order, saga compensation, human task queues with SLA and escalation, bulk operations with dry run and rollback |
| Decision fabric | Jev Choice / Score / Noul questions, L0 rules, composite scoring, two-stage top-K, confidence bands by tier (T0 to T3), shadow and canary rollout, replay, kill switches, AI budgets, full "why" traces with a customer-safe variant |
| Policy studio | Drafts, structured edits, lint, impact diff on historical inputs, golden-set evaluation gate, tiered approvals with separation of duties, rollback, emergency publish with retro review |
| Copilot | Describe a policy change in English, get a validated draft in the studio; bootstrap a tenant from a business description; operations Q&A; decision explanations; customer-service reply drafts. Drafts only, never publishes |
| Sourcing and promise | Candidate generation, economics (rates, labour, packaging, split penalty, landed cost), Jev composite node score, intent-driven split policy, profitability floor with approval, cached promise with on-time probability |
| Inventory | ATP by supply and demand type, row-locked reservations with TTL, atomic multi-line reserve, feeds and drift reconciliation |
| Control tower | Pipeline view by state, age, channel and node; SLA breach-risk scoring; exception taxonomy with classification; playbooks gated by confidence and spend; stuck-order sweeper; Slack / Teams / e-mail / PagerDuty alerts |
| Returns | Any channel including in-store and agent; L0 policy + abuse-risk Score with reason codes drives refund timing; keep-it; disposition; original-tender refunds under spend guardrails |
| Finance | PSP-agnostic payments (mock, Stripe), tax engine SPI, order P&L and margin snapshot, tariff tables for landed cost, refund velocity caps and circuit breaker |
| Agents | Registry with trust tiers and limits, signed mandates, MCP server (11 tools), UCP order capability with lifecycle webhooks, injection screening, dispute evidence packs |
| Simulation and analytics | Rebuild any order from the ledger, replay decisions against proposed versions, batch what-if (node closure, carrier outage, tariff), synthetic orders, KPI dashboard, per-decision calibration, AI economics (automation rate, cost per 1k decisions, human decisions avoided, p95 latency by layer) |
| Margin replay | Upload a Shopify CSV export at `/replay` and get a report of shipping saved, margin recovered and splits avoided under your candidate policies; a bundled sample runs without any upload |
| Playground | `/playground`: place an order and watch the decision trace animate with layer, confidence, latency and cost, plus the network map |
| Customer tracking | Public `/track/<token>` page built on the customer-safe view and the customer-safe explanation |
| Integrations | GraphQL API, generic HTTP WMS connector with signed release documents, EasyPost labels and tracker webhooks, Smarty address verification, SendGrid and Twilio customer notifications from static templates, signed inbound webhooks for WMS, carriers, inventory feeds, Shopify and marketplaces; every adapter has a deterministic mock so the system runs with no credentials |
| Governance | Append-only ledger and audit (database-enforced), per-tenant PII tokenisation with crypto-shredding, weekly calibration drift monitor, OIDC SSO, RBAC permission grammar |

## Configure it with prompts

The copilot (`oms-copilot`, `/api/v1/copilot`) lets a business user change the system in English and get back
something the studio can review:

* **Describe your business, get a tenant.** `POST /api/v1/copilot/bootstrap {"text": "We sell outdoor gear online and
  in six stores, ship from two DCs, offer in-store pickup, 30-day returns except on clearance"}` returns a change set
  with order types, state models, decisions with tier-appropriate bands, compliance rules, playbooks, SLAs and tenant
  settings; `/bootstrap/preview` shows the blueprint first; `stage: true` turns it into drafts.
* **Change a policy.** `POST /api/v1/copilot/changes {"text": "Gold customers returning under $250 with a receipt
  should get an instant refund", "scope": "return_risk"}` returns validated proposed changes with rationale, diff,
  risk tier and required approvals; `/stage` turns a decision change into a studio draft (`return_risk@3`) that then
  goes through lint, impact diff, golden-set evaluation, submit, a different person's approval and a canary. Other
  changes become draft artefacts a `config.admin` applies with a reason.
* **Ask operations questions.** `POST /api/v1/copilot/ops/ask {"question": "Which orders at DC-TX are at risk of
  missing their promise today?"}` picks one of the whitelisted SQL templates, binds its parameters and returns the
  answer, the rows and the SQL it ran.
* **Explain a decision** (`GET /api/v1/copilot/explain/{recordId}?audience=internal|customer`) from its stored trace,
  **draft a customer-service reply** (`POST /api/v1/copilot/cs/draft {orderId, message}`) that fills typed slots from
  order facts only (GR-15), and read a **weekly digest** (`GET /api/v1/copilot/digest?days=7`) of KPI movements with
  suggested follow-up prompts.

Guardrails: drafts only, never publishes or moves money (GR-14); strict JSON schemas (GR-04); your text is data, not
instructions (GR-08); spend counts against the tenant's daily AI budget (GR-17); ops queries are parameterised
templates and the SQL is returned; numbers in explanations, replies and digests are checked against the facts. The
model is any OpenAI-compatible endpoint (`OPENROUTER_API_KEY` by default, `anthropic/claude-sonnet-4.5`); prompt
templates are files in `config/copilot/`. Ready-to-paste prompts with their expected outcomes are in
`docs/examples/prompts/`; exact request shapes in `docs/API.md` "Copilot".

## AI providers

The runtime reads the keys below from the environment (see `.env.example`).

| Setup | Variables | Effect |
|---|---|---|
| One OpenRouter key for everything | `OPENROUTER_API_KEY`; optional `JEV_MODEL` (default `typesafe/jev-1.13`) and `LLM_MODEL` (default `anthropic/claude-sonnet-4.5`) | Jev (L1) through `https://openrouter.ai/api`, LLM (L2) through the OpenAI-compatible route |
| Direct Jev | `TYPESAFE_API_KEY`, optional `TYPESAFE_BASE_URL` | Jev at `https://api.typesafe.ai/v1/systemone` |
| Direct Anthropic | `ANTHROPIC_API_KEY`, optional `ANTHROPIC_MODEL` (default `claude-sonnet-5`) | L2 via the Messages API with a forced tool whose schema enumerates the options |
| Direct OpenAI or compatible | `OPENAI_API_KEY`, optional `OPENAI_BASE_URL`, `OPENAI_MODEL` | L2 via chat completions with `json_schema` response format |
| Selection and budgets | `LLM_PROVIDER` (`auto`, `anthropic`, `openai`), `JEV_ENABLED`, `JEV_TIMEOUT_MS` (800), `LLM_TIMEOUT_MS` (8000) | `auto` prefers Anthropic when its key is present, else any OpenAI-compatible key |

Per tenant, `ai.budget_usd_per_day` caps spend and `ai.kill_switch`, `decision.kill_switch.<name>` and
`provider.kill_switch.<name>` switch layers off instantly (`docs/RUNBOOK.md`).

### Degraded mode

Without keys, or when a provider is slow, erroring, killed or over budget, decisions fall through the chain declared in
their YAML: `provider`, then `fallbackProvider` (the deterministic `heuristic` provider by default), then the
definition's `default`. Orders still capture, source, ship and return. Expect more human-review tasks: a T2 decision
such as `return_risk` escalates to the `risk_review` queue whenever the fallback cannot reach the 0.9 act band, and T1
decisions in the confirm band apply their default deterministically rather than flooding a queue. `GET
/api/v1/decisions/providers` shows which providers are available right now; the layer mix per decision is in
`GET /api/v1/analytics/decisions/{name}`.

## Tiers and bands

| Tier | Meaning | Default bands | Approvals to publish |
|---|---|---|---|
| T0 | Low-stakes classification, reversible | act >= 0.5, else escalate | 1 |
| T1 | Operational decisions with money or customer impact bounded by policy | act >= 0.8, confirm >= 0.5, else escalate | 1 |
| T2 | Money-moving or customer-affecting judgement calls | act >= 0.9, confirm >= 0.5, else escalate | 2 (distinct people) |
| T3 | Never acts: recommendation only, a person decides | confirm >= 0.5, else escalate | 2 |

Thresholds can only be changed by humans in the studio, never by the copilot or an agent (GR-14). Full authoring
guide: `docs/DECISIONS_GUIDE.md`.

## Repository layout

```
backend/   Maven multi-module Spring Boot 3.5 / Java 21 modular monolith (oms-platform ... oms-integrations, oms-copilot, oms-app)
ui/        React 18 + Vite + TypeScript operator console
config/    tenant configuration as data: tenants/demo (seeded), tenants/starter (template), tenants/retail, tenants/electronics, tenants/apparel
docs/      guides, architecture, ADRs, API reference, runbook, traceability, examples, launch material, static site
scripts/   try.sh, bootstrap.sh, new-tenant.sh, dev.sh, build-ui.sh, demo.sh, token.sh, usecases/, reference/
deploy/    Kubernetes reference manifests and Helm chart
load/      k6 scenarios (NFR-02 thresholds)
```
