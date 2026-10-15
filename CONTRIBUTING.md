# Contributing to Intent OMS

Thank you for considering a contribution. This guide covers the development setup, how the code is organised, how to
add the things people most often add (a module, a decision, a state model, an SPI implementation), test conventions and
the commit and pull request rules. Governance and release process are in `GOVERNANCE.md`; conduct in
`CODE_OF_CONDUCT.md`; vulnerability reporting in `SECURITY.md`.

## Development setup

Prerequisites:

| Tool | Version | Notes |
|---|---|---|
| JDK | 21 | Temurin or OpenJDK. The Makefile defaults `JAVA_HOME` to the Homebrew path; override with `JAVA_HOME=...` |
| Maven | 3.9+ | The build uses the Spring Boot 3.5 parent |
| Node.js | 22 (20+ works) | UI only (`ui/`), `npm ci` |
| Docker | with Compose v2 | PostgreSQL 16 and Redis; Testcontainers fallback for tests |
| curl, python3 | any recent | used by `scripts/*.sh` and the examples |

One-command setup for a fresh clone:

```bash
scripts/bootstrap.sh           # checks tools, starts Postgres/Redis, builds UI + backend, starts the service, seeds demo
```

Manual equivalent:

```bash
cp .env.example .env
make infra                     # docker compose up -d postgres redis  (Postgres on localhost:5440, oms/oms)
make ui                        # optional: builds the React UI into backend/oms-app/src/main/resources/static
make build                     # cd backend && mvn -q -DskipTests clean install
make run                       # cd backend/oms-app && mvn spring-boot:run   → http://localhost:8080
```

`make run` seeds the `demo` tenant from `config/tenants/demo` on first start (`OMS_DEMO_SEED=true`). Logins are
tenant `demo`, user `admin` / `admin` (also `ops`, `cs`, `risk`, `finance`, `strategy`; password = user name).

UI development: `cd ui && npm run dev` serves on :5173 with a proxy to :8080; `npm run dev:mock` needs no backend.

### Build gotcha

`mvn -pl oms-app install` without `clean` does not refresh the nested module jars inside `intent-oms.jar`. After
changing any upstream module run `cd backend && mvn -q -DskipTests clean install` (what `make build` does) or
`mvn -q -pl oms-<module> -am install -DskipTests` followed by `mvn -q -pl oms-app -DskipTests clean install`.

## Repository layout

```
backend/        Maven multi-module Spring Boot 3.5 / Java 21 modular monolith
  oms-platform      shared kernel: tenancy, security, ids, json, jdbc, errors, CEL, ledger + outbox, audit, config, PII tokens
  oms-testing       PostgresTestBase, TestApplication
  oms-statemachine  configurable statecharts, holds, SLA timers, versioning
  oms-workflow      durable step engine, human tasks, bulk operations
  oms-decision      decision definitions, L0 rules, providers (jev, llm.*, heuristic), runtime, studio, golden sets
  oms-inventory     ATP, reservations, feeds
  oms-finance       payments, tax, compliance rules, P&L, landed cost, spend guard
  oms-order         canonical order, capture pipeline, intent, order types, CS view and modifications
  oms-sourcing      nodes, candidates, economics, promise
  oms-monitoring    pipeline view, exceptions, playbooks, SLA scorer, alerts
  oms-returns       RMA lifecycle, risk, refund timing, disposition
  oms-agent         agent registry, mandates, MCP server, UCP, injection screen, evidence packs
  oms-simulation    replay, batch simulation, what-if
  oms-analytics     KPIs, decision analytics
  oms-orchestration workflows, playbook actions, bulk handlers, escalation handler, demo seeder, webhooks
  oms-integrations  GraphQL API, HTTP WMS connector, EasyPost, Smarty, SendGrid/Twilio, marketplace webhook + Amazon SP-API poller
  oms-copilot       prompt-based configuration and operations assistance (/api/v1/copilot)
  oms-app           Spring Boot main, application.yml, Dockerfile, bundled UI
ui/             React 18 + Vite + TypeScript operator console
config/         tenant configuration as data: config/tenants/demo (seeded), config/tenants/starter (template)
docs/           architecture, ADRs, guides, API reference, runbook, traceability, examples
scripts/        bootstrap.sh, new-tenant.sh, dev.sh, build-ui.sh, demo.sh, token.sh, usecases/
deploy/         Kubernetes reference manifests
load/           k6 scenarios
```

