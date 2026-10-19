# Configuration reference

Intent OMS has three layers of configuration:

1. **Deploy-time settings**: environment variables bound to Spring properties in
   `backend/oms-app/src/main/resources/application.yml` and `@ConfigurationProperties` records (`oms.<module>.*`).
   They need a restart.
2. **Tenant configuration files** in `config/tenants/<tenant>/`: YAML/JSON loaded by the seeder for the demo tenant,
   or through the API by `scripts/new-tenant.sh`. Each file has a matching REST resource and is editable at runtime.
3. **Runtime tenant settings** (`tenant_config` table): dotted keys with JSON values, read through
   `TenantConfigStore` with a 5 s cache. `GET/PUT/DELETE /api/v1/admin/config[/{key}]`. The seeder writes a key only
   when it is absent, so runtime edits survive restarts.

## 1. Environment variables and properties

| Variable | Property | Default | Meaning |
|---|---|---|---|
| `OMS_DB_URL` | `spring.datasource.url` | `jdbc:postgresql://localhost:5440/oms` | PostgreSQL 16 JDBC URL |
| `OMS_DB_USER`, `OMS_DB_PASSWORD` | `spring.datasource.username/password` | `oms`/`oms` | Database credentials |
| `OMS_DB_POOL` | `spring.datasource.hikari.maximum-pool-size` | 32 | Connection pool size |
| `OMS_PORT` | `server.port` | 8080 | HTTP port (plain HTTP; TLS terminates upstream) |
| `OMS_JWT_SECRET` | `oms.security.jwt-secret` | dev placeholder | HS256 secret for session tokens; ≥ 32 chars; refused in production when left at the default |
| `OMS_JWT_SECRET_FILE` | `oms.security.jwt-secret-file` | empty | File whose trimmed content replaces the secret (secret mounts); unreadable file = startup failure |
| — | `oms.security.jwt-ttl` | 8h | Session token lifetime |
| `OMS_DEV_USERS` | `oms.security.dev-users` | demo users | `id:password:tenant:role1\|role2,...`; creates tenants and users on start; must be empty in production |
| `SPRING_PROFILES_ACTIVE=prod` | `application-prod.yml` | — | Production profile: no dev users, no demo seed, HSTS on, forwarded headers, actuator limited to `health,prometheus`, Swagger off unless `OMS_SWAGGER_ENABLED=true`; runs the fail-fast `ProductionReadinessCheck` |
| — | `oms.security.enforce-production` | false | Run the production readiness checks outside the `prod` profile |
| — | `oms.security.hsts` | on in prod | Emit `Strict-Transport-Security` |
| — | `oms.security.content-security-policy`, `swagger-content-security-policy`, `permissions-policy` | built-in | Security headers for the SPA and Swagger UI |
| — | `oms.security.trust-forwarded-for` | false | Take the client IP from `X-Forwarded-For` (only behind a trusted proxy) |
| — | `oms.security.login-rate-limit`, `login-rate-window` | 10 per 1m | Login attempts per IP and per user |
| — | `oms.security.password-min-length` | 12 | Minimum length for passwords set through the API |
| `OMS_MASTER_KEY` | `oms.security.master-key` | empty | AES-256-GCM key (base64 of 32 bytes or a passphrase, SHA-256 derived) that envelope-encrypts `tenant_key.key_material`; required in production |
| `OMS_CORS_ALLOWED_ORIGINS`, `OMS_CORS_ALLOW_CREDENTIALS` | `oms.security.cors.allowed-origins`, `allow-credentials` | `*`, false | Browser origins allowed to call the API (comma separated); `*` refused in production; credentials only with explicit origins |
| `OMS_OIDC_ISSUER_URI`, `OMS_OIDC_JWK_SET_URI`, `OMS_OIDC_AUDIENCE`, `OMS_OIDC_TENANT_CLAIM`, `OMS_OIDC_DEFAULT_TENANT`, `OMS_OIDC_ROLES_CLAIM` | `oms.security.oidc.issuer-uri`, `jwk-set-uri`, `audience`, `tenant-claim`, `default-tenant`, `roles-claim`; also `subject-claims`, `roles-claim-paths`, `role-mapping.*`, `clock-skew` | disabled | OIDC resource server; enabled when either URI is set. See `docs/DEPLOYMENT.md` §7 and `backend/oms-platform/HARDENING.md` |
| — | `oms.security.public-paths` | health, docs, login, promise, SPA assets | Paths served without authentication |
| `OPENROUTER_API_KEY` | feeds both `oms.decision.jev.api-key` and `oms.decision.llm.openai-api-key` | empty | One key for Jev (`typesafe/jev-1.13`) and the LLM (`anthropic/claude-sonnet-4.5`) through OpenRouter |
| `TYPESAFE_API_KEY`, `TYPESAFE_BASE_URL` | `oms.decision.jev.api-key`, `base-url` | empty | Direct Jev; base URL defaults to `https://api.typesafe.ai` (or OpenRouter when the key starts with `sk-or-`) |
| `JEV_MODEL` | `oms.decision.jev.model` | `jev-latest` direct, `typesafe/jev-1.13` via OpenRouter | Jev model id |
| `JEV_ENABLED` | `oms.decision.jev.enabled` | true | Register the Jev provider |
| `JEV_TIMEOUT_MS` | `oms.decision.jev.timeout-ms` | 800 | Per-call budget (GR-07); overridden by `timeoutMs` in a definition |
| `JEV_LATENCY_ALLOWANCE_MS` | `oms.decision.jev.latency-allowance-ms` | 1200 via OpenRouter, 0 direct | Extra budget for a routing hop |
| `LLM_PROVIDER` | `oms.decision.llm.provider` | `auto` | `auto` (Anthropic if its key exists, else OpenAI-compatible), `anthropic`, `openai` |
| `ANTHROPIC_API_KEY`, `ANTHROPIC_MODEL` | `oms.decision.llm.anthropic-api-key`, `anthropic-model` | empty, `claude-sonnet-5` | L2 via the Messages API with a forced `answer` tool |
| `OPENAI_API_KEY`, `OPENAI_BASE_URL`, `OPENAI_MODEL`/`LLM_MODEL` | `oms.decision.llm.openai-api-key`, `openai-base-url`, `openai-model` | empty | Any OpenAI-compatible endpoint with `json_schema` response format |
| `LLM_TIMEOUT_MS` | `oms.decision.llm.timeout-ms` | 8000 | L2 budget |
| `OMS_DEMO_SEED` | `oms.demo.seed` and `oms.agent.demo-expose-secrets` | true | Seed `config/tenants/<oms.demo.tenant>` on start, including sample orders; exposes demo agent secrets in `tenant_config` |
| `OMS_DEMO_TENANT` | `oms.demo.tenant` | `demo` | Tenant folder the seeder loads (relaxed binding; not in application.yml) |
| `OMS_CONFIG_DIR` | `oms.config-dir` | `../../config` | Directory containing `tenants/<tenant>/`; the resolver also tries `../config`, `config`, `/config` |
| — | `oms.outbox.enabled`, `poll-ms`, `batch-size`, `max-attempts` | true, 250, 200, 12 | Outbox relay |
| `OMS_KAFKA_ENABLED`, `KAFKA_BOOTSTRAP` | `oms.kafka.enabled`, `spring.kafka.bootstrap-servers` | false | Publish ledger events to Kafka topics `oms.events.*` |
| — | `oms.workflow.enabled`, `workers`, `poll-ms`, `max-step-attempts`, `step-backoff-initial`, `step-backoff-max` | true, 8, 200, 20, 1s, 5m | Workflow engine pollers |
| — | `oms.statemachine.sweep-ms`, `sweep-batch`, `cache-size` | module defaults | SLA timer sweeper and model cache |
| — | `oms.inventory.network-cache-ttl`, `expiry-sweep-enabled` | module defaults | Network ATP cache; reservation expiry sweep |
| `OMS_REDIS_URL` | `oms.sourcing.promise.redis-url` | empty | Shared promise cache; Caffeine in-process otherwise (`cache-ttl`, `cache-max-size`) |
| `OMS_FINANCE_PSP` | `oms.finance.psp` | `mock` | `mock` or `stripe` (`oms.finance.stripe.api-key`, `base-url`, `timeout`) |
| — | `oms.finance.tax` | `flat` | `flat` (rates from `tax.rates`) or `avalara` skeleton |
| — | `oms.finance.mock.auth-ttl` | 7d | Mock authorisation hold lifetime |
| — | `oms.monitoring.enabled`, `risk-sweep-ms`, `stuck-sweep-ms`, `sweep-batch`, `pipeline-cache-ms` | module defaults | Control-tower sweeps |
| — | `oms.orchestration.fulfilment-timeout`, `delivery-timeout`, `pickup-window` | P5D, P14D, P5D | Defaults for the tenant keys of the same name |
| — | `oms.agent.signature-window`, `max-body-bytes`, `secret-key`, `webhook-timeout`, `server-version` | PT300S, 1 MiB, empty, PT5S, 0.1.0 | Agent HMAC replay window, body cap, secret encryption key, UCP webhook timeout |
| — | `oms.simulation.page-size`, `commit-every`, `default-sample-limit`, `max-sample-limit`, `per-order-sample`, `ledger-event-limit` | module defaults | Batch simulation sizing |
| — | `oms.analytics.*` | module defaults | KPI cache, roll-up cron, export batch |
| — (relaxed binding `OMS_COPILOT_*`) | `oms.copilot.enabled`, `api-key`, `base-url`, `model`, `timeout-ms`, `max-retries`, `max-output-tokens`, `templates-dir`, `starter-dir` | enabled; key from `OPENROUTER_API_KEY` then `OPENAI_API_KEY`; base URL by key prefix; `anthropic/claude-sonnet-4.5` (OpenRouter) / `gpt-5` (OpenAI); `config/copilot`; `config/tenants/starter` | Copilot LLM for configuration drafts, ops answers, explanations, CS drafts and digests; spend counts against `ai.budget_usd_per_day`. Prompt templates are the `*.md` files in `templates-dir` |
| `OMS_GRAPHIQL` | `oms.integrations.graphiql` | false | Serve the GraphiQL explorer at `/graphiql` (development only); `POST /graphql` is always on |
| `OMS_NOTIFY_ENABLED` | `oms.integrations.notify.enabled` | false | Install the production customer notifier (SendGrid e-mail, Twilio SMS) in place of the logging one; `oms.integrations.notify.timeout` (PT5S) |
| `SENDGRID_API_KEY`, `SENDGRID_FROM_EMAIL`, `SENDGRID_FROM_NAME` | `oms.integrations.notify.sendgrid.api-key`, `from-email`, `from-name` (`base-url`) | empty, `orders@example.com`, `Intent OMS` | SendGrid v3 Mail Send; without a key e-mail attempts are logged `skipped_no_provider` |
| `TWILIO_ACCOUNT_SID`, `TWILIO_AUTH_TOKEN`, `TWILIO_FROM` | `oms.integrations.notify.twilio.account-sid`, `auth-token`, `from` (`base-url`) | empty | Twilio Programmable Messaging; all three required for SMS |
| `OMS_ADDRESS_PROVIDER` | `oms.integrations.address.provider` | `mock` | `mock` (deterministic `MockAddressVerifier`) or `smarty` |
| `SMARTY_AUTH_ID`, `SMARTY_AUTH_TOKEN` | `oms.integrations.address.smarty.auth-id`, `auth-token` (`base-url`, `timeout` PT1.5S) | empty | Smarty US Street API; errors yield `unverified`, never block capture |
| `OMS_CARRIER_PROVIDER` | `oms.integrations.carrier.provider` | `mock` | `mock` or `easypost` (rates, labels, tracking) |
| `EASYPOST_API_KEY` | `oms.integrations.carrier.easypost.api-key` (`base-url`, `timeout` PT10S) | empty | EasyPost; labels land in `carrier_label` (`GET /api/v1/shipments/{id}/label`) |
| `OMS_WMS_PROVIDER` | `oms.integrations.wms.provider` | `mock` | `mock` or `http` (`HttpWmsConnector`: `oms.integrations.wms.http.timeout` PT5S, `retries` 3, `backoff` PT0.2S); endpoints and secret are tenant keys `wms.*` |
| `AMAZON_SPAPI_ENABLED`, `AMAZON_SPAPI_CLIENT_ID`, `AMAZON_SPAPI_CLIENT_SECRET`, `AMAZON_SPAPI_REFRESH_TOKEN`, `AMAZON_SPAPI_MARKETPLACE_IDS` | `oms.integrations.marketplace.amazon.enabled`, `client-id`, `client-secret`, `refresh-token`, `marketplace-ids`; also `tenant` (demo), `base-url` (NA), `lwa-url`, `lookback` (PT24H), `poll-interval` (PT5M), `timeout` | false, empty, `ATVPDKIKX0DER` | Amazon SP-API order poller; polls `Unshipped,PartiallyShipped` orders after the stored watermark into the configured tenant |
| `OTEL_EXPORTER_OTLP_ENDPOINT`, `OTEL_SAMPLING` | `management.otlp.tracing.endpoint`, `management.tracing.sampling.probability` | localhost:4318, 0.1 | Tracing |
| `JAVA_OPTS` | — | `-XX:MaxRAMPercentage=75 -XX:+UseZGC -XX:+ZGenerational` (container) | JVM flags |

