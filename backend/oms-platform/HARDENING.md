# Intent OMS security hardening notes

Engineering notes for the production/open-source hardening pass (NFR-07 authentication & SSO, NFR-08 data
protection, NFR-09 abuse protection). This file is the source of truth for the `oms.security.*` properties; the
docs owner should fold the relevant parts into `docs/`.

## 1. What was added

| Area | Change | Where |
|---|---|---|
| OIDC / SSO | Optional resource-server mode: RS256/ES256 bearer tokens from any OpenID Connect IdP are validated (JWKS, issuer, audience, exp/nbf) and mapped to an OMS `Principal`. Local HS256 session tokens keep working side by side. | `security/OidcTokenAuthenticator`, `AuthenticationFilter`, `SecurityProperties.Oidc` |
| Fail-fast production guard | `prod` profile (or `oms.security.enforce-production=true`) aborts startup before any bean is created, listing every violation. | `oms-app: ai.intentoms.app.ProductionReadinessCheck` (registered via `META-INF/spring.factories`), `application-prod.yml` |
| Security headers | CSP tuned for the bundled SPA (inline theme script allowed by sha256 hash, no `unsafe-inline` for scripts), `X-Content-Type-Options`, `Referrer-Policy`, `Permissions-Policy`, `X-Frame-Options`, `Cross-Origin-Opener-Policy`, HSTS in prod. Relaxed CSP for `/swagger-ui` and `/v3/api-docs`. Applies to both security chains (`/api`, `/mcp`, `/ucp`) and static assets. | `security/SecurityHeadersFilter` |
| CORS | `oms.security.cors.allowed-origins` (default `*` for dev, explicit list required in prod). | `PlatformConfig.cors` |
| Login hardening | Per-IP and per-user token-bucket rate limit (429 `rate_limited`), constant-time bcrypt path for unknown users, `auth.login` / `auth.login_failed` audit rows. | `api/AuthController`, `security/ClientIp` |
| New auth endpoints | `POST /api/v1/auth/logout` (204), `POST /api/v1/auth/change-password` (204; requires current password), `POST /api/v1/auth/users` (`config.admin`; creates a local user). | `api/AuthController`, `security/PasswordPolicy` |
| Password policy | Minimum 12 chars (configurable) for every password set through the API. Bootstrap `dev-users` bypass it and are forbidden in prod. | `security/PasswordPolicy` |
| Secrets | `oms.security.jwt-secret-file` (file content wins over inline value). | `security/SecretSources`, `JwtService` |
| PII key envelope encryption | `oms.security.master-key` wraps `tenant_key.key_material` with AES-256-GCM (`enc:` prefix). Legacy plaintext rows stay readable and are re-wrapped on first use. Warning without a key outside prod; startup failure in prod. | `pii/EnvelopeCipher`, `pii/Tokenizer` |
| Monotonic UUIDv7 | `Ids.uuid7()` is now strictly increasing within a JVM (RFC 9562 counter method); fixes a flaky ordering test and improves index locality. | `ids/Ids` |
| Container | `-Djava.io.tmpdir=/tmp` + `VOLUME /tmp`, `exec java`, chowned files; compose runs `read_only` with a tmpfs `/tmp`. | `oms-app/Dockerfile`, `docker-compose.yml`, `docker-compose.prod.yml` |
| Kubernetes | Pod + container `securityContext` (non-root, read-only root FS, no privilege escalation, drop ALL, seccomp RuntimeDefault), tmpfs `/tmp`, JWT secret mounted as a file, `prod` profile, NetworkPolicy. | `deploy/k8s/oms.yaml` |
| CI supply chain | `trivy fs` (deps/IaC/secrets, SARIF + fail on fixable HIGH/CRITICAL), `trivy image` on the built image, CycloneDX SBOM (Maven profile `sbom` + npm + image SBOM) uploaded as artifacts, `gitleaks`, `npm audit --audit-level=high` (advisory). Weekly scheduled rescan. | `.github/workflows/ci.yml`, parent `pom.xml` profile `sbom` |

## 2. Properties (`oms.security.*`)

All defaults are the development defaults; the "prod" column is what `application-prod.yml` sets or what the
readiness check requires.

