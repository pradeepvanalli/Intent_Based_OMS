# Gap analysis against the PRD (v0.1) — and what this release adds

Status of every requirement row is in `TRACEABILITY.md`. This page is the honest list of what a mid-market retailer
would still miss, what was added in the 2026-10-06 release to close it, and what remains deferred with a reason.

## Added in this release

| Area | Gap | Added |
|---|---|---|
| Prompt-based configuration (FR-CFG-01/02/03, FR-SM-12, FR-WF-03, UC-10/11) | Policies were YAML edited in the studio; a non-technical operator could not change behaviour from a sentence | `oms-copilot`: describe a change in English → validated, diffed, tested **drafts** across decisions, rules, state models, order types, SLAs, playbooks and tenant settings; staging only — publishing still goes through lint → golden set → approvals |
| Onboarding (goal: live in ≤ 12 weeks) | Starting from the demo tenant meant hand-editing a dozen YAML files | **Bootstrap copilot**: describe your business → a reviewable configuration bundle; `config/tenants/starter/` and `scripts/new-tenant.sh` for the manual path |
| FR-MON-08 ops copilot | No plain-language questions over live data | `POST /api/v1/copilot/ops/ask` with a whitelist of parameterised queries; the query used is always shown |
| FR-XAI-03 plain-language explanation | Only the raw trace | `GET /api/v1/copilot/explain/{recordId}` (labelled generated, numbers checked against the trace, customer-safe variant) |
| FR-CS-05 CS copilot | None | `POST /api/v1/copilot/cs/draft` grounded in order facts; dates/amounts only from the order |
| Margin visibility for leaders | KPIs only | **Margin Guardian digest**: weekly findings with the money at stake and a suggested copilot prompt per finding |
| FR-CAP-01 GraphQL (P0) | REST only | `/graphql` with capture, modify, cancel, order/promise/inventory/returns queries |
| Section 11 P0 connectors | Mocks only for notifications, address, carrier, marketplace | SendGrid/Twilio notifier, Smarty address verifier, EasyPost carrier connector + tracker webhook, Amazon-shaped marketplace ingestion, HTTP WMS connector — all optional, mocks stay the default |
| NFR-07 SSO | Local users and API keys only | OIDC resource-server support (Keycloak/Entra/Okta claim mapping), production fail-fast checks, security headers, login rate limit, envelope encryption of PII keys, SBOM/dependency/secret scanning in CI |
| AI providers | Direct TypeSafe/Anthropic/OpenAI keys only | OpenRouter routing for Jev (`typesafe/jev-1.13`) and LLMs with one key; gateway latency allowance on the GR-07 budget |

## Still deferred (with reasons)

| Requirement | Why deferred | Suggested path |
|---|---|---|
| FR-CAP-01 EDI X12 850/855/856/810 (P1) | Needs a trading-partner profile model and an EDI parser library decision | Add an `oms-edi` module using an X12 parser; map 850 → `CaptureRequest`, emit 855/856/810 from ledger events |
| FR-CAP-08 drafts/quotes, FR-CAP-09 consolidation (P1) | Not needed by design partners for MVP | Quotes = orders in a `quoted` header state with price-lock expiry timer; consolidation = a sweeper over `awaiting_sourcing` orders sharing customer+address |
| FR-SM-04 parallel regions (P1) | Current statechart supports nesting via `parent`; parallel regions need a different runtime model | Model payment as its own entity (`payment` aggregate already exists) rather than a region |
| FR-SM-09 visual designer (P1) | UI shows the auto-laid-out diagram and YAML; editing is YAML | Add drag-to-edit on the SVG writing back to YAML |
| FR-WF-03 workflow steps edited by business users (P1) | Workflows are code (Temporal-compatible step maps) | Expose "insert human review step for <condition>" as a copilot-generated hold + playbook (already possible) rather than editing workflow code |
| FR-SRC-10 allocation protection, FR-SRC-11 negotiation mode (P1/P2) | Inventory demand types exist; channel/tier reservations need policy UI | Add `allocation_rule` table consumed by `InventoryService.atp` |
| FR-INV-05 Jev inventory confidence (P1) | Hook `InventoryConfidenceUpdater` exists | Nightly batch decision `inventory_confidence` over cycle-count age/rejects |
| FR-MON-07 anomaly detection with incident summaries (P1) | Baselines need history | Compute z-scores over `kpi_daily`; summarise with the explain copilot |
| FR-RET-07 full identity resolution (P1) | Cross-channel history by customer ref + e-mail token exists | Add phone/address tokens and a probabilistic match |
| FR-AGT-07 onX, FR-AGT-08 ACP checkout (P1) | Standards still moving | Adapter layer with version negotiation is in place for UCP; add onX/ACP adapters on the same `AgentOrderService` |
| FR-SIM-04 A/B experiments (P1) | Canary by subject hash exists | Add experiment table with guardrail metrics and automatic stop |
| FR-PRM-09 es-US text (P2), FR-CFG-04 legacy importer (P2), DMN import (P2) | P2 | — |
| NFR-07 SCIM provisioning | OIDC covers authentication; provisioning needs a SCIM server | Add `/scim/v2` Users/Groups mapped to `app_user` |
| NFR-04 multi-region active-active (P2) | Infrastructure | Postgres active-passive first |

## Things the PRD does not say but a retailer will ask for

* **Store associate task app** (section 15 open question): the task queues and MCP tools exist; a mobile UI is not built.
* **Customer-facing order status page**: UCP/MCP `order_get` returns a customer-safe view; a hosted page is one small front end away.
* **Multi-currency**: `Money` carries currency and refuses mixed arithmetic; FX is not modelled.
* **Promotions and pricing**: out of scope by design (the OMS consumes prices).
