# Changelog

All notable changes to Intent OMS are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project uses semantic versioning.

## [Unreleased]
- **Self-Optimizing Order Orchestration — fixed and finished.** The first SOO entry below overstated what was built:
  a strict audit found 6 of 63 functional requirements done, runtime bugs that broke the learning screens, and a
  task queue that filled with duplicate escalations. This pass fixes those and builds the rest; the status of every
  requirement, with code and tests, is in `docs/SOO_TRACEABILITY.md`.
  - Fixed: proposals were stored without their change type and could not be read; a scheduled scorer re-decided every
    open order every minute, opening a new episode and a new escalation task each time (one demo order collected
    hundreds of tasks); outcomes attached to only one episode per order; newer episodes never got rewards; the MCP
    learning tools failed for every caller; most learners ignored autonomy and the kill switch; the bandit logged the
    wrong propensity; the Tasks page's default view returned nothing.
  - Migrations fold the existing duplicates: one episode per decision point, and one open escalation task per subject
    for the scorers that re-evaluate (`sla_breach_risk`, `stuck_order_diagnosis`), with the rest linked to it.
  - New: learner governance (registry, global/tenant/learner kill switch, pause, autonomy × tier gate, never-autonomous
    changes), live use of a learned value as a promoted change with range and max step, a decision-fabric hook for
    learned features, calibration, provider routing and experiment routing, chargeback/contact/COD signals and fraud
    learners, fulfilment and promise learners, inventory and customer learners, every change type applied and rolled
    back, shadow/canary/holdout experiments and a lift report, governance events, change cards, digests, retention.
  - UI: a Learning console built for first-time users (plain-language names and autonomy, captions instead of
    paragraphs, technical details behind a toggle, demo-data badges), errors shown in plain words, a Tasks badge that
    counts only the queues you work, and a Why drawer that shows which learned facts a decision used.
  - Demo tenant reference data (`SooDemoSeeder`), `scripts/usecases/uc8-self-optimizing.sh`, and a local Playwright
    suite (`make e2e`).
- **Self-Optimizing Order Orchestration** (`oms-learning`, new module): closes the loop from outcome back to policy.
  Every decision now logs propensity (`decision_record.context_features/arms/chosen_arm/propensity/policy_version`)
  so past decisions can be fairly replayed against a new policy. Episodes are assembled from the existing event
  ledger and joined to a typed outcome catalog (delivery, fulfilment, return, payment, contact, inventory); rewards
  are computed from a tenant's versioned `ObjectiveProfile` and corrected as late outcomes land. Fast-loop parameter
  learners (node reliability, lane transit times, a contextual bandit over the five sourcing policies, customer
  preference facts, fraud-threshold tuning, decision-fabric band/calibration tuning) update typed facts in minutes,
  no LLM involved. A slow-loop Analyst/Author/Critic pipeline (extends `oms-copilot`) raises typed hypotheses and
  drafts policy changesets nightly, re-validated against the live artifact before ever being stored. Nothing is
  live until it clears, in order: an off-policy replay evaluator (inverse-propensity and doubly-robust estimation
  with a 95% confidence interval, extends `oms-simulation`), a shadow run, and a canary with a persistent holdout —
  then a tiered promotion gate (T0 auto-promotes on positive evidence, T1 only at learner autonomy L3, T2 always
  needs approval, T3 never autonomous) drives the real change through the existing decision publish/rollback path.
  Governance: per-learner autonomy levels (default L1, recommend-only), weekly change budgets and max-step caps,
  freeze windows, automatic rollback on guardrail breach, coverage and drift monitors that auto-pause learning on
  silent data loss or a distribution shift, and crypto-shred propagation for deleted subjects. New Learning Console
  (`/learning`) and two read-only MCP tools, `learning_status` and `explain_policy_change`. See
  `docs/UC_SELF_OPTIMIZING_ORCHESTRATION.md`.
- One-command trial and distribution: `scripts/try.sh` / `scripts/try.ps1` (Docker-only build-or-pull, health wait,
  login table, browser; `--reset`, `--down`, `--pull`), `scripts/doctor.sh` environment check, `make try|reset|down|
  doctor|helm-lint`, `docker-compose.quickstart.yml` for the published image, `.devcontainer/` for GitHub Codespaces
  and VS Code (Temurin 21, Node 22, Docker-in-Docker, bootstrap on start), Helm chart `deploy/helm/intent-oms`,
  `OMS_DEMO_TENANTS` in `deploy/k8s/oms.yaml`, release workflow (multi-arch GHCR image, cosign, SBOM, GitHub release
  with jar) and GitHub Pages workflow for `docs/site/`; Dockerfile gains BuildKit caches and OCI labels, `.dockerignore`
  added; `docker-compose.yml` passes `OPENROUTER_API_KEY`/`OPENAI_API_KEY` and honours `OMS_PORT`. New
  `docs/QUICKSTART.md`.
- Copilot demo scenarios UC6 (plain-English policies → governed drafts) and UC7 (Margin Guardian digest, ops Q&A, explain, CS draft) in `scripts/usecases/`.
- `POST /api/v1/admin/tenants` (platform administrators) and `GET /api/v1/admin/tenants`; `scripts/new-tenant.sh` uses them before falling back to psql.
- Golden-set evaluation compares Score answers at rubric-level tolerance (0.5) so probability-weighted Jev scores match their level.
- Jev and LLM providers accept OpenRouter keys (`sk-or-`) with sensible defaults; Jev budget gains a gateway latency allowance.
- Return risk v2 adds business fast-lane rules; rule reason codes surface on RMAs.