Modules depend one way only, in the order listed (`docs/ARCHITECTURE.md` §1). A module may call another only through
the public Java API documented in `docs/ARCHITECTURE.md` §3 and `docs/MODULE_NOTES.md`, or react to ledger events via
`EventSubscriber`. Spring component scanning covers all of `ai.intentoms`; both applications use
`FullyQualifiedAnnotationBeanNameGenerator`, so same-named classes in different modules are fine.

## Conventions

* Package root `ai.intentoms.<module>`; sub-packages `model`, `repo`/`store`, `service`, `api`, `config`.
* Records for values, final classes, constructor injection, no Lombok, no field injection.
* Javadoc cites the requirement IDs it implements (`FR-*`, `GR-*`, `NFR-*` from `docs/requirements.txt`) so
  traceability is greppable.
* Persistence: `JdbcClient` with hand-written SQL; jsonb through `Jsonb.of(obj)`; ids from `Ids.id()` (UUIDv7) or
  `Ids.prefixed("rma")`. UUIDv7 ids are strictly increasing within one JVM (RFC 9562 counter method), so they sort by
  creation order and keep index locality; do not rely on ordering across instances. Every table has `tenant_id`;
  every query filters by `TenantContext.require()`. Never trust a tenant id from a request body.
* Migrations: Flyway `src/main/resources/db/migration/V<range>__<module>_<topic>.sql` within the module's version
  range (platform 100–199, statemachine 200–299, workflow 300–399, decision 400–499, inventory 500–599, finance 600–699,
  order 700–799, sourcing 800–899, monitoring 900–999, returns 1000–1099, agent 1100–1199, simulation 1200–1249,
  analytics 1250–1299, orchestration 1300–1399). Append-only tables get the `reject_mutation()` trigger.
* Events: append to the ledger with `EventStore.append(...)` in the same transaction as the row change; type grammar
  `<aggregate>.<past_tense>`.
* Errors: throw `NotFoundException`, `ConflictException`, `ValidationException`, `ForbiddenException`,
  `GuardrailViolationException`, `InvalidTransitionException`; they map to RFC 9457 problem details.
* Security: controllers use `@RequiresPermission(Permissions.X)`; permission names follow `<domain>.<action>`.
* Time: inject `TimeSource`, not `Instant.now()`, in business logic.
* REST: `/api/v1/<resource>`, JSON, lists return `Page<T>` with `offset`/`limit`, mutations accept `Idempotency-Key`.
* Hot paths stay allocation-light; AI is never called synchronously in order capture (GR-07).

## How to add things

### A decision

1. Write the YAML in `config/tenants/<tenant>/decisions/<name>.yaml` following `docs/DECISIONS_GUIDE.md`. Every
   Choice question needs an escape option; every definition needs a `default`.
2. Add a golden set in `golden-sets/<name>_gold.json` and reference it under `evaluation:`.
3. Call it from code with `DecisionService.decide(name, input, DecisionOptions.live(subjectType, subjectId, actor))`
   and check `result.acts()`; a `requiresHuman` result is a recommendation only.
4. Add the name to the relevant `order-types.yaml` `decisions:` list if a workflow invokes it.
5. Document the input shape in `docs/MODULE_NOTES.md` (oms-decision section).

### A state model

1. Author `config/tenants/<tenant>/state-models/<entity>.<variant>.yaml` per `docs/STATE_MODELS_GUIDE.md`.
2. Validate with `POST /api/v1/state-models/validate {yaml}`; the validator rejects unreachable states, dead ends,
   unknown guard roots and `api.*` triggers without a permission.
3. Bind it from `order-types.yaml` (`headerStateModel`, `lineStateModel`).
4. Code fires transitions with `StateMachine.fire(new TransitionRequest(...))`; actions listed in the YAML are
   executed by the orchestration workflow, so a new action name needs a handler there.

### A playbook action, decision provider, address verifier or payment gateway

Implement the SPI and register a Spring bean; the defaults are `@ConditionalOnMissingBean` or property-selected.
Reference implementations live in `docs/examples/extensions/`.

