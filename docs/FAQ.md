# Frequently asked questions

### Do I need an AI key to run it?

No. Without `OPENROUTER_API_KEY`, `TYPESAFE_API_KEY`, `ANTHROPIC_API_KEY` or `OPENAI_API_KEY` every decision runs on
its L0 rules, the deterministic `heuristic` fallback and its `default`. Orders capture, source, ship and return. You
will see more human-review tasks (T2 decisions escalate when the fallback cannot reach the 0.9 act band) and the
free-text parts of the demo (inferring intent from a note) need an explicit `intentChoices` value instead. `GET
/api/v1/decisions/providers` shows what is available.

### Why did a decision escalate / use the default / ignore the model?

Read `GET /api/v1/decision-records/{id}/why`. It lists every layer that ran, the band, the rules fired, the checks
and `details.defaultReason`. Common causes: the provider is unavailable or killed (`provider.kill_switch.*`,
`ai.kill_switch`, budget exceeded), the answer was the escape option (band forced to ESCALATE), a post-check failed, the
confidence fell in the confirm band on a T2 decision with no L2 provider, or the tier is T3 (never acts).

### Why can't I publish my decision?

Non-emergency publish requires: status `approved` with the right number of distinct approvers (T0/T1 one, T2/T3 two;
the submitter cannot approve), a run and passed golden-set evaluation when the YAML declares `evaluation`, and a clean
lint. The problem detail says which gate failed. `emergency: true` with `config.admin` bypasses them and schedules a
48 h retro review; use it for incidents and bootstraps.

### Why does the starter tenant's evaluation fail?

The starter uses production gates (0.90 accuracy in the act band, 0.05 calibration error). The deterministic heuristic
provider is designed never to be that confident, so the gate fails until Jev or an LLM is configured. The demo tenant
relaxes the gates to 0.6/0.5 for that reason. `scripts/new-tenant.sh` publishes initial versions through the
emergency path by default; see its `--publish` option.

### How do I reset the demo?

Stop the service, then `docker exec oms-postgres-1 psql -U oms -d postgres -c "drop database oms with (force);" -c
"create database oms;"` and start again. The seeder re-creates everything on first start.

### How do I create a second tenant?

`scripts/new-tenant.sh <tenantId> "<name>"` copies `config/tenants/starter` and loads it through the API. There is no
tenant-creation endpoint: a tenant row is created when a user for it exists (`OMS_DEV_USERS`, OIDC with a tenant claim)
or by the script's `--create-login` option, which inserts the tenant and an admin user through `psql`.

### Where do tenant settings live, the YAML or the database?

Both, deliberately. `config/tenants/<t>/` is the Git-backed source the seeder and `new-tenant.sh` load; the database
is the runtime truth, editable through the API and the UI behind the publish pipeline. The seeder writes a
`tenant_config` key only when it is absent, so runtime edits are not overwritten on restart. Export runtime state back
to YAML with `GET /api/v1/admin/config`, `GET /api/v1/decisions/{name}/versions/{v}` (the `yaml` field) and the other
GET endpoints.

### Can I use Temporal / Camunda instead of the built-in workflow engine?

The engine is a Postgres step machine behind the `WorkflowEngine` interface (ADR-002). Definitions are step maps with
activity semantics that map one to one onto Temporal workflows and activities, so an adapter is feasible; none ships
today.

### Can I split the monolith into services?

Modules depend one way and talk through interfaces or ledger events (ADR-001). The outbox already publishes to Kafka
(`OMS_KAFKA_ENABLED=true`). Splitting along module seams is possible; the single transaction that writes rows and
appends events is the property to preserve.

### How are customer names and e-mails protected from the model providers?

`state.include` paths named `name`, `email`, `phone` or `address` are tokenised per tenant with an HMAC key before the
provider call (`em_3f9a…`). Tokens are stable so history questions still work. Rotating the key (`Tokenizer.shred`)
makes every historical token unlinkable (crypto-shredding). Raw PII lives only in the order tables.

### Does the LLM ever act on its own?