Any `oms.*` property can also be set through Spring's relaxed binding (`OMS_SECURITY_OIDC_ISSUER_URI` →
`oms.security.oidc.issuer-uri`), even when `application.yml` does not list a placeholder for it.

## 2. Tenant configuration files

`config/tenants/<tenant>/` (see `config/tenants/starter/README.md` for a guided tour). Each file maps to a REST
resource; the demo seeder loads them all, `scripts/new-tenant.sh` loads them through the API.

| File | Loader / API | Contents |
|---|---|---|
| `tenant-config.yaml` | `PUT /api/v1/admin/config/{key}` | Runtime keys (section 3). Written only when absent |
| `order-types.yaml` | `PUT /api/v1/order-types/{name}` | `orderTypes[]{name, description, headerStateModel, lineStateModel, workflowType, decisions[], channelDefaults{channel: policy}, returnWindowDays, sourcing{nodeTypes, capabilities, maxDistanceKm, allowBackorder, includeInbound}}`. The `sourcing:` blocks are also written to `tenant_config` key `sourcing.order_type_constraints` |
| `state-models/*.yaml` | `POST /api/v1/state-models/publish {yaml, reason}` | Statecharts; format in `docs/STATE_MODELS_GUIDE.md` |
| `decisions/*.yaml` | `POST /api/v1/decisions/{name}/drafts {yaml}` → publish | Decision definitions; format in `docs/DECISIONS_GUIDE.md` |
| `golden-sets/*.json` | `PUT /api/v1/golden-sets/{name}` with `[{caseId, input, expected, notes}]` | Labelled cases: `{goldenSet, cases[{caseId, input, expected{value}}]}` |
| `rules/compliance.yaml` | `PUT /api/v1/compliance/rules {yaml}` | L0 compliance rules: `rules[]{id, name, scope line\|order, when (CEL), action block\|hold\|require_signature, reason_code, severity, message, priority}` over roots `line`, `order`, `tenant` |
| `catalog.yaml` | `PUT /api/v1/catalog/{sku}` with `{sku, name, attributes}` | `products[]{sku, name, price, cogs, weightKg, hazmat, hazmatClass, ageRestricted, minAge, category, lithium, exportControlled, oversized, finalSale, digital, restrictedStates[], hsCode}` |
| `nodes.yaml` | `PUT /api/v1/nodes/{id}` with the node entry | `nodes[]{id, name, type dc\|store\|vendor\|3pl, lat, lon, country, state, zip, capabilities[ship,bopis,sfs,hazmat,same_day], dailyCapacity, cutoff "HH:mm", timezone, health{backlog, pick_rate, reject_rate, staffing}, costProfile{labour_per_unit, packaging, handling}, landedCost{originCountry}}` |
| `shipping-rates.yaml` | `PUT /api/v1/shipping-rates` with the whole document | `services[]{code, zones[]{maxKm, transitDays, rates{weightKg: usd}}}`, `splitPenaltyUsd`, `onTimeByTransitDays{days: probability}` |
| `exception-taxonomy.yaml` | seeder only (`exceptionTypes`); `PUT /api/v1/queues/{name}` for `queues` | `exceptionTypes[]{type, severity, queue, playbook, slaMinutes, description}`, `queues[]{name, slaMinutes, escalateTo, skills[]}` |
| `playbooks.yaml` | `PUT /api/v1/playbooks/{name} {description, steps}`; `spendLimits` seeder only | `playbooks[]{name, description, steps[]{action, minConfidence?, requiresDecision?, when? (CEL), onFailure?, ...action params}}`, `spendLimits{reship_usd, refund_usd, appeasement_usd}` |
| `sla.yaml` | `PUT /api/v1/sla-definitions {orderType, entity, state, maxAge, onBreach, expectedNextEventWithin}` | `slas[]{orderType or "*", entity, state, maxAge (ISO-8601), onBreach playbook.<name>\|decision.<name>}`, `expectedNextEventWithin{state: duration}` |
| `agents/agents.yaml` | `POST /api/v1/agents` | `agents[]{id, owner, protocol ucp\|acp\|mcp, trustTier 0–3, allowedActions[], maxOrderValueUsd, maxRefundUsd, ratePerMinute, confirmActions[], callbackUrl?}`; secrets are generated and shown once |

