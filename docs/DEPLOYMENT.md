# Deployment

Intent OMS is one stateless JVM service (`intent-oms.jar`, container `ghcr.io/pradeepvanalli/intent_based_oms`) over PostgreSQL
16, with Redis optional for the shared promise cache. Horizontal scale is more instances behind a load balancer;
Postgres row locks and `FOR UPDATE SKIP LOCKED` workers keep every instance stateless (ADR-001). This page covers the
ways to run it (Compose, Kubernetes with Helm or plain manifests, bare JVM), sizing, the database, secrets, OIDC,
observability, backups, upgrades and how releases and the container image are produced (section 12). With
`SPRING_PROFILES_ACTIVE=prod` the application loads `application-prod.yml` (no dev users, no demo seed, HSTS,
forwarded-header handling, actuator limited to `health,prometheus`, Swagger off) and `ProductionReadinessCheck`
refuses to start with insecure settings (section 11). The engineering notes behind the security settings are in
`backend/oms-platform/HARDENING.md`.

## 1. Docker Compose

`docker-compose.yml` defines `postgres` (5440 → 5432, tuned for 300 connections, logical WAL), `redis` and `oms`
(built from `Dockerfile` at the repository root, UI included).

```bash
scripts/try.sh                  # = the three lines below plus health wait, login table and browser (Windows: scripts/try.ps1)
cp .env.example .env            # fill OMS_JWT_SECRET and optional AI keys
docker compose up --build -d    # http://localhost:8080 (OMS_PORT=8090 to publish elsewhere)
docker compose logs -f oms
```

The compose file passes `OMS_DB_*`, `OMS_JWT_SECRET`, the AI keys (`OPENROUTER_API_KEY`, `TYPESAFE_API_KEY`,
`ANTHROPIC_API_KEY`, `OPENAI_API_KEY`), `OMS_REDIS_URL`, `OMS_DEMO_SEED=true` and
`OMS_DEMO_TENANTS=demo,retail,electronics,apparel`; the `oms` container runs read-only with a tmpfs `/tmp`. The image
build needs no local toolchain: `Dockerfile` (at the repository root) builds the UI in a Node 22 stage and the backend in a
Maven/Temurin 21 stage (BuildKit cache mounts for `~/.npm` and `~/.m2`), then copies the jar and `config/` onto a
non-root `eclipse-temurin:21-jre-alpine` runtime. `.dockerignore` keeps `target/`, `node_modules/`, `.env` and the
docs out of the context.

Without a checkout, `docker-compose.quickstart.yml` runs the **published image** (no `build:`) with the same
PostgreSQL/Redis services and demo settings:

```bash
curl -fsSL https://raw.githubusercontent.com/pradeepvanalli/Intent_Based_OMS/main/docker-compose.quickstart.yml -o docker-compose.yml
OMS_IMAGE=ghcr.io/pradeepvanalli/intent_based_oms:v0.1.0 docker compose up -d     # OMS_IMAGE defaults to :latest
```

For a production-shaped run use the standalone `docker-compose.prod.yml`:

```bash
export OMS_DB_URL='jdbc:postgresql://db.example.internal:5432/oms?sslmode=require' OMS_DB_USER=oms OMS_DB_PASSWORD='...'
export OMS_JWT_SECRET="$(openssl rand -base64 48)" OMS_MASTER_KEY="$(openssl rand -base64 32)"
export OMS_CORS_ALLOWED_ORIGINS=https://oms.example.com
docker compose -f docker-compose.prod.yml up -d
```