| SPI | Interface | Registration |
|---|---|---|
| Playbook step | `ai.intentoms.monitoring.playbook.PlaybookAction` | any `@Component`; `action()` is the YAML key |
| Decision provider | `ai.intentoms.decision.provider.DecisionProvider` | any `@Component`; referenced by `name()` in YAML `provider:` |
| Address verification | `ai.intentoms.order.validation.AddressVerifier` | one bean replaces `MockAddressVerifier` |
| Payment gateway | `ai.intentoms.finance.payment.PaymentGateway` | `@ConditionalOnProperty(oms.finance.psp=<name>)` |
| Human escalation | `ai.intentoms.decision.runtime.EscalationHandler` | one bean replaces the recording default |
| Bulk action | `ai.intentoms.workflow.bulk.BulkActionHandler` | any `@Component` |

### A module

1. Create `backend/oms-<name>` with the parent `ai.intentoms:oms-parent`, add it to the root `pom.xml` `<modules>` and
   `<dependencyManagement>`, and depend only on modules above it in the dependency order.
2. Claim a Flyway version range in `docs/ARCHITECTURE.md` §2 (coordinate in the pull request).
3. Add the public contract to `docs/ARCHITECTURE.md` §3 and the as-built notes to `docs/MODULE_NOTES.md`.
4. Add a `TestApplication`-based integration test that boots the module with its upstream dependencies.

## Tests

* Unit tests for pure logic; integration tests extend `ai.intentoms.testing.PostgresTestBase` with
  `@SpringBootTest(classes = TestApplication.class) @ActiveProfiles("test")`. The base class binds tenant `test` and an
  admin principal before each test.
* Database resolution in `PostgresTestBase`: `OMS_TEST_JDBC_URL` (+ `OMS_TEST_JDBC_USER`/`OMS_TEST_JDBC_PASSWORD`) if
  set; otherwise the compose Postgres on localhost:5440 (a throwaway `oms_test_<id>` database is created and dropped);
  otherwise a Testcontainers `postgres:16-alpine` container (Docker required). When sharing one database across
  modules set `spring.flyway.ignore-migration-patterns=*:missing,*:future` in the module's test base.
* Naming: `*Test` and `*IntegrationTest` run under surefire; `*IT` is ignored.
* Tests must not need network access or API keys. The decision module ships `HeuristicDecisionProvider` for this; use
  WireMock for HTTP providers.
* `TenantConfigStore.set(key, String)` treats a `String` as raw JSON: write string values as `Json.write(str)`.
* Workflow tests call `WorkflowWorker.runOnce()` deterministically; `oms.workflow.enabled=false` disables pollers.
* Run everything: `make test` (or `cd backend && mvn -q test`); one module: `mvn -q -pl oms-<module> test`.
* UI: `cd ui && npm run lint && npm test -- --run && npm run build`.

## Commits and pull requests

* Small, focused pull requests against `main`. One logical change per PR; refactors separate from behaviour changes.
* Commit messages: imperative subject under 72 characters, a body that says why, and the requirement IDs touched.
  Example: `decision: reject rule values that are not options (GR-04, FR-DEC-03)`.
