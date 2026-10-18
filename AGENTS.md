# AGENTS.md — read this first

This is the entry point for an AI coding agent working in this repository. It is deliberately dense: commands and
non-negotiable rules, not a tutorial. For the long-form version of anything here, follow the pointer. Nested
`AGENTS.md` files exist for the areas with their own sharp edges (see the table at the bottom); read the one in the
directory you're about to touch before you start, in addition to this one.

## What this is

Intent OMS: an order management system where every judgement call (which node ships a line, whether to split, a
return's fraud risk, whether an AI agent's order is legitimate) is a typed decision — deterministic CEL rules first,
then a typed model restricted to enumerated options with a calibrated confidence, then an LLM in strict JSON, then a
person — never an LLM writing free text into a system of record. Java 21 / Spring Boot 3.5 modular monolith
(`backend/`, one deployable, `oms-app`), PostgreSQL 16 (`JdbcClient` + Flyway, jsonb, append-only event ledger),
React 18 / Vite / TypeScript console (`ui/`). Everything a tenant can change is YAML data under
`config/tenants/<tenant>/`, versioned and replayable.

## Build, test, run

```bash
scripts/bootstrap.sh                                   # fresh clone → running app on :8080, demo tenant seeded
cd backend && mvn -q -DskipTests clean install          # build everything (NOT `mvn -pl oms-app install` — see gotcha below)
mvn -q -pl oms-<module> test                            # one module's tests
cd ui && npm run lint && npm test -- --run && npm run build
```

**Build gotcha**: `mvn -pl oms-app install` without `clean` does not refresh the nested module jars inside
`intent-oms.jar` — always `clean install` across the reactor after touching an upstream module.

**Test-database gotcha**: `PostgresTestBase` resolves a real Postgres in this order: `OMS_TEST_JDBC_URL` env var →
the `docker compose` Postgres on `localhost:5440` (same one `scripts/bootstrap.sh`/`dev.sh` use) → a Testcontainers
`postgres:16-alpine`. If you run many scoped `mvn test` passes against the `:5440` compose instance across
different module subsets, its Flyway schema-history table can end up inconsistent (a migration resolved on the
classpath that was never recorded as applied against that specific history). The fix is `scripts/bootstrap.sh
--stop && docker compose rm -f postgres && docker volume rm oms_pgdata && scripts/bootstrap.sh` — it's a throwaway
dev volume, reseeded from config every time, never worth debugging the history by hand.

**Before you call anything done**: `mvn -q -ntp verify` at the backend root (or at minimum the modules you touched
plus anything that depends on them) and, if you touched `ui/`, `npm run lint && npm test -- --run && npm run build`.
A change isn't finished because it compiles.

## Non-negotiable conventions

* **Tenant scoping.** Every table has `tenant_id`; every query filters by `TenantContext.require()`. Never trust a
  tenant id from a request body. A background job iterates tenants explicitly (`TenantContext.current().map(List::of)
  .orElseGet(() -> repo.tenantsWithRecords(since))` then `TenantContext.runAs(tenant, ...)`), never assumes one.