| Property | Default | Env var (application.yml / application-prod.yml) | Prod requirement / notes |
|---|---|---|---|
| `jwt-secret` | `change-me-in-production-please-32chars` | `OMS_JWT_SECRET` | Must not be the placeholder, must not contain `change-me`, >= 32 chars. `openssl rand -base64 48` |
| `jwt-secret-file` | `` (unset) | `OMS_JWT_SECRET_FILE` | Path of a mounted secret; trimmed file content replaces `jwt-secret`. Unreadable file = startup failure. |
| `jwt-ttl` | `8h` | – | Session token lifetime. |
| `dev-users` | `` (app yml ships 6 demo users) | `OMS_DEV_USERS` | Must be empty in prod (`OMS_DEV_USERS=`). Format `id:password:tenant:role1|role2`. |
| `public-paths` | health, prometheus, api-docs, swagger, login, logout, promise reads, `.well-known`, SPA assets | – | Served without authentication. |
| `enforce-production` | `false` | – | Run the readiness check and HSTS default without the `prod` profile. |
| `hsts` | `null` → on in prod, off otherwise | – | `Strict-Transport-Security: max-age=31536000; includeSubDomains`. `application-prod.yml`: `true`. |
| `content-security-policy` | see `SecurityHeadersFilter.DEFAULT_CSP` | – | `{inline-script-hashes}` expands to sha256 hashes of inline scripts in `classpath:/static/index.html`. If the UI ever loads Google Fonts add `https://fonts.googleapis.com` to `style-src` and `https://fonts.gstatic.com` to `font-src` (the current UI bundle has no external font links). |
| `swagger-content-security-policy` | relaxed (`'unsafe-inline'` scripts/styles) | – | Applied to `/swagger-ui/**`, `/v3/api-docs/**`. Swagger is disabled in prod unless `OMS_SWAGGER_ENABLED=true`. |
| `permissions-policy` | `camera=(), microphone=(), geolocation=(), payment=(), usb=(), interest-cohort=()` | – | |
| `trust-forwarded-for` | `false` | – | Use first `X-Forwarded-For` hop as client IP for login rate limiting. `application-prod.yml`: `true` (together with `server.forward-headers-strategy=framework`). Only enable behind a proxy that overwrites the header. |
| `login-rate-limit` | `10` | – | Attempts per IP **and** per `tenant/user` within `login-rate-window`; excess → `429 rate_limited`. In-memory per instance (see gaps). |
| `login-rate-window` | `1m` | – | |
| `password-min-length` | `12` | – | For `change-password` and `POST /auth/users`. |
| `master-key` | `` | `OMS_MASTER_KEY` | Required in prod. Base64 of 32 bytes (`openssl rand -base64 32`) or any passphrase (SHA-256 derived). Wraps tenant PII keys. |
| `cors.allowed-origins` | `*` | `OMS_CORS_ALLOWED_ORIGINS` (comma separated) | Prod: explicit origins, no `*`. |
| `cors.allow-credentials` | `false` | `OMS_CORS_ALLOW_CREDENTIALS` | Only honoured with explicit origins. |
| `oidc.issuer-uri` | `` | `OMS_OIDC_ISSUER_URI` | Expected `iss`. Enables OIDC. JWKS discovered from `<issuer>/.well-known/openid-configuration` unless `jwk-set-uri` is set. |
| `oidc.jwk-set-uri` | `` | `OMS_OIDC_JWK_SET_URI` | Explicit JWKS endpoint (skips discovery; also enables OIDC on its own). |
| `oidc.audience` | `` | `OMS_OIDC_AUDIENCE` | Required `aud` member; blank = no audience check (not recommended). |
| `oidc.subject-claims` | `preferred_username, sub` | – | First present claim becomes `Principal.id` (shown in audit/ledger actor refs). |
| `oidc.tenant-claim` | `tenant` | `OMS_OIDC_TENANT_CLAIM` | Claim path carrying the OMS tenant id. |
| `oidc.default-tenant` | `` | `OMS_OIDC_DEFAULT_TENANT` | Tenant for tokens without the tenant claim (single-tenant installs). Blank rejects such tokens. |
| `oidc.roles-claim` | `roles` | `OMS_OIDC_ROLES_CLAIM` | Primary roles claim path. |
| `oidc.roles-claim-paths` | `realm_access.roles, groups` | – | Extra paths, dot or slash separated (`resource_access.oms.roles`, `/realm_access/roles`). All matches are unioned. |
| `oidc.role-mapping` | `{}` | – | IdP role/group → OMS role (several with `a|b`). Matching is case-insensitive; Keycloak group paths (`/ops/warehouse`) match with or without the leading slash. Unmapped values are kept only when they are built-in OMS roles (`Roles.DEFAULT`); everything else (e.g. `offline_access`, unknown Entra group ids) is dropped. |
| `oidc.clock-skew` | `60s` | – | Tolerance on `exp`/`nbf`. |