No. The L2 layer runs only in the confirm band on T1/T2 decisions, is given the trace and the enumerated options, and
may only return one of those options; anything else is discarded. T3 decisions never act. The copilot writes drafts.

### What stops a prompt injection in a customer note or an agent instruction?

The text is stored as data, screened by the `injection_screen` decision (T0 Noul), and only ever appears in the
typed `state` of a provider request, never in the instructions. Flagged agent instruction text puts the order on an
`agent_verification` hold and opens a `risk_review` task (UC4 shows it).

### How do refunds stay under control?

`SpendGuard` enforces refund and appeasement velocity caps per tenant, per actor and per agent, with a circuit breaker
that routes refunds to the `refund_approvals` queue for 15 minutes when tripped. Refunds above
`returns.refund_requires_approval_above_usd` always need an approval task. Every refund is a ledger event with its
actor.

### What is Jev, and can I use something else?

Jev (TypeSafe System One) answers typed questions (Choice, Score, Noul) with calibrated confidence quickly and
cheaply, which is why it is the default L1 provider (ADR-003). It is one `DecisionProvider` among several:
`llm.anthropic`, `llm.openai` (any OpenAI-compatible endpoint, including OpenRouter) and `heuristic` ship; your own is
a Spring bean (`docs/examples/extensions/CustomDecisionProvider.java`). Every definition declares its provider and
fallback.

### How do I connect a real WMS, carrier, address or notification provider?

Select the adapter with `OMS_WMS_PROVIDER=http`, `OMS_CARRIER_PROVIDER=easypost`, `OMS_ADDRESS_PROVIDER=smarty`,
`OMS_NOTIFY_ENABLED=true` plus the provider credentials (`docs/CONFIGURATION.md` §1), then set the tenant-level
endpoints and secrets through the admin API (`wms.<nodeId>.release_url`, `wms.webhook_secret`,
`carrier.easypost.webhook_secret`, `notify.brand_name`). `GET /api/v1/integrations/status` confirms what is active.
The outbound WMS release document contract (fields, headers, retry and idempotency rules) is in the
`HttpWmsConnector` Javadoc (`backend/oms-integrations/.../wms/HttpWmsConnector.java`) and summarised in `docs/API.md`;
the WMS reports back through `POST /webhooks/wms/{nodeId}`.

### Is there a GraphQL API?

Yes: `POST /graphql` with the same authentication and permissions as REST; the schema mirrors the REST shapes for
orders, promise, inventory and returns (`docs/API.md` "GraphQL"). GraphiQL is at `/graphiql` when `OMS_GRAPHIQL=true`.

### Which Shopify topics are handled?

`orders/create` through `POST /webhooks/shopify`. Other `orders/*` topics are accepted and mapped as captures
(replays return the existing order); non-order topics are ignored with 204.

### How do I run the tests without Docker?

Point `OMS_TEST_JDBC_URL` at any PostgreSQL 16 database (plus `OMS_TEST_JDBC_USER`/`PASSWORD`). Without that, tests use
the compose database on :5440 if reachable, else Testcontainers.

### Is there an EDI or marketplace connector?

Marketplaces: `POST /webhooks/marketplace/{name}` accepts Amazon SP-API–shaped or generic order payloads, and the
Amazon SP-API poller (`AMAZON_SPAPI_ENABLED=true`) ingests `Unshipped`/`PartiallyShipped` orders on an interval.
EDI X12 and POS file imports are not in this release; `docs/TRACEABILITY.md` lists what is deferred.

### How do I create the first administrator in production?

Dev users are forbidden in production. Either sign in through OIDC with a group mapped to the `admin` role
(`oms.security.oidc.role-mapping`), or insert one `app_user` row with a bcrypt hash (what `scripts/new-tenant.sh
--create-login` does through `psql`), then create the rest with `POST /api/v1/auth/users`.

### What are the known security gaps?