### Added
* Copilot module (`oms-copilot`, `/api/v1/copilot`): plain-English policy changes rendered as validated change sets
  and staged as studio drafts or draft artefacts, tenant bootstrap from a business description, operations Q&A over
  whitelisted SQL templates, decision explanations, customer-service reply drafts and a KPI digest. Drafts only;
  publishing and money movement stay human.
* Platform hardening: OIDC resource-server authentication, production fail-fast checks, security headers, login rate
  limiting, envelope encryption of tenant keys, dependency and container scanning in CI.
* Integrations module (`oms-integrations`): GraphQL API at `POST /graphql` mirroring the REST shapes, generic HTTP
  WMS connector with signed release/cancel documents, EasyPost carrier aggregator (labels, tracker webhooks), Smarty
  address verification, SendGrid and Twilio customer notifications from static templates, marketplace webhook
  (Amazon SP-API or generic payloads) and Amazon SP-API order poller, `GET /api/v1/integrations/status`.
* Open-source release scaffolding: licence, contributor and governance documents, security policy, threat model,
  compliance mapping, adopter guides, API reference, deployment guide, runnable examples, starter tenant and
  `scripts/bootstrap.sh` / `scripts/new-tenant.sh`.

### Added (launch, 2026-10)
* Reference implementations: tenants `retail` (Maple & Main General Store), `electronics` (Voltline Electronics) and
  `apparel` (Lumen & Loom) under `config/tenants/`, each with five scripted scenarios in `scripts/reference/`
  (`docs/REFERENCE_IMPLEMENTATIONS.md`).
* Playground (`/playground`): place an order and watch the decision trace animate with layer, confidence, latency and
  cost, plus the network map.
* Public customer tracking page (`/track/<token>`) built on the customer-safe view and explanation.
* Margin Replay (`/replay`): upload a Shopify CSV export and get a report of shipping saved, margin recovered and
  splits avoided; bundled sample included.
* AI Economics analytics: automation rate, cost per 1k decisions, human decisions avoided, p95 latency by layer.
* Showcase page with a guided tour.
* One-command try: `scripts/try.sh` / `make try`, `docker-compose.quickstart.yml` with the GHCR image, Codespaces
  devcontainer, Helm chart in `deploy/helm/intent-oms`.
* Static landing page for GitHub Pages in `docs/site/` published by `pages.yml`.

### Documentation (launch)
* README rewritten for the public release; the long feature matrix, provider setup and degraded-mode notes moved to
  `docs/FEATURES.md`.
* New: `docs/WHY.md` (why typed LLM decisions, and the limits), `docs/COMPARISON.md` (against a conventional
  rules-engine OMS), `docs/ROADMAP.md` (next two quarters with help-wanted labels),
  `docs/REFERENCE_IMPLEMENTATIONS.md`, `docs/images/README.md` (screenshots to capture).
* `docs/launch/`: LinkedIn posts, launch blog post, Show HN text, demo video script, live demo talk track, one-pager
  for teams building a new OMS, launch checklist.
* `docs/FAQ.md` gains the launch questions; `CONTRIBUTING.md` gains "Good first issues" and the reference
  implementation contribution guide.

## [0.1.0] - 2026-10-05

Initial public release.

### Added
* Modular monolith on Java 21 / Spring Boot 3.5 / PostgreSQL 16 with sixteen Maven modules (ADR-001).
* Order capture over REST, Shopify webhook, MCP and UCP with idempotency, a configurable validation pipeline, typed
  tenant extensions and agent provenance.
* Configurable statechart engine: tenant YAML models with CEL guards, quantity-level status, typed holds, SLA timers,
  versioning with dry-run migration and static checks.
* Durable Postgres-backed workflow engine with activities, retries, timers, signals, saga compensation, human task
  queues and bulk operations (ADR-002).
* Decision fabric: Jev (TypeSafe System One) as the default typed-decision provider with LLM and heuristic fallbacks
  (ADR-003); L0 rules, composite scoring, two-stage top-K, confidence bands by risk tier, shadow and canary rollouts,
  replay, kill switches, AI budgets and full decision traces.
* Policy studio: drafts, structured edits, lint, impact diff, golden-set evaluation gate, tiered approvals with
  separation of duties, rollback and emergency publish with retro review.
* Sourcing and promise: candidate generation, economics, intent-driven split policy, profitability floor, cached promise.
* Inventory ATP and strongly consistent reservations; finance (PSP-agnostic payments, tax SPI, order P&L, landed cost,
  spend guardrails); returns with risk scoring, keep-it and disposition; control tower with exceptions, playbooks and
  alerts; agent registry, mandates, MCP server, UCP order capability, injection screening and evidence packs;
  simulation and analytics.
* Append-only event ledger with transactional outbox (ADR-004), audit log, per-tenant PII tokenisation with
  crypto-shredding.
* React operator UI, demo tenant with five scripted scenarios, k6 load test, Kubernetes reference manifests.

[Unreleased]: https://github.com/pradeepvanalli/Intent_Based_OMS/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/pradeepvanalli/Intent_Based_OMS/releases/tag/v0.1.0