Playbook actions with handlers: built-in `notify`, `create_task`, `hold`, `classify`, `log`; orchestration adds
`resource`, `repromise`, `contact_customer`, `reship`, `refund`, `cancel`, `reauthorize`, `retry_workflow`
(`GET /api/v1/playbooks/-/actions` lists what is registered). A step with no handler is recorded `skipped`.

## 3. Runtime tenant keys (`tenant_config`)

Values are JSON. Strings written through the API are plain JSON strings. Defaults apply when the key is absent.

### Sourcing and promise
| Key | Default | Meaning |
|---|---|---|
| `sourcing.margin_floor` | 0.12 | Contribution-margin ratio below which a plan needs a `margin_floor_approvals` task (GR-01, FR-SRC-04). Exposed to CEL as `tenant.margin_floor` |
| `sourcing.max_split_packages` | 3 | Maximum packages for split policies |
| `sourcing.split_penalty_usd` | rate card `splitPenaltyUsd` | Cost added per extra package |
| `sourcing.candidate_limit` | 100 | Candidate nodes considered per order |
| `sourcing.channel_default_policy` | `{}` | `{channel: ship_complete\|ship_as_available\|earliest_partial\|pickup_consolidated}` when intent is unclear |
| `sourcing.weights` | `{cost 0.4, time 0.25, soft 0.25, split 0.1}` | Composite plan-score weights |
| `sourcing.business_days_only` | false | ETAs skip weekends |
| `sourcing.pickup_radius_km` | 50 | Offer a BOPIS store within this radius as an alternative |
| `sourcing.backorder_lead_days` | 14 | Promise offset when nothing is in stock |
| `sourcing.order_type_constraints` | from `order-types.yaml` | `{orderType: {nodeTypes, capabilities, maxDistanceKm, allowBackorder, includeInbound}}` |
| `sourcing.reject_penalty` | 0.3 | Penalty added to node+SKU on a store reject or timeout |