No SCIM or just-in-time provisioning (SSO users have no `app_user` row, so `change-password` is 403 for them); no
refresh tokens or server-side revocation of local session tokens (rotate `OMS_JWT_SECRET` to log everyone out); login
rate limiting is per instance (N replicas → N× the limit); API keys do not expire; no MFA for local accounts (use the
IdP's). Full list: `SECURITY.md` "Known gaps" and `backend/oms-platform/HARDENING.md` §6.

### Where are the metrics and traces?

`GET /actuator/prometheus` (key series in `docs/RUNBOOK.md`), OTLP tracing to `OTEL_EXPORTER_OTLP_ENDPOINT`, logs with
the order id as correlation id.

## Launch questions

### Is it production ready?

It is new software, and the honest answer is "ready to be proven on your traffic, not ready to be trusted blind". What
is there: OIDC SSO, RBAC, per-tenant PII tokenisation, an append-only ledger enforced by database triggers, a
production profile that refuses to start with insecure settings, 570 backend tests, dependency and container scanning
in CI, a published threat model (`docs/THREAT_MODEL.md`), compliance mapping (`docs/COMPLIANCE.md`) and a known-gaps
list (`SECURITY.md`). What is not there yet is listed in `docs/GAP_ANALYSIS.md` and `docs/ROADMAP.md`. The adoption
path we recommend is replay, then shadow mode on live traffic, then a canary on one channel or region
(`docs/launch/PITCH_ONE_PAGER.md`).

### Do I need an AI key?

No. Every decision runs on its L0 rules, the deterministic `heuristic` fallback and its `default`. Orders capture,
source, ship and return. You will see more human-review tasks and the free-text parts of the demos (reading intent from
a note) need an explicit choice instead. One `OPENROUTER_API_KEY` switches on both the typed decision layer (Jev) and
the LLM layer; direct TypeSafe, Anthropic and OpenAI-compatible keys also work. The first FAQ entry above has the
details.

### What does it cost to run?

Infrastructure: one JVM (2 GB heap is comfortable for the demo), PostgreSQL 16 and Redis. The quick start runs on a
laptop; `docs/DEPLOYMENT.md` has sizing. AI: the typed decision layer measured at about $0.00002 per call in the demo
run (`docs/DEMO_SAMPLES.md`), so a thousand orders with three or four decisions each cost well under a dollar. The
copilot is the expensive part at roughly $0.15 per change set with Claude Sonnet through OpenRouter, and it is used a
few times a day, not per order. Every tenant has a daily AI budget (`ai.budget_usd_per_day`) and the AI Economics page
in Analytics shows cost per thousand decisions live.

### Can I use my own LLM?

Yes. A decision provider is a Spring bean implementing `DecisionProvider`; `llm.anthropic`, `llm.openai` (any
OpenAI-compatible endpoint, including local servers that speak that protocol) and `heuristic` ship, and
`docs/examples/extensions/CustomDecisionProvider.java` is a template for your own. Each decision YAML names its
`provider` and `fallbackProvider`. The L2 layer needs structured output (a forced tool or `json_schema` response
format) because answers outside the enumerated options are discarded. The copilot uses any OpenAI-compatible endpoint
(`LLM_MODEL`, `OPENAI_BASE_URL`).

### How do I bring my orders?

Three ways, in increasing order of effort. For evaluation, export orders from Shopify as CSV and upload them at
`/replay`; the margin replay reports shipping saved, margin recovered and splits avoided without any integration. For a
pilot, connect the Shopify `orders/create` webhook (`POST /webhooks/shopify`), a marketplace webhook or the Amazon
SP-API poller, and the orders flow live (`docs/GETTING_STARTED.md` §7). For a migration, post historical orders to
`POST /api/v1/orders` with their original `channelOrderId` (idempotent) or through the GraphQL API;
`docs/SAMPLE_ORDERS_FORMAT.md` describes the shape and `docs/examples/python/place_order.py` is a working loader.

### How do I contribute?

Start with `CONTRIBUTING.md`: setup, conventions, tests, DCO sign-off, and the "Good first issues" and "Reference
implementation contribution guide" sections. Issues labelled `good first issue` are scoped to one afternoon; `help
wanted` items on `docs/ROADMAP.md` are larger. The most valuable non-code contribution is a labelled golden set for a
decision in one of the reference tenants. Questions go to Discussions; security reports go through `SECURITY.md`.
