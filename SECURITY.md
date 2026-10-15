# Security Policy

## Supported versions

| Version | Supported |
|---|---|
| `main` | yes (fixes land here first) |
| latest `0.x` minor and its patch releases | yes |
| older `0.x` minors | no; upgrade |

`0.x` releases may change contracts between minors (see `GOVERNANCE.md`). Security fixes are back-ported only to the
latest minor.

## Reporting a vulnerability

**Do not open a public issue, discussion or pull request for a vulnerability.**

Report privately through GitHub's private vulnerability reporting on this repository
(`Security` → `Report a vulnerability`), or by e-mail to security@intentoms.ai. If you need to encrypt, ask for the
current PGP key in your first message.

Include: affected version or SHA, the surface (REST, webhook, MCP/UCP, UI, LLM provider path, database), a
reproduction, impact as you understand it, and whether the issue is already public. Please do not test against
systems you do not own, and do not access or modify other people's data while reproducing.

### What to expect

| Step | Target |
|---|---|
| Acknowledgement | within 3 business days |
| Triage and severity (CVSS 3.1) | within 7 days |
| Fix for critical/high | within 30 days of triage; coordinated disclosure date agreed with you |
| Fix for medium/low | next scheduled release, at most 90 days |
| Advisory | GitHub Security Advisory with CVE when applicable, crediting the reporter unless they prefer otherwise |

We follow coordinated disclosure: we ask reporters to hold details until the advisory is published, and we will not take
legal action against good-faith research that respects the rules above.

## Security standards of the project

These are the properties the code is built to keep. A change that weakens one of them is a security bug, and a pull
request that does so will not be merged (see `GOVERNANCE.md`). Where a standard maps to a PRD guardrail or
non-functional requirement the id is given; coverage is tracked in `docs/TRACEABILITY.md`.

### Secrets and configuration

* Secrets come only from environment variables, mounted secret files (`oms.security.jwt-secret-file`) or a secret
  store. No secret is read from tenant YAML in `config/`; `.env` is git-ignored and `.env.example` holds placeholders.
* No default secret in production. With `spring.profiles.active=prod` (what `docker-compose.prod.yml` and the
  Kubernetes manifest set) or `oms.security.enforce-production=true`, `ProductionReadinessCheck` aborts startup before
  any bean is created and lists every violation: a JWT secret that is the placeholder, contains `change-me` or is
  shorter than 32 characters; any `OMS_DEV_USERS`; demo seeding or demo agent secrets enabled; CORS without explicit
  `https://` origins; actuator exposing more than `health,prometheus`; a missing `OMS_MASTER_KEY`.
* API keys are stored as SHA-256 hashes; agent HMAC secrets are encrypted at rest (`oms.agent.secret-key`); per-tenant
  PII keys are envelope-encrypted with AES-256-GCM under `OMS_MASTER_KEY` (`oms.security.master-key`), legacy plaintext
  rows being re-wrapped on first use. Passwords are bcrypt; the password policy requires 12 characters
  (`oms.security.password-min-length`) for anything set through the API.
* The container runs as the unprivileged `oms` user on a JRE-only image, exposes one port, carries no build tools,
  and runs with a read-only root filesystem and a tmpfs `/tmp` (`docker-compose.prod.yml`; pod and container
  `securityContext` with dropped capabilities and `RuntimeDefault` seccomp in `deploy/k8s/oms.yaml`).

### Authentication and authorisation (NFR-07)

* People authenticate with OIDC (resource server: issuer discovery or explicit JWKS, audience check, role mapping from
  IdP claims) or with locally issued HS256 session tokens; integrations with API keys (`oms_<id>_<secret>`, hash
  stored); agents with HMAC-SHA256 request signatures (`X-Agent-Id`, `X-Agent-Timestamp`, `X-Agent-Signature` over
  `timestamp\nMETHOD\npath\nsha256(body)`, ±300 s replay window, rate limited per agent).
