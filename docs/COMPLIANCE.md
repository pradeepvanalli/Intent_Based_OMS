# Compliance mapping

How the design of Intent OMS supports common control frameworks. This is an engineering mapping to help an adopter's
compliance team scope an assessment; it is not a certification and it does not replace the operator's own policies,
procedures and evidence collection. Controls marked **platform** must be provided by the deployment (cloud provider,
Kubernetes, secret store, IdP).

## SOC 2 (Trust Services Criteria)

| Criteria | Design support | Evidence available | Platform / operator |
|---|---|---|---|
| CC6.1 Logical access | RBAC with the `<domain>.<action>` permission grammar; built-in least-privilege roles; OIDC SSO with role mapping; API keys per integration; agents authenticated by HMAC and bounded by registry limits | `GET /api/v1/auth/me`; `Roles.DEFAULT`; `security.roles` tenant key; audit rows for key issuance and agent changes | IdP policies (MFA), joiner/leaver process |
| CC6.2 / CC6.3 Provisioning and removal | Users come from the IdP; API keys revocable (`DELETE /auth/api-keys/{id}`); agents suspendable and secret-rotatable | `api_key.revoked_at`, `agent.*` ledger events | Periodic access review |
| CC6.6 Boundary protection | Only one HTTP port; TLS at ingress; security headers; CORS allow-list; login rate limiting | Headers on any response; `oms.security.*` properties | WAF, network policy |
| CC6.7 Data in transit | HTTPS at ingress; `sslmode=require` to PostgreSQL; signed webhooks both ways | Deployment manifests | Certificates |
| CC6.8 Malicious software | Minimal JRE image, non-root, dependency and image scanning in CI with SBOM | CI artefacts | Registry scanning, admission policy |
| CC7.1 / CC7.2 Monitoring | Prometheus metrics, OTLP traces, structured logs with correlation ids; business alerts to Slack/Teams/PagerDuty; outbox and webhook delivery logs | `/actuator/prometheus`, `alert` table, `webhook_delivery` | SIEM, on-call |
| CC7.3 / CC7.4 Incident response | Kill switches per decision/provider/tenant; circuit breaker on refund spend; emergency publish with retro review; runbook | `docs/RUNBOOK.md`; `decision.definition.emergency_published` events | IR plan |
| CC8.1 Change management | Decision and state-model changes go through draft → lint → impact → evaluation → approval (separation of duties) → rollout; all versioned, audited, reversible; code changes via PR with CI | `audit_log`, `decision.definition.*` and `statemodel.*` ledger events, Git history | Change calendar |
| PI1.x Processing integrity | Idempotent capture; exactly-once per order via row locks and idempotency keys; append-only ledger allows rebuild and replay of any order; golden-set gates and calibration monitoring for decisions | `GET /api/v1/simulations/rebuild/{orderId}`; `decision_record`; `GET /analytics/decisions/{name}` | |
| C1.x Confidentiality | Per-tenant isolation in every table and query; PII tokenised before model providers; per-tenant keys envelope-encrypted | Threat model; code review convention | Data classification |
| A1.x Availability | Stateless pods, HPA, PDB, graceful shutdown; degraded mode keeps capture/sourcing/shipping working without AI | Load test thresholds (`load/promise.js`) | Postgres HA, backups |

## PCI DSS: scope minimisation

Intent OMS is designed to stay **out of PCI scope for cardholder data**:

* No primary account number, expiry or CVV field exists in the canonical order model. Tenders carry a PSP token
  (`tenders[].token`, `TenderType` card/paypal/gift_card/net_terms/agent_token) issued by the storefront's PSP
  integration; the `PaymentGateway` SPI authorises, captures and refunds by token and PSP reference only
  (`AuthRequest.tenderToken` "never a PAN").
* The Stripe implementation uses PaymentIntents with a `payment_method` token and Stripe idempotency keys; the mock
  gateway stores nothing.
* Raw gateway responses kept for disputes are "selected fields" (`AuthResult.raw`), never card data.
* Refund and appeasement spend are bounded by velocity caps and approvals, supporting requirement 7/8-style least
  privilege for money movement.

Operator responsibilities: keep card entry on the PSP's hosted fields or SDK, confirm the storefront never posts PANs
into `notes` or `extensions` (the capture pipeline does not scan free text for PANs), and treat the PSP key as a
secret. If a tenant's integration were to send PANs in free text, the ledger would retain them; the extension JSON
Schema (`PUT /api/v1/extension-schema`) can reject unexpected fields.

## CCPA / CPRA (and GDPR-style rights)

| Right | Mechanism |
|---|---|
| Know / access | `GET /api/v1/orders?customerRef=` and `GET /api/v1/orders/{id}` return the customer's orders; `GET /api/v1/admin/ledger/order/{id}` the full event history; `GET /api/v1/returns?customerRef=` returns |
| Delete | Raw PII lives only in the order tables (`orders`, `order_address`, `order_note`, customer fields). Deletion removes those rows per request. Ledger and decision traces hold **tokens**, not values: rotating the tenant PII key (`Tokenizer.shred(tenant)`) makes every historical token unlinkable (crypto-shredding), which satisfies deletion without breaking the append-only evidence chain. Per-customer shredding is a documented gap: today the key is per tenant, so a single customer's tokens become unlinkable only when the tenant key rotates |
| Correct | `POST /api/v1/orders/{id}/modifications` (address) and notes; corrections are events, the prior value stays in the ledger as a token |
| Opt out of sale/sharing | Not applicable: no data is sold; model providers receive tokenised, minimal `state.include` fields under the operator's provider agreement |
| Limit use of sensitive PI | Lint blocks protected attributes in decision inputs (GR-16); `zip_proxy` warns on demographic proxies |
| Automated decision-making transparency | Every decision has a stored trace with inputs, options, probabilities and rules; `why?audience=customer` produces a customer-safe explanation; humans can override with a recorded reason (GR-13); T2/T3 money-affecting decisions require or recommend human review |

Retention: `retention.years` (default 7) applies to the ledger and audit; order-table deletion is immediate on request.
Document your retention schedule and run deletions under the DSAR process.

## Data residency

* All persistent data is in one PostgreSQL database (plus a volatile Redis cache). Deploy the database and the pods in
  the region required; there is no cross-region replication in the application.
* Model providers receive tokenised `state` fields only. Choose providers and regions in your provider agreement;
  `TYPESAFE_BASE_URL` and `OPENAI_BASE_URL` allow regional or private endpoints; `JEV_ENABLED=false` and provider kill
  switches stop egress entirely (degraded mode).
* Outbound webhooks and Kafka are configured per tenant / deployment; restrict egress to approved destinations.
* Multi-tenant deployments isolate tenants logically. Tenants with different residency requirements should run as
  separate deployments.

## Evidence shortcuts

| Question an auditor asks | Where to look |
|---|---|
| Who changed this policy and who approved it? | `GET /api/v1/admin/audit?objectType=decision_definition&objectId=<name>`; `GET /api/v1/decisions/<name>/versions/<v>` (`approvals`, `rollout`) |
| Why did the system refund this customer? | `GET /api/v1/decision-records/{id}/why` and the `refund.*` ledger events |
| Can anyone modify history? | `reject_mutation()` trigger on `event`, `audit_log`, decision traces |
| What did the model see? | The trace's `inputs` (tokenised) and `tokenisedFields` |
| How do you stop the AI if it misbehaves? | `docs/RUNBOOK.md` degraded mode; monthly kill-switch drill |
| How do agents prove delegation? | `GET /api/v1/orders/{id}/evidence-pack` |