Other properties touched: `management.endpoints.web.exposure.include` must be a subset of `health,prometheus` in prod;
`oms.demo.seed` and `oms.agent.demo-expose-secrets` must be `false` in prod; `server.forward-headers-strategy=framework`
in `application-prod.yml`.

## 3. Configuring an identity provider

Principal mapping: `type=USER`, `id` = `preferred_username` or `sub`, `tenantId` = tenant claim, `roles` = mapped
roles, `permissions = Roles.permissionsFor(roles)`, `trustTier = 3`. A token that validates but yields no tenant is
rejected (401). A token that yields no roles is accepted with no permissions (every `@RequiresPermission` → 403).

Local HS256 tokens and API keys remain valid when OIDC is on, so admins can still log in with `POST /auth/login`
while the IdP is being rolled out. The filter routes on the token's `alg` header: asymmetric → OIDC first, then
local; `HS*` → local first, then OIDC.

### Keycloak

```yaml
oms:
  security:
    oidc:
      issuer-uri: https://sso.example.com/realms/oms
      audience: oms-api                      # add an audience mapper to the client, or use the client id
      tenant-claim: tenant                   # user attribute "tenant" -> token claim via a "User Attribute" mapper
      roles-claim-paths: [realm_access.roles, resource_access.oms-api.roles, groups]
      role-mapping:
        oms-admins: admin
        oms-ops: ops_analyst
        "[/customer-service]": cs_rep        # group paths: bracket the key so Boot keeps it verbatim
```
Keycloak puts realm roles in `realm_access.roles`, client roles in `resource_access.<client>.roles`, groups (with a
"Group Membership" mapper, full path on) in `groups`.

### Microsoft Entra ID

```yaml
oms:
  security:
    oidc:
      issuer-uri: https://login.microsoftonline.com/<tenant-id>/v2.0
      audience: <application (client) id of the OMS API app registration>
      subject-claims: [preferred_username, oid, sub]
      tenant-claim: extension_omsTenant       # directory extension / optional claim; or use default-tenant
      default-tenant: acme
      roles-claim-paths: [roles, groups]
      role-mapping:
        OMS.Admin: admin                      # app roles (manifest appRoles -> "roles" claim)
        3f1c2c4e-aaaa-bbbb-cccc-000000000001: finance_controller   # group object ids ("groups" claim)
```
Note that `iss` for v2.0 tokens is `https://login.microsoftonline.com/<tenant-id>/v2.0`; v1.0 tokens use
`https://sts.windows.net/<tenant-id>/`. Group overage (>200 groups) returns no `groups` claim; prefer app roles.

### Okta

```yaml
oms:
  security:
    oidc:
      issuer-uri: https://example.okta.com/oauth2/default   # a custom authorization server
      audience: api://oms                                   # the server's audience
      tenant-claim: tenant                                  # custom claim from a profile attribute
      roles-claim-paths: [groups]                           # "groups" claim with a Groups filter
      role-mapping: { "OMS Admins": admin, "OMS CS": cs_rep }
```

### Auth0 / generic

Any IdP that publishes `/.well-known/openid-configuration` works. Custom claims that are namespaced URLs (Auth0
style `https://oms.example.com/tenant`) can be addressed as a whole key: the mapper tries the full key before
splitting on dots/slashes.

### Testing a mapping

`backend/oms-testing/src/test/java/ai/intentoms/testing/security/OidcIntegrationTest.java` runs a WireMock IdP with a
generated RSA key. Copy its token-building code to reproduce a customer token shape and assert the resulting
`Principal` via `OidcTokenAuthenticator.authenticate(token)`.

## 4. Production checklist

Startup refuses to proceed (`NotProductionReadyException`) unless all of the following hold when the `prod` profile is
active or `oms.security.enforce-production=true`:

