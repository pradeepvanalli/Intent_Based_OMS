# How Intent OMS differs from a conventional OMS

This page compares Intent OMS with a conventional rules-engine OMS as a category: a system where order routing,
eligibility and exception handling are configured as rule tables by an implementer, and where anything the rules do
not cover lands in a work queue. It does not describe or make claims about any specific product. Where Intent OMS has
a gap, the table says so.

| Capability | Conventional rules-engine OMS | Intent OMS |
|---|---|---|
| **Sourcing** | Rule tables per channel: nearest node, priority lists, static "ship complete" flags set at implementation. Margin is computed in finance after the fact. | Candidate plans scored on cost, margin, ETA and a typed node score with confidence. The buyer's intent is read from the note ("send everything together") and sets the split policy. A profitability floor parks a loss-making order on a finance task with the alternatives attached. Every plan considered is stored with its economics. |
| **Exceptions** | Fixed exception codes, manual queues, playbooks as runbooks people follow. | Exceptions are classified by a typed decision; playbooks run automatically when confidence and spend limits allow and open a task otherwise. SLA breach risk is scored before the breach. A stuck-order sweeper watches for missing next events. |
| **Configuration** | Implementer-owned rule tables and workflow definitions. A change is a ticket, a test cycle and a release. Nobody can show what would have flipped. | Everything a tenant can change is YAML, versioned and editable at runtime behind a publish pipeline: lint, impact diff over real history, golden-set gate, approval by someone other than the author, canary, rollback, kill switch. A copilot turns a sentence into a reviewable draft. The model never publishes. |
| **Returns** | Eligibility by rule, refund on scan for everyone, fraud scoring in a separate tool, store and online returns in different systems. | One return flow across channels including in-store and agent. L0 policy rules first (trusted fast lane, flagged customer), then an abuse-risk score with reason codes drives refund timing. Keep-it when reverse logistics cost more than recovery. Refund velocity caps and a circuit breaker. |
| **Agent commerce** | Agents are treated as another web channel, if at all. No mandate, no limit enforcement, no proof when the chargeback arrives. | Agent registry with trust tiers, signed mandates with limits checked before capture, MCP server and UCP order capability, instruction text screened and stored as data, one-click dispute evidence pack. |
| **Explainability** | An "explain" layer narrates the rules after the fact, or a log file. Customers get a status word. | Every decision stores its full trace: layers run, rules fired, band, provider, latency, cost. Operators read it in the Why drawer. Customers get a safe sentence derived from the same trace on the public tracking page. |
| **Simulation** | Test environments with copied data; no replay of a proposed rule against history. | Rebuild any order from the ledger. Replay decisions against a proposed version and see the flips. Batch what-if for node closure, carrier outage or tariff change. Margin replay of a Shopify export under candidate policies. |
| **Cost model** | Licence per order or per revenue, plus implementation services, plus a change budget. | Apache-2.0. Runs on PostgreSQL and one JVM. AI cost is metered per decision and capped per tenant per day. The typed decision layer measured at about $0.00002 per call in the demo; the whole system runs with no AI key at all. |
| **Deployment** | Vendor-hosted or a sizeable installation with several services. | One deployable service, PostgreSQL 16 and Redis. Docker Compose, Codespaces, Kubernetes manifests, Helm chart. OIDC SSO, RBAC, PII tokenisation, append-only ledger enforced in the database. |

## What a conventional OMS still does better today

* **EDI and legacy connectors.** X12 850/855/856/810 and POS file imports are on the roadmap, not in the release.
* **Breadth of out-of-the-box integrations.** Intent OMS ships one connector per category (Shopify, a generic HTTP WMS,
  EasyPost, Smarty, SendGrid, Twilio, Amazon SP-API) with deterministic mocks; a mature product ships dozens.
* **Visual workflow and state designer.** State models are shown as diagrams but edited as YAML or through the copilot.
* **Multi-region active-active.** Postgres active-passive is the supported shape.
* **Years of production hardening.** Intent OMS is new. The threat model, compliance mapping and known gaps are
  published so you can judge for yourself (`docs/THREAT_MODEL.md`, `docs/COMPLIANCE.md`, `SECURITY.md`).

The full list of deferred requirements with reasons is in `docs/GAP_ANALYSIS.md`; the plan to close them is in
`docs/ROADMAP.md`.