It sets `SPRING_PROFILES_ACTIVE=prod`, requires the secrets from the environment (compose fails fast on
`${VAR:?...}`), sets `OMS_DEV_USERS=""` and `OMS_DEMO_SEED=false`, passes the optional `OMS_OIDC_*` and AI variables,
and runs the container `read_only` with `no-new-privileges`, all capabilities dropped and a 3 GiB memory limit.
PostgreSQL is expected to be an external or managed service. Mount your `config/` directory at `/config` if you want
the seeder path (the image copies the repository's `config/` there and sets `OMS_CONFIG_DIR=/config`).

## 2. Kubernetes

### 2a. Helm chart (`deploy/helm/intent-oms`)

```bash
# evaluation (kind, minikube, k3d): demo seed of the four sample tenants + bundled single-node PostgreSQL
helm install oms deploy/helm/intent-oms --set demo.enabled=true --set postgresql.enabled=true
kubectl port-forward svc/oms-intent-oms 8080:80

# production: prod profile (default), external PostgreSQL, your secrets, ingress with TLS
helm upgrade --install oms deploy/helm/intent-oms -n oms --create-namespace -f my-values.yaml
```

What the chart renders: `Deployment` (non-root, read-only root filesystem, all capabilities dropped, tmpfs `/tmp`,
startup/readiness/liveness probes on the actuator groups, `preStop` sleep, zone spread), `Service`, optional
`Ingress`, `ConfigMap` with the non-secret environment (`config.*` keys are environment variable names from
`.env.example`), `Secret` (chart-managed with generated `OMS_JWT_SECRET`/`OMS_MASTER_KEY` kept across upgrades, or
`secrets.existingSecret` for external-secrets/sealed-secrets), optional `ConfigMap` mounted at `/config` for tenant
YAML (`configDir.files`), `HorizontalPodAutoscaler` (3–30 at 65% CPU), `PodDisruptionBudget` (`minAvailable: 2`),
`NetworkPolicy` (off by default), `ServiceAccount` without an auto-mounted token, and an optional PostgreSQL 16
`StatefulSet` for evaluation. `image.tag` defaults to `appVersion`; set `image.digest` to pin. `demo.enabled=true`
switches to the default Spring profile with `OMS_DEMO_SEED=true` and `OMS_DEMO_TENANTS`; never expose such a release.
`make helm-lint` lints the chart (through the `alpine/helm` image when `helm` is not installed). Values and their
meaning: `deploy/helm/intent-oms/values.yaml`; a worked `my-values.yaml`: `deploy/helm/intent-oms/README.md`.

### 2b. Plain manifests (`deploy/k8s/oms.yaml`)

`deploy/k8s/oms.yaml` is a reference manifest: a `Deployment` with three replicas spread across zones, rolling update
with zero unavailable, readiness on `/actuator/health/readiness` and liveness on `/actuator/health/liveness`,
`preStop` sleep and a 45 s grace period for graceful shutdown, Prometheus scrape annotations, requests of 1 CPU / 2 GiB
and a 3 GiB limit, a `Service`, an `HPA` (3–30 replicas at 65% CPU) and a `PodDisruptionBudget` (`minAvailable: 2`).
Secrets are read from `oms-db` (url, user, password), `oms-app` (jwtSecret) and `oms-ai` (typesafe, anthropic; optional).

```bash
kubectl create secret generic oms-db --from-literal=url='jdbc:postgresql://pg:5432/oms?sslmode=require' --from-literal=user=oms --from-literal=password='...'
kubectl create secret generic oms-app --from-literal=jwtSecret="$(openssl rand -base64 48)"
kubectl create secret generic oms-ai --from-literal=typesafe='' --from-literal=anthropic=''   # or your keys
kubectl apply -f deploy/k8s/oms.yaml
```

The manifest sets the `prod` profile, mounts the JWT secret as a file (`OMS_JWT_SECRET_FILE`), sets pod and container
`securityContext` (non-root, read-only root filesystem, no privilege escalation, all capabilities dropped,
`RuntimeDefault` seccomp) with a tmpfs `/tmp`, and ships a `NetworkPolicy`. Fill in for your environment:
`OMS_CORS_ALLOWED_ORIGINS=https://oms.example.com`, `OMS_MASTER_KEY` from a secret, the `OMS_OIDC_*` variables
(section 7), `OMS_REDIS_URL` when you run Redis, and a `ConfigMap` or image layer holding `config/tenants/<tenant>/`
at `OMS_CONFIG_DIR` if you want the seeder path (most production deployments load tenants through the API with
`scripts/new-tenant.sh` instead and leave the seeder off). Terminate TLS at the ingress; `application-prod.yml`
enables `server.forward-headers-strategy=framework` and `oms.security.trust-forwarded-for=true`, which is correct only
when the ingress overwrites `X-Forwarded-For`. Pin the image by digest (the sample uses `:latest`).

## 3. Bare JVM

```bash
cd backend && mvn -q -DskipTests clean install
OMS_CONFIG_DIR=$PWD/../config SPRING_PROFILES_ACTIVE=prod java $JAVA_OPTS -jar oms-app/target/intent-oms.jar
```

`JAVA_OPTS` default in the image: `-XX:MaxRAMPercentage=75 -XX:+UseZGC -XX:+ZGenerational`. The service listens on
`OMS_PORT` (8080) with HTTP/2 and compression enabled and shuts down gracefully.

## 4. Sizing

Targets (NFR-01/02): 1,000 orders/s sustained across the fleet, promise p95 < 300 ms, capture p95 < 200 ms. The k6
scenario `load/promise.js` encodes the thresholds (`make load`).

| Component | Starting point | Notes |
|---|---|---|
| App pod | 1 CPU / 2 GiB request, 3 GiB limit, 3+ replicas | Virtual threads; `OMS_DB_POOL` 32 per pod, so size Postgres `max_connections` ≥ pods × pool + headroom |
| Workflow workers | `oms.workflow.workers` 8 per pod | Each step holds a row lock briefly; raise with order volume, not with pod count alone |
| Outbox relay | `oms.outbox.batch-size` 200, `poll-ms` 250 | Runs on every pod; delivery is per-aggregate ordered |
| PostgreSQL | 4 vCPU / 16 GiB, SSD, `shared_buffers` 25% RAM | The ledger grows with every state change: budget ~2–5 KB per event; partition `event` by month at scale |
| Redis | 256 MiB, `allkeys-lru` | Promise cache only; losing it costs latency, not correctness |

Sweepers (SLA timers, stuck orders, breach risk, reservation expiry, calibration) run on every pod and are safe to
run concurrently; tune their periods under `oms.statemachine.*`, `oms.monitoring.*`, `oms.inventory.*`.

## 5. PostgreSQL: HA, PITR, retention

* Use a managed PostgreSQL 16 or an operator with synchronous replication (RPO 0, NFR-04) and automatic failover. The
  application reconnects through HikariCP; in-flight workflow steps retry (`oms.workflow.max-step-attempts`).
* Enable continuous WAL archiving for point-in-time recovery. Because every state change is in the ledger, a restore
  to a point in time is consistent by construction; replay from the ledger (`GET /api/v1/simulations/rebuild/{orderId}`)
  verifies an order after a restore.
* Flyway runs migrations on start (`spring.flyway.enabled=true`). Migrations are additive; never edit a shipped one.
* Append-only tables (`event`, `audit_log`, decision traces) are protected by the `reject_mutation()` trigger. Retention
  is `retention.years` (default 7); archive by exporting (`GET /api/v1/analytics/export/events`) and detaching old
  partitions rather than deleting rows.
* Connection string: add `?sslmode=require` (or `verify-full` with a CA) in production.

## 6. Secrets

| Secret | Where it goes | Notes |
|---|---|---|
| Database credentials | `OMS_DB_USER`, `OMS_DB_PASSWORD` or a URL with credentials | Rotate by rolling pods |
| JWT signing secret | `OMS_JWT_SECRET` or `OMS_JWT_SECRET_FILE` (mounted file; its content wins) | `openssl rand -base64 48`; the placeholder, anything containing `change-me` or under 32 characters is refused in production; rotating it logs everyone out (there is no per-session revocation) |
| PII master key | `OMS_MASTER_KEY` (`oms.security.master-key`) | `openssl rand -base64 32` or a passphrase; AES-256-GCM envelope encryption of per-tenant `tenant_key` material; required in production; rotation is an offline re-wrap with `EnvelopeCipher` (no dual-key window) |
| Agent secret key | `oms.agent.secret-key` | Encrypts agent HMAC secrets at rest |
| AI provider keys | `OPENROUTER_API_KEY`, `TYPESAFE_API_KEY`, `ANTHROPIC_API_KEY`, `OPENAI_API_KEY` | Optional; scope them to the deployment, set `ai.budget_usd_per_day` per tenant |
| PSP key | `oms.finance.stripe.api-key` | Only when `oms.finance.psp=stripe` |
| Webhook secrets | tenant keys `webhooks.inbound_secret`, `shopify.webhook_secret`; outbound per subscription | Set through the admin API; audited |

Never put secrets in `config/tenants/**` or in the image. Kubernetes secrets, a secrets operator or a vault agent
writing files are all fine; the application reads environment variables and files only.

## 7. OIDC single sign-on

Set `OMS_OIDC_ISSUER_URI` (`oms.security.oidc.issuer-uri`, discovery through `/.well-known/openid-configuration`) or
`OMS_OIDC_JWK_SET_URI` (explicit JWKS) and the application becomes an OIDC resource server: RS256/ES256 bearer tokens
from the IdP are validated (JWKS, issuer, audience, `exp`/`nbf` with `clock-skew`) and mapped to a principal of
type USER with `id` = first present `subject-claims` value, `tenantId` = the tenant claim (or `default-tenant`),
`roles` = mapped roles, permissions from the OMS role map, trust tier 3. A token without a resolvable tenant is
rejected (401); a token without roles is accepted with no permissions (every protected call → 403). Local HS256
session tokens and API keys keep working alongside, so administrators can still log in while the IdP is rolled out;
the filter routes on the token's `alg` header. Mapping:

| Property | Default | Purpose |
|---|---|---|
| `oms.security.oidc.audience` | none | Required `aud` value |
| `oms.security.oidc.subject-claims` | `preferred_username, sub` | Claims tried in order for the principal id |
| `oms.security.oidc.tenant-claim` | `tenant` | Claim carrying the OMS tenant id |
| `oms.security.oidc.default-tenant` | none | Tenant for tokens without the claim (single-tenant deployments); blank rejects them |
| `oms.security.oidc.roles-claim`, `roles-claim-paths` | `roles`; `realm_access.roles, groups` | Where roles/groups are read (Keycloak, Entra ID shapes supported) |
| `oms.security.oidc.role-mapping.<idp role>` | none | IdP role or group (or Entra group object id) → OMS role; unmapped values are kept only when they are built-in OMS roles |
| `oms.security.oidc.clock-skew` | 60s | Accepted skew for `exp`/`nbf` |

Environment variables with placeholders: `OMS_OIDC_ISSUER_URI`, `OMS_OIDC_JWK_SET_URI`, `OMS_OIDC_AUDIENCE`,
`OMS_OIDC_TENANT_CLAIM`, `OMS_OIDC_DEFAULT_TENANT`, `OMS_OIDC_ROLES_CLAIM`. `role-mapping`, `roles-claim-paths` and
`subject-claims` are best set in a profile YAML (or via relaxed binding, e.g.
`OMS_SECURITY_OIDC_ROLE_MAPPING_oms-admins=admin`). Matching of role and group names is case-insensitive; Keycloak
group paths match with or without the leading slash; unmapped values survive only when they are built-in OMS roles.

Keycloak (realm roles in `realm_access.roles`, client roles in `resource_access.<client>.roles`, groups via a Group
Membership mapper):

```yaml
oms:
  security:
    oidc:
      issuer-uri: https://sso.example.com/realms/oms
      audience: oms-api                      # add an audience mapper to the client, or use the client id
      tenant-claim: tenant                   # user attribute "tenant" → token claim via a User Attribute mapper
      roles-claim-paths: [realm_access.roles, resource_access.oms-api.roles, groups]
      role-mapping:
        oms-admins: admin
        oms-ops: ops_analyst
        "[/customer-service]": cs_rep        # group paths: bracket the key so Spring keeps it verbatim
```

Microsoft Entra ID (prefer app roles; the `groups` claim is omitted above 200 groups):

```yaml
oms:
  security:
    oidc:
      issuer-uri: https://login.microsoftonline.com/<tenant-id>/v2.0    # v1.0 tokens use https://sts.windows.net/<tenant-id>/
      audience: <application (client) id of the OMS API app registration>
      subject-claims: [preferred_username, oid, sub]
      tenant-claim: extension_omsTenant       # directory extension / optional claim; or use default-tenant
      default-tenant: acme
      roles-claim-paths: [roles, groups]
      role-mapping:
        OMS.Admin: admin                      # app roles (manifest appRoles → "roles" claim)
        3f1c2c4e-aaaa-bbbb-cccc-000000000001: finance_controller   # group object ids ("groups" claim)
```

Okta (custom authorization server with a groups claim):

```yaml
oms:
  security:
    oidc:
      issuer-uri: https://example.okta.com/oauth2/default
      audience: api://oms
      tenant-claim: tenant                    # custom claim from a profile attribute
      roles-claim-paths: [groups]
      role-mapping: { "OMS Admins": admin, "OMS CS": cs_rep }
```

Auth0 and any other IdP publishing `/.well-known/openid-configuration` work the same way; namespaced custom claims
(`https://oms.example.com/tenant`) are addressed by their full key. To test a mapping offline, copy the token-building
code from `backend/oms-testing/src/test/java/ai/intentoms/testing/security/OidcIntegrationTest.java` (WireMock IdP
with a generated RSA key) and assert the resulting principal.

The UI logs in through the IdP and sends the access token as a bearer; API integrations keep using API keys; agents
keep using HMAC signatures. Leave `OMS_DEV_USERS` empty in production. Known gap: there is no SCIM or just-in-time
provisioning, so SSO users have no `app_user` row and cannot use `change-password`.

## 8. Observability

* Health: `/actuator/health/liveness`, `/actuator/health/readiness` (details for authorised callers).
* Metrics: `/actuator/prometheus`. Key series: `oms_decision_latency{decision,provider}`,
  `oms_decision_evaluated_total{layer,band}`, `oms_statemachine_transition`, `oms_workflow_step`,
  `oms_outbox_delivered_total`, `oms_outbox_failed_total`, `oms_inventory_reserve`, `oms_promise_latency`,
  `oms_agent_auth{outcome}`, `oms_orchestration_webhook_rejected`. Alert on outbox failures, decision latency p95,
  escalation rate and `ai_cost_per_order` (also a KPI).
* Tracing: OTLP to `OTEL_EXPORTER_OTLP_ENDPOINT`, sampling `OTEL_SAMPLING`.
* Logs: single-line, the order id as correlation id on workflow and decision lines. Ship them with your usual agent.
* Business alerts (Slack, Teams, e-mail, PagerDuty) are tenant settings `alerts.*`.

## 9. Backups and disaster recovery

PostgreSQL is the only stateful component. Back it up with WAL archiving plus daily base backups; test a PITR restore
quarterly and run `scripts/usecases/run-all.sh` against the restored copy as a smoke test. Redis needs no backup.
Tenant configuration is in Git (`config/`) and in the database; the API GETs export the runtime versions. Keep
`oms.security.master-key` and `oms.agent.secret-key` in your secret store's backup: without them PII tokens and agent
secrets in a restored database cannot be used.

## 10. Upgrades

1. Read `CHANGELOG.md` for contract changes.
2. Deploy the new image with the rolling strategy; Flyway applies additive migrations on the first pod. In-flight
   workflow instances keep their definition version (FR-WF-08); entities keep their state-model version.
3. If a release ships a new state-model version for your tenant, publish it, dry-run the migration
   (`POST /api/v1/state-models/{id}/migrate {dryRun:true}`), then migrate.
4. If a release changes a decision definition format, re-lint your drafts; live versions are stored as parsed
   definitions and keep working.
5. Roll back by deploying the previous image; migrations are additive so the previous version still runs. Never
   downgrade across a migration that a release note marks as non-reversible.

## 11. Production checklist

Enforced at startup by `ProductionReadinessCheck` when the `prod` profile is active or
`oms.security.enforce-production=true` (the service refuses to start and lists every violation):

1. `OMS_JWT_SECRET` or `OMS_JWT_SECRET_FILE` is random, ≥ 32 characters and not the placeholder (nothing containing `change-me`).
2. `OMS_DEV_USERS` is empty.
3. `OMS_DEMO_SEED=false` and `oms.agent.demo-expose-secrets=false` (`application-prod.yml` fixes both).
4. `OMS_CORS_ALLOWED_ORIGINS` lists explicit `https://` origins (no `*`, not empty).
5. Actuator exposes at most `health,prometheus`.
6. `OMS_MASTER_KEY` is set.

Not enforced by code:

- [ ] TLS at the ingress; keep `server.forward-headers-strategy=framework` and `oms.security.trust-forwarded-for=true` only when the proxy overwrites `X-Forwarded-For`
- [ ] First administrator created: OIDC login with a group mapped to `admin`, or one `app_user` row inserted (bcrypt), then `POST /api/v1/auth/users` for the rest
- [ ] OIDC configured with the IdP's MFA; API keys issued per integration and revoked when unused (they do not expire); agent registrations with explicit limits
- [ ] `oms.agent.secret-key` set; `webhooks.inbound_secret` and `shopify.webhook_secret` set per tenant; outbound webhook secrets stored by receivers
- [ ] `/swagger-ui` left disabled (`OMS_SWAGGER_ENABLED=false`, the prod default) or restricted at the ingress
- [ ] PostgreSQL with synchronous replication, WAL archiving, `sslmode=require`, a least-privilege application role that cannot bypass the append-only triggers; Redis if you need a shared promise cache
- [ ] `ai.budget_usd_per_day`, spend guardrails and alert channels set per tenant; kill-switch drill scheduled (`docs/RUNBOOK.md`)
- [ ] Prometheus scraping and OTLP tracing wired; alerts on outbox failures, decision latency and `auth.login_failed` bursts aggregated across instances (the login limiter is per instance)
- [ ] Container image pinned by digest in the manifest; weekly trivy rescan watched; SBOM archived from CI
- [ ] Plan for `OMS_JWT_SECRET` rotation (logs everyone out) and `OMS_MASTER_KEY` rotation (offline re-wrap)

## 12. Releases and the container image

`.github/workflows/release.yml` runs on a `v*` tag (or by hand with a version input):

1. UI lint, tests and build (Node 22); backend `mvn -Psbom verify` (Temurin 21, tests against PostgreSQL 16).
2. Multi-arch image (`linux/amd64`, `linux/arm64`) with `docker/build-push-action` from `Dockerfile` at the repository root,
   pushed to `ghcr.io/<owner>/<repo>` as `:<version>`, `:<major>.<minor>` and `:latest` (tags only), labelled with
   OCI metadata, with BuildKit provenance and SBOM attestations, a GitHub build-provenance attestation and a keyless
   cosign signature (best effort; the job continues if Sigstore is unavailable). Verify with
   `cosign verify ghcr.io/<owner>/intent-oms:<version> --certificate-identity-regexp '^https://github.com/<owner>/<repo>/' --certificate-oidc-issuer https://token.actions.githubusercontent.com`.
3. A GitHub release with the jar (`intent-oms-<version>.jar`), the CycloneDX SBOMs (backend aggregate, UI, image),
   `SHA256SUMS` and the matching `CHANGELOG.md` section as notes. Pre-release when the version contains a hyphen or
   the run was dispatched by hand.

To cut a release: update `CHANGELOG.md` (move Unreleased into `## [x.y.z] - date`), bump `appVersion` in
`deploy/helm/intent-oms/Chart.yaml` and the Maven version, then `git tag vx.y.z && git push --tags`. Consumers pin by
digest (`image.digest` in the chart, `@sha256:` in manifests). `.github/workflows/ci.yml` keeps scanning the image
weekly with trivy; `.github/workflows/pages.yml` publishes `docs/site/` to GitHub Pages on pushes to `main`.
