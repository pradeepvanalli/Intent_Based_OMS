# Exceptions, playbooks and SLAs

### Carrier delays

> When a carrier shows a delay of more than two days, tell the customer and re-promise automatically; only involve
> ops if the re-promise fails.

Expected: a `repromise` playbook draft (`PUT /api/v1/playbooks/repromise`) with steps
`repromise` → `contact_customer (template delay_notice, requiresDecision: sla_breach_risk, minConfidence 0.8)` →
`create_task (queue ops_exceptions, onFailure: true)`, plus the `carrier_delay` taxonomy row pointing at it (noting
that `exceptionTypes` have no write API yet, so the file change needs the seeder path or a maintainer).

### Stuck orders

> If a released order has not been picked within 6 hours, re-source it somewhere else.

Expected: `sla.yaml` draft `{ orderType: standard_ship, entity: order_line, state: released, maxAge: PT6H, onBreach: playbook.resource }`
(`PUT /api/v1/sla-definitions`) and the matching `sla:` block on `order_line.standard_ship` (new state-model version
with a dry-run migration note). The copilot points out the existing `store_backlog` playbook already re-sources after
240 minutes and asks which should win.

### Payment retry

> Retry expiring authorisations three days before they lapse and hold the order if the retry fails.

Expected: confirms the shipped `payment_retry` playbook does this (`reauthorize` with `within: P3D`, `hold` on failure,
`create_task finance_exceptions`), shows it, proposes no change.

### Alerting

> Page the on-call engineer for any integration failure, Slack for everything else.

Expected: `notify_oncall` playbook unchanged; tenant keys `alerts.pagerduty_routing_key` and `alerts.slack_webhook`
flagged as secrets to be set through the API, not drafted with values.
