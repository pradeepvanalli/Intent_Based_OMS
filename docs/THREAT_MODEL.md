# Threat model

A STRIDE-style view of the main attack surfaces of Intent OMS. For each surface the table lists the threat, the
control in the code or the deployment, and what remains for the operator. Requirement ids refer to the PRD
(`docs/requirements.txt`); coverage is in `docs/TRACEABILITY.md`. The security standards the project commits to are in
`SECURITY.md`; compliance mapping is in `docs/COMPLIANCE.md`.

## Assets

Orders and customer PII; payment tokens (never PANs); tenant configuration (decisions, rules, thresholds); the event
ledger and audit log (evidence); agent secrets and mandates; AI provider keys and spend; availability of capture and
promise (99.99% target).

## Trust boundaries

1. Internet → ingress/TLS → application (REST `/api/v1`, public `/api/v1/promise`, `/webhooks/**`, `/mcp`,
   `/ucp/**`, `/.well-known/ucp`, SPA assets).
2. Application → PostgreSQL / Redis (private network).
3. Application → AI providers (Jev, Anthropic, OpenAI-compatible) over the internet with API keys.
4. Application → tenant systems (outbound webhooks, PSP, tax, Slack/Teams/PagerDuty).
5. Tenant ↔ tenant inside one database (logical isolation).

## REST API (`/api/v1`)