* Authorisation is RBAC with the permission grammar `<domain>.<action>` (`order.cancel`, `decision.publish`,
  `config.admin`); roles map to permission sets, tenants may extend roles, and every controller declares
  `@RequiresPermission`. Agents get permissions from their registry limits, never from a back door; unknown agents are
  read-only on orders their provenance proves they placed.
* Login (`POST /api/v1/auth/login`) is rate limited per IP and per tenant/user (10 attempts per minute by default,
  excess → `429 rate_limited`), takes constant time for unknown users, and writes `auth.login` / `auth.login_failed`
  audit rows with the client IP. `POST /api/v1/auth/logout` (204) ends the client session; `POST
  /api/v1/auth/change-password` requires the current password; `POST /api/v1/auth/users` (`config.admin`) creates
  local users. Sessions are stateless bearer tokens; CSRF does not apply.
* Every response carries security headers: a Content-Security-Policy tuned for the bundled SPA (inline scripts
  allowed by sha256 hash only, `frame-ancestors 'none'`), `X-Content-Type-Options`, `Referrer-Policy`,
  `Permissions-Policy`, `X-Frame-Options`, `Cross-Origin-Opener-Policy`, and HSTS in production. Swagger UI gets a
  relaxed CSP and is disabled in production unless `OMS_SWAGGER_ENABLED=true`.
* Separation of duties is enforced in code (GR-14): the submitter of a decision change cannot approve it, T2/T3
  decisions need two distinct approvers, only humans may edit definitions, and thresholds are human-only.

### Tenant isolation (NFR-09)

* Every table carries `tenant_id` and every query filters by the request's tenant. The tenant is taken from the
  authenticated principal, never from a request body; public webhook paths require `X-Tenant-Id` and verify a
  per-tenant signature. Per-tenant rate limits and AI budgets bound the blast radius of one tenant on another.

### Data protection (NFR-08, NFR-14, GR-09)

* The event ledger, audit log and decision traces are append-only, enforced by database triggers, with configurable
  retention (`retention.years`, default 7).
* PII (names, e-mails, phones, addresses) is tokenised with a per-tenant HMAC key before it reaches any model provider;
  the same input yields the same token so history questions still work without exposing the value. Deleting a tenant
  key crypto-shreds every historical token (`Tokenizer.shred`); raw PII lives only in the order tables, which are
  deleted per request (CCPA/CPRA, see `docs/COMPLIANCE.md`).
* Payment tenders are PSP tokens; no PAN enters the system (`docs/COMPLIANCE.md`, PCI scope).

### Integrations

* Outbound webhooks are CloudEvents signed with HMAC-SHA256 under a per-subscription secret (`X-OMS-Signature:
  sha256=<hex>`), with delivery logging and retry. UCP lifecycle webhooks are signed with the agent's secret
  (`X-UCP-Signature`).
* Inbound WMS/carrier/inventory webhooks verify `X-Webhook-Signature` when `webhooks.inbound_secret` is set; the Shopify
  webhook verifies `X-Shopify-Hmac-Sha256` when `shopify.webhook_secret` is set; marketplace webhooks verify
  `X-Marketplace-Signature` when `marketplace.<name>.webhook_secret` is set; EasyPost tracker webhooks verify
  `X-Hmac-Signature` when `carrier.easypost.webhook_secret` is set. All use constant-time comparison and are
  idempotent on event id. Outbound WMS release documents are signed with `wms.webhook_secret`. Production deployments
  must set these secrets.
* Connector credentials (SendGrid, Twilio, Smarty, EasyPost, Amazon LWA) are read from environment variables only;
  `GET /api/v1/integrations/status` reports presence, never values. Customer notifications are rendered from static
  templates with typed slots filled by code, never from model output (GR-15); recipients are stored tokenised.
* Agent mandates are signed by the buyer side and verified against the agent's key; spend limits and scope are checked
  before capture; every agent order carries provenance and can produce a dispute evidence pack.

### AI safety (GR-01 to GR-17)

