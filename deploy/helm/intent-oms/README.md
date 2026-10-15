# intent-oms Helm chart

Deploys Intent OMS (`ghcr.io/pradeepvanalli/invent_based_oms`) with a ConfigMap for non-secret settings, a Secret (chart-managed
or yours), optional bundled PostgreSQL, Ingress, HPA, PodDisruptionBudget, NetworkPolicy and a ServiceAccount.
Production-shaped by default: `prod` Spring profile, read-only root filesystem, non-root, all capabilities dropped.

```bash
# evaluation in any cluster (kind, minikube, k3d): demo data + bundled PostgreSQL
helm install oms deploy/helm/intent-oms --set demo.enabled=true --set postgresql.enabled=true
kubectl port-forward svc/oms-intent-oms 8080:80     # http://localhost:8080, tenant demo, admin / admin

# production: external PostgreSQL, your secrets, an ingress
helm install oms deploy/helm/intent-oms -f my-values.yaml
```

`my-values.yaml`:

```yaml
image: { tag: v0.1.0 }                      # or digest: sha256:...
secrets:
  values:
    OMS_DB_URL: jdbc:postgresql://pg.internal:5432/oms?sslmode=require
    OMS_DB_USER: oms
    OMS_DB_PASSWORD: "..."
    OMS_JWT_SECRET: "..."                   # openssl rand -base64 48
    OMS_MASTER_KEY: "..."                   # openssl rand -base64 32; back it up
    OPENROUTER_API_KEY: sk-or-v1-...        # optional
config:
  OMS_CORS_ALLOWED_ORIGINS: https://oms.example.com
  OMS_OIDC_ISSUER_URI: https://login.example.com/realms/oms
  OMS_OIDC_AUDIENCE: intent-oms
ingress:
  enabled: true
  className: nginx
  annotations: { cert-manager.io/cluster-issuer: letsencrypt }
  hosts: [{ host: oms.example.com, paths: [{ path: /, pathType: Prefix }] }]
  tls: [{ secretName: oms-tls, hosts: [oms.example.com] }]
```

Prefer a Secret you manage (external-secrets, sealed-secrets)? `secrets.create=false`, `secrets.existingSecret=<name>`
with the same keys (`OMS_DB_URL`, `OMS_DB_USER`, `OMS_DB_PASSWORD`, `OMS_JWT_SECRET`, `OMS_MASTER_KEY`, AI keys).
Every value and its meaning is documented in `values.yaml`; the environment variables map to Spring properties as
listed in `.env.example` and `docs/CONFIGURATION.md`. Lint: `make helm-lint`. Plain manifests without Helm:
`deploy/k8s/oms.yaml`.