### Inventory
| Key | Default | Meaning |
|---|---|---|
| `inventory.include_inbound` | false | Count `po`/`asn`/`in_transit` supply in ATP |
| `inventory.channel_buffer` | 0 | Fraction of network ATP withheld from channel feeds |
| `inventory.drift_tolerance` | 0 | Units of difference above which reconciliation reports drift |

### Capture and customer service
| Key | Default | Meaning |
|---|---|---|
| `capture.validation_steps` | `[schema, item_price_sanity, compliance, address, payment_status, agent_provenance]` | Ordered validation pipeline |
| `capture.allow_unknown_sku` | false | Accept lines not in the catalogue |
| `capture.price_tolerance` | 0.25 | Allowed deviation from catalogue price before a warning |
| `cs.carrier_intercept_enabled` | false | Allow address change after shipment as a carrier intercept |
| `cs.carrier_intercept_fee_usd` | — | Fee for an intercept |
| `shopify.webhook_secret` | unset | When set, `/webhooks/shopify` requires a valid `X-Shopify-Hmac-Sha256` |
| `appeasement.role_limits` | `{}` | `{role: usd}` ceilings used by the `appeasement` decision's L0 rule |

### Finance, tax and compliance
| Key | Default | Meaning |
|---|---|---|
| `finance.payment_fee_rate` | 0.029 | Variable payment fee for P&L |
| `finance.payment_fee_fixed` | 0.30 | Fixed fee per order |
| `finance.returns_reserve_rate` | 0.05 | Returns reserve as a fraction of net revenue |
| `tax.rates` | `{}` | `{state: rate}` for the flat-rate engine |
| `tax.default_rate` | 0 | Rate when the state has no entry |
| `tax.shipping_taxable` | false | Tax shipping charges |
| `compliance.restricted_zips` | `[]` | ZIPs or prefixes the tenant will not ship to (used by rule `restricted_zip`) |
| `compliance.restricted_states` | `{}` | `{category: [states]}` merged into category rules |
| `compliance.rules` | `[]` | Extra rules in the same shape as `compliance.yaml`, merged with the table |