| STRIDE | Threat | Control | Residual / operator action |
|---|---|---|---|
| Spoofing | Forged or stolen session or API key | HS256 JWTs with a ≥ 32-byte secret (refused at default in prod), 8 h TTL; OIDC tokens validated against JWKS with issuer, audience, exp/nbf; API keys stored as SHA-256, format `oms_<id>_<secret>` | No refresh tokens or server-side revocation: rotate `OMS_JWT_SECRET` to invalidate sessions; API keys do not expire, revoke explicitly; use OIDC (with the IdP's MFA) for people |
| Spoofing | Credential stuffing on `/auth/login` | bcrypt hashes; constant-time path for unknown users; per-IP and per-tenant/user token-bucket limit (10/min → `429 rate_limited`); `auth.login_failed` audit rows with client IP | The limiter is per instance (N replicas → N×): alert on aggregated failures or use a Redis-backed limiter; put a WAF in front; disable local logins when OIDC is on |
| Tampering | Tenant id in body used to cross tenants | Tenant is taken from the principal; bodies never carry a trusted tenant; every query filters `tenant_id` | Review new queries in PRs for the tenant filter |
| Tampering | Changing a decision threshold or publishing without review | Studio enforces status transitions, submitter ≠ approver, two approvers for T2/T3, evaluation and lint gates; only humans may edit (GR-14); emergency path audited with retro review | Alert on `decision.definition.emergency_published` |
| Repudiation | "I did not approve that" | Append-only audit log and ledger with actor on every event; approvals stored with actor and time | Export audit regularly |
| Information disclosure | Problem details leaking internals | RFC 9457 bodies carry codes and validation fields only; stack traces are logged, not returned | |
| Information disclosure | Over-broad roles | Permission grammar `<domain>.<action>`; built-in roles are least-privilege; `@RequiresPermission` on every controller | Map IdP groups to the narrowest OMS role |
| Denial of service | Expensive searches, bulk operations | Pagination caps; bulk operations throttled (`perSecond`) with dry run; sweeps batched | Rate limit at the ingress |
| Elevation | Agent or decision principal editing configuration | `requireHuman()` in the studio rejects AGENT and DECISION principals; agents get permissions from registry limits only | |

## Inbound webhooks (`/webhooks/wms|carrier|inventory|shopify|marketplace/{name}|easypost`)

| STRIDE | Threat | Control | Residual |
|---|---|---|---|
| Spoofing | Fake "shipped" or "delivered" events, fake marketplace orders | `X-Webhook-Signature` HMAC-SHA256 over the raw body (hex or base64), constant-time compare, required once `webhooks.inbound_secret` is set; Shopify `X-Shopify-Hmac-Sha256`, marketplace `X-Marketplace-Signature` (`marketplace.<name>.webhook_secret`) and EasyPost `X-Hmac-Signature` (`carrier.easypost.webhook_secret`) likewise | **Set the secrets in production**; the keys are optional only for local demos. Marketplace orders are captured with `net_terms` tenders, so a forged one creates a release, not a charge: still a reason to set the secret |
| Tampering | Replay of a valid event | Idempotent on `eventId` (`WebhookIdempotency`); workflow signals de-duplicated by idempotency key | Include a timestamp in your event ids |
| Repudiation | Disputed delivery | Carrier events are ledger events with payload and actor `system:carrier-webhook`; evidence pack includes delivery proof | |
| Information disclosure | Error bodies revealing order existence | Unknown order → generic validation problem | |
| Denial of service | Flooding | Bodies are parsed once; signature check before any work; idempotency short-circuits | Ingress rate limits per source IP |
| Elevation | Webhook principal doing more than fulfilment | Runs under an integration principal scoped to the tenant; Shopify principal has `order.create`/`order.read` only | WMS/carrier principal is broad (`*`) within the tenant: restrict at the network layer and keep the secret per tenant |

## Agents: MCP and UCP

| STRIDE | Threat | Control | Residual |
|---|---|---|---|
| Spoofing | Impersonating an agent | HMAC-SHA256 over `timestamp\nMETHOD\npath\nsha256(body)` with a per-agent secret, ±300 s window; or API key; suspended agents rejected; rate limit per agent (`ratePerMinute`) | Rotate secrets (`POST /agents/{id}/rotate-secret`); monitor `oms_agent_auth{outcome=rejected}` |
| Tampering | Prompt injection in instruction text or return reason | Text stored as data, screened by `injection_screen`, never placed in prompt instructions (GR-08); flagged orders held (`agent_verification`) and reviewed | Tune `agent.injection_threshold` |
| Tampering | Spending beyond the buyer's delegation | Mandate scope, spend limit and expiry checked before capture; mandate consumed on use; `maxOrderValueUsd`, `maxRefundUsd`; `confirmActions` force human confirmation; T3 `agent_mandate_match` never acts | |
| Repudiation | Chargeback: "I never authorised that" | Evidence pack: identity, signed mandate (`signatureValid`), instruction text, decision trail, delivery proof, ledger digest (FR-AGT-09) | Keep the ledger for the dispute window |
| Information disclosure | Agent reading others' orders | Tier-0 agents see only orders their provenance proves they placed; `decision_why` returns the customer-safe trace to external agents; UCP views are customer-safe | |
| Denial of service | Oversized bodies, tool spam | `oms.agent.max-body-bytes` (1 MiB), rate limits, tool allow-list per agent | |
| Elevation | Agent calling tools outside its allow-list | `tools/list` filtered and `tools/call` checked against `allowedActions` and permissions; same permission model as people (FR-AGT-06) | |

## LLM and Jev providers

| STRIDE | Threat | Control | Residual |
|---|---|---|---|
| Tampering | Model returns an action outside the policy | Only enumerated answers are accepted: choice keys, rubric levels, probabilities (GR-04); LLM forced through a tool/JSON schema; invalid answers discarded → default; post-checks (GR-01) | |
| Tampering | Model talked into acting by injected text | Untrusted text only in typed `state`; L2 is read-only and limited to options; escape option forces ESCALATE (GR-03, GR-08) | |
| Information disclosure | PII sent to a provider | Per-tenant HMAC tokenisation of name/email/phone/address in `state` (GR-09); lint `pii_in_prompt` and `protected_attribute` | Review `state.include` in every decision PR |
| Denial of service | Provider outage or latency | Timeouts (`timeoutMs`), one retry on 429/529, fallback provider, deterministic default (GR-07); kill switches per provider/decision/tenant (GR-06) | Monthly kill-switch drill |
| Denial of service (financial) | Runaway spend | `ai.budget_usd_per_day` degrades to L0 when exceeded (GR-17); cost recorded per decision; cache by input hash | Alert on `ai_cost_per_order` |
| Spoofing | Compromised provider key | Keys live in environment/secret store only; scope keys per deployment | Rotate keys; set provider spend caps at the provider |

## Database

| STRIDE | Threat | Control | Residual |
|---|---|---|---|
| Tampering | Editing the ledger or audit | `reject_mutation()` triggers on append-only tables; application role should not own the trigger function | Use a separate migration role from the runtime role; enable `pgaudit` |
| Information disclosure | Dump of PII | PII tokens keyed per tenant; `oms.security.master-key` envelope-encrypts tenant keys; agent secrets encrypted with `oms.agent.secret-key`; TLS to Postgres | Encrypt volumes; restrict network access |
| Information disclosure | Cross-tenant read via a missing filter | Convention enforced in review; `PostgresTestBase` binds a tenant so tests catch unscoped queries | Consider row-level security as defence in depth |
| Denial of service | Lock contention | Reservations lock per (sku, node); workflow steps lock one instance; `SKIP LOCKED` workers | Size `max_connections` to pods × pool |
| Repudiation | Deleted evidence | Append-only plus retention policy; exports for archive | |

## Operator UI (React SPA)

| STRIDE | Threat | Control | Residual |
|---|---|---|---|
| Spoofing | Session theft via XSS | Content Security Policy with hashes for inline scripts, `X-Content-Type-Options`, `Referrer-Policy`, Permissions-Policy headers; tokens in `localStorage` (no cookies, so no CSRF) | Consider short TTLs; the SPA logs out on 401 |
| Tampering | Clickjacking | `frame-ancestors 'none'` in the CSP | |
| Information disclosure | Verbose traces shown to the wrong audience | `why?audience=customer` strips risk data; permission checks on every endpoint the UI calls | |
| Elevation | Hidden UI actions still callable | Authorisation is server-side; the UI only hides what the principal lacks | |

## Outbound integrations

| STRIDE | Threat | Control | Residual |
|---|---|---|---|
| Spoofing | Forged events to a tenant's receiver or WMS | CloudEvents signed with per-subscription secret (`X-OMS-Signature`); UCP webhooks signed with the agent secret; WMS release documents signed with `wms.webhook_secret` (`X-Webhook-Signature`) | Receivers must verify (`docs/examples/webhooks/verify_signature.py`) |
| Tampering | Customer notification carrying model text or PII to the wrong person | Templates are static files; slots are filled from order facts by code (GR-15); recipient from the order record, never a model; attempts logged with tokenised recipients | Review templates under `notify/templates/` in PRs |
| Information disclosure | Events to an attacker-controlled URL (SSRF) | Subscriptions need `config.admin`; URLs are audited | Restrict egress to known hosts |
| Denial of service | Slow receivers | Timeouts, retries with backoff in the outbox, delivery log | Monitor `oms_outbox_failed_total` |

## Supply chain and build

Dependencies pinned through the Spring Boot BOM; CI scans dependencies and the container image and publishes an SBOM;
images build from the repository Dockerfile only; the runtime image is JRE-only on Alpine, runs as `oms`, and has no
shell tools beyond `wget` for the health check.

## Out of scope / assumptions

TLS termination, network segmentation, host hardening and secret-store operation belong to the platform. The
application assumes PostgreSQL is reachable only from the application network and that the ingress is the only path
to the pods when `trust-forwarded-for` is on.