* **DCO sign-off is required.** Add `Signed-off-by: Your Name <you@example.com>` to every commit (`git commit -s`).
  By signing off you certify the [Developer Certificate of Origin 1.1](https://developercertificate.org/).
* Fill in the pull request template. Contract changes (REST paths or bodies, public Java interfaces, YAML formats,
  migrations) need two maintainer approvals; everything else one. The author never merges their own change.
* CI must be green: backend `mvn verify` with Postgres, UI lint/test/build, container build and the security scans.
* Keep `docs/MODULE_NOTES.md` and `docs/CONFIGURATION.md` in step with the code in the same pull request.

## Good first issues

Issues labelled [`good first issue`](https://github.com/pradeepvanalli/Invent_Based_OMS/labels/good%20first%20issue) are scoped
to one afternoon, touch one module or one tenant folder, and have a test or a script that shows they are done. Typical
shapes:

* **A golden-set case.** Add a labelled case to `config/tenants/<tenant>/golden-sets/<decision>_gold.json` for a
  situation the set does not cover (a gift return without a receipt, a hazmat line in a BOPIS order). Run the
  evaluation (`POST /api/v1/decisions/{name}/versions/{v}/evaluate`) and include the result in the pull request.
* **A customer-facing string.** Translations of the customer-safe explanation templates and notification templates
  (`es-US` first).
* **An example.** A curl, Python or TypeScript example under `docs/examples/` that runs against a fresh
  `scripts/bootstrap.sh` install.
* **A console screen.** One screen of the store associate app or one chart on the analytics page, against existing
  endpoints.
* **A connector behind an existing SPI** with a deterministic mock and a contract test (`CarrierConnector`,
  `AddressVerifier`, `PaymentGateway`, `PlaybookAction`).
* **A documentation fix** where the docs and the code disagree. The code wins; cite the controller or YAML.

Before starting, comment on the issue so nobody else picks it up. If you want to work on something that has no issue,
open one first and say what you intend to change. Larger items carry the `help wanted` label and are listed with their
scope in `docs/ROADMAP.md`.

## Reference implementation contribution guide

A reference implementation is a complete tenant for one retail vertical with five scripted scenarios. Three ship
today (`retail`, `electronics`, `apparel`; see `docs/REFERENCE_IMPLEMENTATIONS.md`). Grocery, furniture and B2B are
the ones most often asked for. A contribution has four parts.

### 1. The tenant folder: `config/tenants/<vertical>/`

Copy the closest existing tenant, not `starter`, so the scenarios have realistic nodes, SKUs and rate cards to work
with. Keep the same file set (`tenant-config.yaml`, `order-types.yaml`, `state-models/`, `decisions/`, `golden-sets/`,
`rules/compliance.yaml`, `catalog.yaml`, `nodes.yaml`, `shipping-rates.yaml`, `playbooks.yaml`, `sla.yaml`,
`exception-taxonomy.yaml`, `agents/agents.yaml`). Rules of the folder:

* The business is fictional, the data is invented, and nothing in it resembles a real company or person. Names like
  "Maple & Main General Store" are the pattern.
* Every decision the scenarios touch has a golden set with cases for every option and every rule boundary. Evaluation
  gates stay at the production values (0.90 accuracy, 0.05 calibration) unless the `README.md` explains why not.
* Compliance rules and L0 rules encode the policies that must never be a judgement call for that vertical (cold chain
  for grocery, two-person delivery for furniture). Put them in rules, not in prompts.
* `tenant-config.yaml` is commented key by key, as in `starter`.
* No secrets anywhere in the folder; agent secrets are placeholders the loader replaces.

### 2. `config/tenants/<vertical>/README.md`

One paragraph on the business and what makes its order flow different. A table of the files and what each configures,
in the format of `config/tenants/starter/README.md`. A table of the five scenarios with one line each. The logins. What
to change first if someone copies this tenant for their own business.

### 3. The scenario script: `scripts/reference/<vertical>.sh`

Follow `scripts/reference/retail.sh`. The script sources `scripts/usecases/lib.sh`, supports `--load-only` and
`--only <id>`, creates the tenant and logins if missing (through `POST /api/v1/admin/tenants`), loads the folder through
the same API calls `scripts/new-tenant.sh` makes, seeds the inventory the scenarios need, runs the five scenarios, and
prints what to open in the console after each. Every scenario must:

* run without an AI key (degraded mode) and finish with the expected state, with a note on what changes when a key is
  present;
* be idempotent, so running the script twice does not create duplicate orders or fail on existing config;
* end with the decision trace lines for the decisions it exercised, in the same format as `scripts/usecases/`.

Scenario ids are the vertical's initial plus a number (`G1` to `G5` for grocery). Five is the number; fewer looks thin
and more stops being a demo.

### 4. Documentation and checks

* Add the tenant to the table and a section to `docs/REFERENCE_IMPLEMENTATIONS.md`, and a row to the README table.
* Add the tenant id to the default `OMS_DEMO_TENANTS` list only if the seeded data stays under roughly 50 orders;
  otherwise document that it is loaded by its script.
* Add a `docs/images/README.md` row for one screenshot that shows the vertical's distinctive decision.
* The pull request description links a terminal capture of the script's full run, with and without a key.
* CI runs `scripts/reference/<vertical>.sh --load-only` against the test database; the loader must succeed with no
  manual steps.

Label the pull request `reference-implementation`. Two maintainer reviews are required because it adds configuration
contracts; expect questions about the golden sets first.

## Writing documentation

Plain, specific, no marketing. Endpoint names and YAML keys come from the code, not from memory; when in doubt read the
controller. Examples in `docs/examples/` must run against a fresh `scripts/bootstrap.sh` install.