### Guardrails and AI
| Key | Default | Meaning |
|---|---|---|
| `guardrail.refund_velocity_usd_per_hour` | — | Tenant-wide refund cap per rolling hour (GR-05) |
| `guardrail.appeasement_velocity_usd_per_hour` | — | Tenant-wide appeasement cap |
| `guardrail.refund_velocity_usd_per_hour_per_actor`, `..._per_agent` | — | Per-actor and per-agent refund caps |
| `guardrail.appeasement_velocity_usd_per_hour_per_actor`, `..._per_agent` | — | Per-actor and per-agent appeasement caps |
| `guardrail.breaker_open_until` | — | ISO instant; set by the circuit breaker; refunds route to `refund_approvals` while open |
| `ai.budget_usd_per_day` | — | Daily AI spend cap; L1/L2 degrade to L0 when exceeded (GR-17) |
| `ai.kill_switch` | false | Disable all L1/L2 for the tenant (GR-06) |
| `decision.kill_switch.<name>` | false | Disable one decision (falls to its default) |
| `provider.kill_switch.<name>` | false | Disable one provider (`jev`, `llm.anthropic`, `llm.openai`, `heuristic`) |
| `agent.injection_threshold` | 0.7 | Noul probability at or above which text counts as injected (GR-08) |
| `agent.default_max_order_value`, `agent.default_max_refund` | — | Defaults for registrations without limits |