* **Persistence.** Plain `JdbcClient` with hand-written SQL, not Spring Data JDBC, not jOOQ. jsonb columns via
  `Jsonb.of(obj)` going in, `select col::text as col` + `Json.read(...)` coming out. Ids: `Ids.id()` (UUIDv7,
  sorts by creation order within one JVM, don't rely on cross-instance ordering) or `Ids.prefixed("rma")` for
  human-visible ids.
* **Typed unions, not strings.** A closed set of variants (an outcome type, a policy change, an order modification)
  is a `sealed interface` with `record` implementations, `@JsonTypeInfo(use = Id.NAME, property = "type")` +
  `@JsonSubTypes`. Grep `ai.intentoms.order.modification.Modification` or `ai.intentoms.learning.model.PolicyChange`
  for the exact pattern before inventing a new one.
* **Events.** Append to the ledger (`EventStore.append(aggregateType, aggregateId, NewEvent(type, payload, actor))`)
  in the same transaction as the row change. Type grammar is `<aggregate>.<past_tense>` (`order.captured`,
  `episode.matured`), never a verb. React to events with `EventSubscriber` (`eventTypePrefixes()` + `on(Event)`,
  dispatched post-commit, in per-aggregate order, must be idempotent) rather than calling across modules directly —
  this is how a new module (SOO's `oms-learning`, for example) attaches to the order path without any existing
  order-capture code knowing it exists.
* **Migrations.** Flyway, `src/main/resources/db/migration/V<range>__<module>_<topic>.sql`, inside your module's
  reserved version range (`docs/ARCHITECTURE.md` §2 has the table; claim a new range there if you add a module).
  Check what the next free version actually is before picking one — don't assume.
* **Errors.** Throw `NotFoundException`, `ConflictException`, `ValidationException`, `ForbiddenException`,
  `GuardrailViolationException`, `InvalidTransitionException`; they map to RFC 9457 problem details automatically.
  Don't catch-and-swallow at a controller.
* **Security.** Controllers: `@RequiresPermission(Permissions.X)`, permission names `<domain>.<action>`, added to
  `oms-platform`'s `Permissions.java` by appending (never reordering/removing existing constants).
* **Time.** Inject `TimeSource`, never call `Instant.now()`/`LocalDate.now()` in business logic that a test needs to
  control — the one defensible exception is enriching a ledger event payload with real wall-clock delivery data,
  which is inherently about what actually happened, not a replayable business decision.
* **AI is never synchronous on a hot path.** Order capture never calls a model inline. A typed decision goes through
  `DecisionService.decide(name, input, DecisionOptions.live(...))`, never throws on AI failure (falls back to
  `Layer.DEFAULT`), and `result.acts()` must be checked — `requiresHuman` means recommendation only.
* **No Lombok, no field injection.** Records for values, final classes, constructor injection.

## Module map

One-way dependency order (a module depends only on modules at or above its own position; `|` marks modules with no
dependency on each other, verified against the actual `pom.xml` files, not assumed): `platform → statemachine |
workflow → decision → learning → inventory → finance → order → sourcing → monitoring | returns → agent | simulation
| analytics | copilot | orchestration → integrations → app`. The one placement worth internalizing: `oms-learning`
sits right after `oms-decision`, depending only on `platform`/`decision`/`workflow` — `sourcing`, `inventory`,
`copilot`, `simulation`, `orchestration` and `agent` all depend on *it* (to read learned facts and raise
proposals), never the other way around. `oms-integrations` is the one module that depends on `oms-orchestration`;
`oms-copilot` and `oms-orchestration` don't depend on each other at all, despite sitting next to each other above.

| Module | What it owns |
|---|---|
| `oms-platform` | tenancy, security, ids, json, jdbc, errors, CEL, event ledger, PII tokens |
| `oms-statemachine` | configurable statecharts, holds, SLA timers |
| `oms-workflow` | durable step engine, human tasks, bulk ops |
| `oms-decision` | the decision fabric: definitions, L0 rules, providers, runtime, studio, golden sets |
| `oms-inventory` | ATP, row-locked reservations, feeds |
| `oms-finance` | payments, tax, compliance, P&L, landed cost, spend guard |
| `oms-order` | canonical order, capture pipeline, intent, modifications |
| `oms-sourcing` | nodes, candidates, economics, promise |
| `oms-monitoring` | pipeline view, exceptions, playbooks, SLA scorer, alerts |
| `oms-returns` | RMA lifecycle, risk, refund timing, disposition |
| `oms-agent` | agent registry, mandates, MCP server, UCP, injection screen, evidence packs |
| `oms-simulation` | replay, batch simulation, Margin Replay, what-if |
| `oms-analytics` | KPIs, AI economics |
| `oms-orchestration` | workflows, the Agentic Control Tower (`autonomy` package), demo seeder |
| `oms-copilot` | prompt-based configuration, the Analyst/Author/Critic SOO roles |
| `oms-integrations` | GraphQL, carrier/address/notify adapters, WMS, marketplace poller |
| `oms-learning` | Self-Optimizing Order Orchestration: episodes, outcomes, learners, promotion gate |
| `oms-app` | Spring Boot main, bundled UI |

## Where to go for more

| Need | File |
|---|---|
| Human contribution workflow, "how do I add a decision/state model/SPI/module" | `CONTRIBUTING.md` |
| Module public-API contracts | `docs/ARCHITECTURE.md` |
| **As-built API surface — read before writing code against a module** | `docs/MODULE_NOTES.md` |
| REST/GraphQL/MCP/UCP/webhook reference | `docs/API.md` |
| Full feature inventory, verified against the code, with an honest gap list | `docs/FEATURE_INVENTORY.md` |
| The Agentic Control Tower mechanism | `docs/UC_AGENTIC_CONTROL_TOWER.md` |
| Self-Optimizing Order Orchestration mechanism | `docs/UC_SELF_OPTIMIZING_ORCHESTRATION.md` |
| Tenant YAML reference (every file and key) | `docs/CONFIGURATION.md` |
| Writing a decision / a state model | `docs/DECISIONS_GUIDE.md`, `docs/STATE_MODELS_GUIDE.md` |

`docs/MODULE_NOTES.md` is the one that matters most before you write code: it's the as-built signature reference,
kept in the same pull request as the code it documents (see `CONTRIBUTING.md`'s rule on this) — when it and
`docs/ARCHITECTURE.md` disagree, `docs/MODULE_NOTES.md` is newer and more likely correct, but the actual source
under `backend/<module>/src/main/java` is the real authority.

## Security and safety

Never commit a real API key — `.env` is gitignored for exactly this; `scripts/bootstrap.sh` reads it.
`ProductionReadinessCheck` fails startup in the `prod` profile on a weak JWT secret, dev users configured, wildcard
CORS, or a missing PII master key — don't weaken it to make a local run easier. PII is tokenized before it ever
reaches a model (`Tokenizer`, the `ai.intentoms.*.screen.InjectionScreen` wrapping of any customer/agent free text);
don't introduce a code path that hands raw PII to an LLM prompt. Refund and cancel are hard-excluded from
autonomous execution in both the Agentic Control Tower and SOO's promotion gate, checked in code, not just
documented — don't add a configuration knob that could re-enable them.

## Nested AGENTS.md files

| Directory | Covers |
|---|---|
| `backend/oms-decision/AGENTS.md` | The decision fabric — L0/L1/L2/L3, providers, golden sets, the publish gate |
| `backend/oms-learning/AGENTS.md` | Self-Optimizing Order Orchestration |
| `backend/oms-agent/AGENTS.md` | Agent commerce — MCP, UCP, mandates, injection screening |
| `backend/oms-orchestration/AGENTS.md` | Workflows and the Agentic Control Tower |
| `ui/AGENTS.md` | The React console |
| `config/AGENTS.md` | Tenant configuration as data |