1. `OMS_JWT_SECRET` (or `OMS_JWT_SECRET_FILE`) is random, >= 32 characters and not the placeholder.
2. `OMS_DEV_USERS` is empty.
3. `OMS_DEMO_SEED=false` and `oms.agent.demo-expose-secrets=false` (`application-prod.yml` fixes both).
4. `OMS_CORS_ALLOWED_ORIGINS` lists explicit `https://` origins (no `*`, no empty).
5. Actuator exposes at most `health,prometheus`.
6. `OMS_MASTER_KEY` is set (tenant PII key envelope encryption).

Operational checklist (not enforced by code):

- Terminate TLS at the ingress and keep `server.forward-headers-strategy=framework` + `oms.security.trust-forwarded-for=true`
  only when the proxy overwrites `X-Forwarded-For`; otherwise set `trust-forwarded-for=false`.
- Create the first administrator: with dev users disabled, either sign in through OIDC with a role mapped to `admin`
  or insert one `app_user` row (bcrypt hash) and then use `POST /api/v1/auth/users` for the rest.
- Rotate `OMS_JWT_SECRET` to invalidate all sessions (stateless tokens cannot be revoked individually).
- Rotating `OMS_MASTER_KEY`: decrypt every `tenant_key.key_material` with the old key and re-encrypt with the new one
  (`EnvelopeCipher`), then restart. There is no dual-key window yet (see gaps).
- Keep `/swagger-ui` disabled (`OMS_SWAGGER_ENABLED=false`, the default in prod) or restrict it at the ingress.
- Database: use a least-privilege role; the append-only ledger/audit triggers must not be bypassable by the app role.
- Pin the image by digest in `deploy/k8s/oms.yaml` (the sample uses `:latest`) and watch the weekly trivy rescan.
- Review the audit log for `auth.login_failed` bursts; the limiter is per instance, so an aggregated alert is still needed.

## 5. Threat notes on specific decisions

- **Constant-time login**: an unknown user still costs one bcrypt comparison against a fixed hash, so response time
  does not reveal whether a user id exists. Failed and successful logins are audited with the client IP.
- **Rate limiter keys**: `auth.login.ip:<ip>` and `auth.login.user:<tenant>/<user-lowercased>`. Both buckets are
  consumed before the credential check, so credential stuffing across many IPs for one user is still capped.
- **CSP and the SPA**: Vite emits one inline theme-bootstrap script in `index.html`; its hash is computed at startup
  from the bundled file, so a UI rebuild never breaks the policy. React inline `style` attributes require
  `style-src 'unsafe-inline'` (style injection is not a script-execution vector).
- **MCP/UCP chain**: `SecurityHeadersFilter` is a plain servlet filter and does not touch `AgentSecurityConfig`; the
  agent chain still runs `AuthenticationFilter` (now OIDC-aware) → `AgentAuthFilter` → `TenantFilter`.
- **Envelope encryption**: AES-256-GCM, random 96-bit IV, AAD `tenant_key`. Plaintext rows are upgraded on first read
  with a compare-and-swap (`where key_material = :old`) so concurrent instances cannot double-wrap.

## 6. Known gaps

- **SCIM / JIT provisioning is not implemented.** OIDC users exist only as principals in their tokens; there is no
  `app_user` row for them, so `change-password` returns 403 for SSO accounts and per-user settings cannot be stored.
- **No refresh tokens or server-side session revocation** for local HS256 tokens; logout is client-side.
- **Login rate limiting is per instance** (Caffeine + Bucket4j). Behind N replicas the effective limit is N×. Swap
  `RateLimiter` for a Redis-backed bucket for a global limit.
- **Field-level encryption covers PII tokenisation keys only**; order payloads, addresses and ledger events are
  stored in clear in PostgreSQL. Use storage-level encryption (TDE/volume) and restrict DB access.
- **Master-key rotation** has no dual-key grace period; it is an offline re-wrap.
- **No MFA** for local accounts; use the IdP's MFA via OIDC.
- **No account lockout** beyond the rate limiter; no password history or breach-list check.
- **API keys** are SHA-256 hashed (fine for 256-bit random secrets) but have no expiry; add `expires_at` if needed.
- **Audit log for failed logins of unknown tenants** is written under the requested tenant id (free text column);
  there is no FK, so typos create orphan tenant ids in `audit_log`.
- **CSP `connect-src 'self'`** must be widened if the UI ever calls a third-party API directly (maps, analytics).
- **Dependency scanning** uses trivy; OWASP dependency-check is not run (slower, NVD key needed). Add it if a
  policy requires NVD-based reports.