### Agentic Control Tower (oms-orchestration autonomy)
| Key | Default | Meaning |
|---|---|---|
| `autonomy.enabled` | false | Master switch for autonomous execution. Ships off everywhere; the demo tenant turns it on |
| `autonomy.kill_switch` | false | Independent emergency stop (meant to be flipped instantly without touching the rest of the config); blocks autonomous execution even when `autonomy.enabled` is true |
| `autonomy.max_actions_per_day` | 10 | Maximum autonomous actions this tenant may execute per UTC day |
| `autonomy.max_usd_impact_per_action` | 100.00 | Maximum estimated USD impact of a single autonomous action (merchandise total for `re_source`/`re_promise`/`retry_payment`; 0 for `notify_customer`/`release_hold`) |
| `autonomy.allowed_actions` | `[re_source, re_promise, retry_payment, release_hold, notify_customer]` | Allow-list of action types the tenant permits autonomously; `refund` and `cancel` can never be added, regardless of this list |
| `autonomy.min_confidence_override` | unset | Optional confidence floor a tenant can raise above the decision's own ACT threshold; never lowers it |
| `autonomy.stuck_after_minutes` | 180 | Minutes an open order may sit with no further progress before the scanner flags it stuck |
| `autonomy.risk_score_threshold` | 3 | SLA breach-risk score (0-4) at or above which an open order is a scan candidate |
| `autonomy.margin_approval_stale_minutes` | 120 | Minutes a margin-floor-approval task may sit open before the scanner flags its order |
| `autonomy.scan_batch` | 200 | Maximum open orders / tasks / exceptions examined per tenant per scan |

