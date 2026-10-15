# Support

## Before you ask

1. `docs/FAQ.md` answers the questions that come up most, including degraded mode, approvals and "why did this decision
   do that".
2. `docs/GETTING_STARTED.md` and `docs/CONFIGURATION.md` cover setup and every configuration key.
3. `docs/RUNBOOK.md` covers operational symptoms (stuck workflows, failing outbox deliveries, provider outages).
4. Search existing GitHub issues.

## Where to ask

| Need | Channel |
|---|---|
| A question about using or configuring Intent OMS | GitHub Discussions (Q&A category) |
| A reproducible bug | GitHub issue using the bug report template |
| A feature or change proposal | GitHub issue using the feature request template; larger changes become an ADR |
| A security vulnerability | **Private** report as described in `SECURITY.md`. Never open a public issue. |
| Code of Conduct concern | conduct@intentoms.ai |

## What to include in a bug report

* Version (git SHA or release tag), how you run it (compose, `make run`, Kubernetes), JDK and OS.
* The tenant configuration involved (decision or state-model YAML, tenant_config keys) with secrets removed.
* The request you sent and the response, including the RFC 9457 problem detail body and the `X-OMS-Event-Id` or order
  id if there is one.
* For decision issues: the decision record id and the output of `GET /api/v1/decision-records/{id}/why`.
* Relevant log lines. Logs carry the order id as a correlation id; `grep <orderId>` is usually enough.

## Response expectations

Maintainers are volunteers. Issues are triaged weekly; security reports follow the timelines in `SECURITY.md`. There is
no commercial support contract attached to this repository.