* **Untrusted text is data.** Customer notes, agent instruction text, webhook payloads and marketplace fields are stored
  as data, screened by the `injection_screen` decision, and only ever placed in the typed `state` fields of a provider
  request, never in the instruction part of a prompt (GR-08). Flagged text holds the order for a person.
* **Structured output only (GR-04).** Every provider returns an enumerated answer: a Choice key from the declared
  criteria, a Score level within the rubric, or a Noul probability. LLM providers are forced into a tool/JSON schema
  that enumerates the options; anything outside it is discarded and the deterministic default applies.
* The L2 LLM layer is read-only: it may pick one of the enumerated options with the trace as context, never execute
  an action. T3 decisions never act; T2 acts only at or above 0.9 confidence; the low band never acts (GR-02).
* Every decision has a timeout budget, a fallback provider and a deterministic default (GR-07); kill switches exist per
  decision, per provider and per tenant (GR-06); a daily AI budget per tenant degrades to L0 when exceeded (GR-17).
* Spend guardrails: refund and appeasement velocity caps per tenant, actor and agent, with a circuit breaker (GR-05).
* Pre- and post-checks in CEL (GR-01), golden-set evaluation gates (GR-11), calibration drift monitoring (GR-12),
  recorded overrides (GR-13), lint for PII and protected attributes in prompts (GR-16).
* The copilot produces drafts only: it cannot publish a decision, change a threshold or move money (GR-14).

### Supply chain and build

* CI (`.github/workflows/ci.yml`) runs `gitleaks` for committed secrets, `trivy fs` over dependencies, IaC and
  secrets (SARIF upload, failing on fixable HIGH/CRITICAL), `trivy image` on the built container, `npm audit`
  (advisory) and publishes CycloneDX SBOMs for the backend (Maven profile `sbom`), the UI and the image; a weekly
  scheduled rescan catches new advisories. Dependencies are pinned through the Spring Boot BOM and explicit versions in
  `backend/pom.xml`.
* Images are built from the repository `Dockerfile` by CI only; no image is pushed from a developer machine.

### Transport

* The application serves plain HTTP on one port and expects TLS termination at an ingress or load balancer. Terminate
  TLS 1.2+ in front of it, forward `X-Forwarded-*` only from that trusted proxy (`oms.security.trust-forwarded-for`),
  and enable HSTS in production (`oms.security.hsts`). Database and Redis connections should use TLS where the platform
  offers it (`?sslmode=require`).

### Known gaps

Documented so adopters can compensate at the platform level (details in `backend/oms-platform/HARDENING.md` §6):

* No SCIM or just-in-time provisioning: OIDC users exist only as principals in their tokens, so `change-password`
  returns 403 for SSO accounts and per-user settings cannot be stored.
* No refresh tokens and no server-side revocation of local HS256 session tokens; logout is client-side. Rotate
  `OMS_JWT_SECRET` to invalidate every session.
* Login rate limiting is per instance (Caffeine + Bucket4j): behind N replicas the effective limit is N×. Alert on
  `auth.login_failed` bursts across instances, or swap `RateLimiter` for a Redis-backed bucket.
* API keys have no expiry (`expires_at` is a straightforward addition); revoke them explicitly.
* No MFA for local accounts; use the IdP's MFA through OIDC. No account lockout beyond the rate limiter, no password
  history or breach-list check.
* Field-level encryption covers PII tokenisation keys only; order payloads and ledger events rest in clear in
  PostgreSQL. Use storage-level encryption and restrict database access.
* `OMS_MASTER_KEY` rotation is an offline re-wrap with no dual-key window.

### Related documents

* `backend/oms-platform/HARDENING.md`: engineering notes for the hardening pass, full `oms.security.*` property table,
  identity-provider recipes.
* `docs/THREAT_MODEL.md`: STRIDE table over the main attack surfaces.
* `docs/COMPLIANCE.md`: SOC 2, PCI scope, CCPA/CPRA, data residency.
* `docs/DEPLOYMENT.md`: production checklist, secrets, OIDC, backups.
* `docs/RUNBOOK.md`: kill-switch drill, degraded mode, outbox recovery.