### Returns
| Key | Default | Meaning |
|---|---|---|
| `returns.window_days` | 30 | Return window when the order type sets none; `returns.window_days.<orderType>` overrides |
| `returns.allow_in_transit` | false | Allow returns of quantity still shipped |
| `returns.restocking_fee_pct` | 0 | Restocking fee fraction; `returns.restocking_fee_conditions` (default `[damaged]`) |
| `returns.refundable_conditions` | `[new, like_new, damaged]` | Conditions refundable after inspection |
| `returns.refund_shipping` | false | Refund the shipping share |
| `returns.refund_requires_approval_above_usd` | 500 | Refunds above this always go to `refund_approvals` |
| `returns.return_shipping_cost_usd`, `returns.return_handling_cost_usd`, `returns.recovery_rate` | 8.5, 2.0, 0.4 | Keep-it economics |
| `returns.keep_it_enabled` | true | Master switch for keep-it offers |
| `returns.flagged_customers` | `[]` | Customer refs that surface as `flagged_customer` in `customer.history.flags` |
| `returns.serial_returner_threshold`, `returns.not_received_claims_threshold` | 6, 2 | Thresholds for history tags |
| `returns.reason_taxonomy` | from `return_reason` options | Reason codes accepted on returns |

### Orchestration, monitoring, platform
| Key | Default | Meaning |
|---|---|---|
| `orchestration.fulfilment_timeout` | P5D | Time in `await_fulfilment` before a `fulfilment_timeout` exception |
| `orchestration.delivery_timeout` | P14D | Time waiting for a delivery scan |
| `orchestration.pickup_window` | P5D | BOPIS pickup window |
| `tenant.timezone` | UTC | IANA zone of the tenant's clock. The one day-part definition (night 00-06, morning 06-12, afternoon 12-18, evening 18-24) for learned node reliability: the learner files outcomes and the sourcing decision reads facts on this clock (FS-02) |
| `learning.fraud.chargeback_window_days` | 120 | Days a card payment can be charged back. Fraud learners sample `payment_risk` / `address_risk` outcomes only from decisions at least this old, frauds and goods alike (FD-03) |
| `learning.fraud.outcome_window_days` | 30 | The same observation window for `rto_risk` and `return_risk` (a refused parcel or a return grading arrives within it) |
| `webhooks.inbound_secret` | unset | When set, WMS/carrier/inventory webhooks require `X-Webhook-Signature` |
| `alerts.slack_webhook`, `alerts.pagerduty_routing_key`, `alerts.email` | unset | Alert channels |
| `alerts.dedupe_minutes` | 15 | Alert de-duplication window |
| `retention.years` | 7 | Ledger and audit retention |
| `security.roles` | built-in | `{role: [permissions]}` extensions to the built-in role map |
| `agents.demo_secrets` | — | Written by the agent loader only when `oms.agent.demo-expose-secrets=true`; never in production |

