# Show HN

Replace `REPO` with the repository URL. Post on a weekday between 8 and 10 am US Eastern. Stay in the thread for the
first three hours and answer every technical question with a file path.

## Title

```
Show HN: Intent OMS – open-source order management built on typed LLM decisions
```

(HN titles allow 80 characters; this one is 78.)

## Text

```
Intent OMS is an open-source order management system for retailers where every decision (sourcing, split, address risk, refund timing, agent mandate) is a typed YAML object with enumerated options, a risk tier and confidence bands.

Decisions pass through four layers: L0 CEL rules, L1 a typed decision model that can only answer within the options and reports calibrated confidence, L2 an LLM restricted to strict JSON that may only confirm one of the same options, L3 a human task. The tier sets the bands; money-moving decisions act only above 0.9, recommendation-only decisions never act.

The model never publishes configuration. A copilot turns a sentence into a draft; the studio runs lint, an impact diff over real history, a golden-set gate, separation of duties and a canary.

Java 21, Spring Boot, Postgres, one deployable. Runs with no AI key. Apache-2.0.

REPO
```

(About 150 words.)

## First comment (technical details)

```
Author here. Some details people usually ask about:

Stack: Java 21 / Spring Boot 3.5 modular monolith (16 Maven modules, one-way dependencies enforced in the poms), PostgreSQL 16 with hand-written SQL via JdbcClient, Flyway, React/Vite console. No Lombok, no ORM.

Workflow: a durable step engine on Postgres (steps, retries, timers, per-order ordered signals, saga compensation, human task queues). The WorkflowEngine interface is the seam for a Temporal adapter; definitions map one to one. ADR-002 explains why we did not ship Temporal by default.

Ledger: every state change appends to an append-only event table in the same transaction as the row change, with a transactional outbox. Append-only is enforced by a database trigger, not by convention. Any order can be rebuilt from the ledger; that is what simulation and replay use.

Decisions: config/tenants/demo/decisions/*.yaml. Each has tier, provider, fallbackProvider, timeoutMs, default, rules (CEL, first match), questions (Choice / Score / Noul), bands, postChecks, optional composite weights and two-stage top-K for long candidate lists. The default L1 provider is Jev (TypeSafe System One) via OpenRouter; Anthropic and OpenAI-compatible providers and a deterministic heuristic ship too. A provider is a Spring bean implementing DecisionProvider.

Measured in the demo run (docs/DEMO_SAMPLES.md): L1 answers in 350 to 850 ms through the gateway at about $0.00002 per call. A policy change on fulfilment_intent flipped 6 of 33 historical decisions; golden set accuracy 1.0, calibration error 0.012.

PII: fields named name/email/phone/address in the provider state are HMAC-tokenised per tenant before any model call; rotating the key crypto-shreds history.

Agents: MCP server with 11 tools, UCP order capability, signed mandates with limits checked before capture, instruction text stored as data and screened by a T0 decision, evidence pack endpoint.

Tests: 570 backend tests, Postgres via Testcontainers or a compose instance, no network or keys required.

What it does not have yet: EDI, parallel statechart regions, a visual designer, SCIM, multi-region. docs/GAP_ANALYSIS.md and docs/ROADMAP.md list these with reasons. The threat model and compliance mapping are in docs/ too.

Happy to answer anything about the design.
```
