---
name: Bug report
about: Something behaves differently from the documentation or from what the code intends
title: "[module] short description"
labels: bug, needs-triage
---

<!-- Security vulnerabilities: do NOT file them here. Follow SECURITY.md. -->

## What happened

A clear description of the behaviour you saw.

## What you expected

## How to reproduce

Steps, with the exact requests. Redact secrets and personal data.

```bash
# e.g.
curl -s -X POST http://localhost:8080/api/v1/orders -H "Authorization: Bearer $TOKEN" -d @order.json
```

Response (RFC 9457 problem detail if any):

```json
```

## Context

* Version / git SHA:
* How you run it: `make run` | `docker compose` | Kubernetes | other
* JDK / OS:
* AI providers configured: none (degraded mode) | Jev | LLM (which)
* Tenant configuration involved (decision or state-model YAML, `tenant_config` keys), secrets removed:

## Decision traces (if a decision is involved)

Decision record id and the output of `GET /api/v1/decision-records/{id}/why`:

```json
```

## Logs

Relevant lines (the order id is the correlation id on workflow and decision log lines):

```
```