### Integrations (oms-integrations)
| Key | Default | Meaning |
|---|---|---|
| `wms.<nodeId>.release_url`, `wms.release_url` | unset | Where the HTTP WMS connector posts release documents (per node, then tenant-wide fallback) |
| `wms.<nodeId>.cancel_url`, `wms.cancel_url` | release URL | Where cancel documents go |
| `wms.webhook_secret` | unset | Secret the outbound release documents are signed with (`X-Webhook-Signature: sha256=<hex>`) |
| `marketplace.<name>.webhook_secret` | unset | When set, `POST /webhooks/marketplace/<name>` requires `X-Marketplace-Signature` |
| `marketplace.<name>.order_type` | `standard_ship` | Order type for that marketplace's orders |
| `marketplace.amazon.watermark` | — | ISO instant of the last successful SP-API poll; written by the poller |
| `notify.default_channel` | `email` | `email` or `sms` when the caller leaves the channel open |
| `notify.brand_name` | — | Rendered into the `{{brand}}` slot of every customer message template |
| `carrier.easypost.webhook_secret` | unset | When set, `POST /webhooks/easypost` requires `X-Hmac-Signature` |

## 4. Roles and permissions

Permissions follow `<domain>.<action>`; `*` and `<domain>.*` are wildcards. Built-in roles (`Roles.DEFAULT`):

| Role | Permissions |
|---|---|
| `admin`, `platform_engineer` | `*` |
| `strategy_lead` | order.read, decision.*, statemodel.*, analytics.read, simulation.run, task.read, task.work, exception.read, config.admin, margin.approve, order.bulk, inventory.read |
| `ops_analyst` | order.read, order.modify, order.hold, order.release, order.bulk, task.*, exception.*, inventory.read, decision.read, decision.override, analytics.read |
| `cs_rep` | order.read, order.modify, order.cancel, return.create, refund.issue, appeasement.issue, task.read, task.work, decision.read |
| `store_associate` | order.read, task.read, task.work |
| `risk_analyst` | order.read, return.decide, decision.read, decision.approve, decision.override, task.*, exception.read, agent.admin |
| `finance_controller` | order.read, refund.approve, margin.approve, analytics.read, decision.read, decision.approve, task.read, task.work |
| `implementer` | statemodel.*, decision.edit, decision.read, config.admin, order.read, simulation.run |
| `ai_governance` | decision.*, analytics.read, simulation.run, order.read |
| `agent_readonly` | order.read, promise.read, inventory.read |
| `agent_shopper` | order.read, order.create, order.modify, order.cancel, return.create, promise.read, inventory.read |
| `agent_ops` | order.read, order.modify, order.hold, order.release, task.read, task.work, exception.*, decision.read |

Full permission list: `order.read|create|modify|cancel|hold|release|bulk`, `return.create|decide`,
`refund.issue|approve`, `inventory.read|write`, `promise.read`, `decision.read|edit|approve|publish|override`,
`statemodel.edit|publish`, `task.read|work`, `exception.read|work`, `agent.admin`, `config.admin`, `analytics.read`,
`simulation.run`, `appeasement.issue`, `margin.approve`.
