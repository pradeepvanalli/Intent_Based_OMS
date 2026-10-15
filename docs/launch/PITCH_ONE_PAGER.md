# Intent OMS for a team building a new OMS

One page. For the engineering lead or product owner who has been asked to build or replace an order management
system and is deciding what to build on.

## What you get

**A decision layer you do not have to design.** Decisions are typed YAML objects with enumerated options, risk tiers,
confidence bands, rules, defaults and golden sets. Four layers at runtime: rules, a typed decision model, an LLM
restricted to strict JSON, a person. Measured in the demo at 350 to 850 ms per typed decision at about $0.00002 each.
Runs with no AI key.

**A policy studio with governance built in.** Drafts, lint, impact diff over real history, golden-set gate, approval
by someone other than the author, shadow, canary, rollback, kill switches, emergency publish with retro review. A
copilot that turns a sentence into a draft and never publishes.

**The boring parts done.** Durable Postgres workflow engine with retries, timers, signals, saga compensation and human
task queues. YAML statecharts with quantity-level status, holds and SLA timers. Append-only event ledger with
transactional outbox, enforced in the database. ATP and row-locked reservations. Sourcing economics, promise cache,
profitability floor. Returns with risk-scored refund timing. Control tower, exceptions, playbooks, alerts.

**The new parts done.** MCP server, UCP order capability, signed agent mandates, injection screening, evidence packs.
Margin replay of a Shopify export. Public customer tracking page with a safe explanation. AI economics analytics.

**A platform shape you can live with.** Java 21 / Spring Boot 3.5 modular monolith, 16 modules with one-way
dependencies, PostgreSQL 16, Redis, React console. OIDC SSO, RBAC, PII tokenisation, security headers, production
fail-fast profile. Docker Compose, Codespaces, Kubernetes manifests, Helm chart. 570 backend tests. Apache-2.0.

**Three reference implementations.** General merchandise (BOPIS, curbside, hazmat, margin floor), electronics
(pre-orders, address risk, backorders, warranty, drop-ship), apparel (bracketing, exchanges, keep-it, drops,
final-sale). Five scripted scenarios each, so your first demo to your own stakeholders is a script away.

## What it would take to adopt

| Phase | Weeks | What happens |
|---|---|---|
| Replay | 1 | Export your orders. Run the margin replay. Load the nearest reference tenant and walk your scenarios. Decide whether the numbers justify the next phase. |
| Configure | 2 to 4 | Describe your business to the Bootstrap copilot or copy a tenant. Nodes, catalogue, rate cards, state models, decisions, compliance rules. Label a golden set for the decisions that matter. |
| Connect | 3 to 6 | Storefront webhook, WMS release document, carrier, address, notifications, PSP. Each has a contract, a mock and a signed webhook. Your own adapters are SPI beans. |
| Shadow | 2 to 4 | Run decisions in shadow against your current logic on live traffic. Compare. Tune bands and rules in the studio. |
| Cut over | 1 to 2 | Switch one channel or region live with canary rollout. Keep the kill switches close. Expand. |

Twelve weeks from export to a live channel is realistic for a mid-market retailer with one engineer and one person who
owns the policies. The deferred items (EDI, parallel regions, visual designer, SCIM, multi-region) are listed in
`docs/ROADMAP.md`; if one of them is on your critical path, it moves up.

## What the author brings

* The design and every line of the platform: workflow engine, decision fabric, studio, ledger, agent channel,
  copilot, console. I know where the seams are and why they are there.
* A working method for turning a retailer's policies into typed decisions with golden sets, and for proving them in
  shadow before they act.
* Reference implementations for three verticals and the scripts that demonstrate them to stakeholders.
* Published threat model, compliance mapping and gap analysis. I would rather you read the limits than discover them.
* Availability to work with your team: architecture, implementation, or building the parts your business needs on
  the roadmap, in the open where possible.

## Next step

Thirty minutes with the person who owns your order flow, and one order export. Repository:
`https://github.com/pradeepvanalli/Invent_Based_OMS`. LinkedIn: `https://www.linkedin.com/in/REPLACE_ME`.
