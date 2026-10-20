# Changelog

All notable changes to Intent OMS are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project uses semantic versioning.

## [Unreleased]
- **Self-Optimizing Order Orchestration — fixes and completion work.** The first SOO entry below overstated what was built:
  a strict audit found 6 of 63 functional requirements done, runtime bugs that broke the learning screens, and a
  task queue that filled with duplicate escalations. This pass fixes those and builds the rest; the status of every
  requirement, with code and tests, is in `docs/SOO_TRACEABILITY.md`. An independent audit of commit `fea4c21`
  (19 October 2026) found 60 of 63 functional requirements done, 2 partial and 1 needing real traffic to judge.
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
  - Fixed after a second audit:
    - Orders: an order that loses the last units at reservation is re-sourced or backordered instead of failing. Each
      fulfilment group's last parcel counts as delivered. A carrier scan for an unknown parcel is ignored, and a lost
      or damaged parcel counts as a late delivery.
    - Rewards: one return outcome per return (the refund replaces the estimate). Fill counts every unit ordered, and
      there is a new first-attempt fill. A perfect-order component (unweighted) now heads the lift report. Reward
      scales come from the holdout once it has 30 episodes.
    - Fraud: fraud labels are sampled from cohorts whose chargeback window has closed, for the tuner, the signal
      learner and the drift monitor. Return grading labels the return's risk decision. Fraud changes are evaluated on
      labelled fraud history.
    - Governance: live-use ranges are checked against each value's domain (a demo range in the wrong units had sent
      about 190 delay emails). Guardrails also watch live-use promotions, and proposals are governed by the learner
      that owns the target. The cooldown applies only after a rollback for cause. The global kill switch and the
      tenant list need the platform engineer role. The critic reviews every evaluation.
    - Learning inputs: human resolutions record the value the person chose, and calibration and learners read it.
      Read-only sourcing scores are logged as shadow. Day parts use the tenant's clock. Erased customers are never
      learned again, and a repeated erasure also removes facts stored under the pseudonym.
    - Privacy: contact details typed into notes, return reasons, complaints, customer messages and agent
      instructions are redacted before they reach a decision record or a model prompt.
    - Screens: the Tasks page opens decision escalations, shows the recommendation and records the choice. The return
      inspection dropdown offers only conditions the API accepts. The Learning console shows the perfect-order
      headline, a 4-week guardrail report, the critic's review and each live value's allowed range.
  - Fixed after a third audit:
    - Security: nobody can grant a role or permission they do not hold. A tenant admin could create a user or API key
      with the platform-engineer role and from there flip the global kill switch or list every tenant; that and the
      same hole in agent registration are closed, key issue and revoke are audited, and revoke is tenant-scoped.
    - Governance: a change is judged against what is live. A proposal drafted against an older version, or from a
      value that is no longer live, is refused at evaluation and at promotion, and the max step is measured from the
      live value. Live use takes the tier of the decisions it feeds, so a learned value feeding a T2 decision never
      promotes itself. The target's owner governs a learner's draft (the stricter of owner and drafter wins). Canary
      duration and shadow sample size have tenant floors a caller cannot go below, and a canary cannot be completed
      early. Promotion re-checks a live-use range against its domain, and switching on a family nothing has learned
      for is weak evidence.
    - Rewards: the north star is §2's definition, judged per line (delivered complete, on time, undamaged, not
      returned for a fulfilment reason); contacts and chargebacks no longer count against it. The weighted fill term
      is first-attempt fill. Every term has an explicit scale (from the holdout, else the tenant's own episodes, else
      a stated default), shown by `GET /learning/reward-baseline`. A won chargeback blames nobody, a refused
      cash-on-delivery parcel costs its return leg rather than the order, and a node's refusal is scored against
      the choice that planned its group.
    - Learning inputs: a reservation race is not a store reject. Outcomes of a re-sourced group reach the decision
      that planned it. Provisional rewards cannot make a sourcing policy the greedy one. The calibration report is
      measured out of sample, lane learning works right after boot, and the warn-early learner and the warning gate
      read the same score. Late orders, carrier exceptions and nodes likely to breach now score without a model, in
      every tenant. Inventory values are judged on what they change, not on an unrelated decision.
    - Decision fabric: a return review resolves and labels its risk escalation; labels say who resolved (person,
      agent, system) and learners read people only. Calibration learns from live resolutions and is live on demo.
      Override-to-rule is no longer blocked by golden-set candidates, and its rule generalises. Rules are judged on
      the ground truth that exists (fraud outcomes), so "the model beats the rule" is reachable. The fraud-label
      replay compares like with like and says what it covers. Off-policy evaluation operates on real propensities:
      a governed exploration share for low-risk decisions only, and bandit changes evaluated with IPS and doubly
      robust estimates. The critic weighs the IPS cross-check and the mature share.
    - Privacy: names and street addresses typed into notes are redacted along with emails and phone numbers.
      Customers are keyed by their vault token in every learning table, existing rows are re-keyed on upgrade, and
      an erasure also scrubs the customer's facts from decision records, the why view and old learning events. The
      decision log is append-only: later annotations go to their own append-only table, a database trigger refuses
      any other change, and legal erasure is the one scoped exception.
    - Screens: the outcome score is explained as a weighted sum, never labelled "0 to 1"; system actors have plain
      names; the live-use form follows each family's domain.
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
