# Changelog

All notable changes to Intent OMS are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project uses semantic versioning.

## [Unreleased]
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

[Unreleased]: https://github.com/pradeepvanalli/Invent_Based_OMS/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/pradeepvanalli/Invent_Based_OMS/releases/tag/v0.1.0
