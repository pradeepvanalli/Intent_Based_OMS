## Playbooks (YAML, FR-MON-04)
`playbooks`: list of `{name, description, steps: [ {action, ...gates, ...params} ]}`. Known actions: notify
(channel: slack|teams|pagerduty|email, template), create_task (queue), hold (holdType, blocks: [release|event.shipped]),
classify, log, resource, repromise, contact_customer (template), reship, refund (amountUsd), cancel, reauthorize,
retry_workflow. Gates: `minConfidence` (0..1), `when` (CEL over exception/order), `requiresDecision` (decision name),
`onFailure: true` (runs only if a previous step failed), `spendLimit` (key into spendLimits). Optional top-level
`spendLimits: {reship_usd, refund_usd, appeasement_usd}`.

Example:
```yaml
playbooks:
  - name: carrier_outage
    description: Re-promise and tell the customer when a carrier scan shows a delay
    steps:
      - { action: repromise, minConfidence: 0.5 }
      - { action: contact_customer, template: delay_notice, requiresDecision: sla_breach_risk, minConfidence: 0.8 }
      - { action: create_task, queue: cs_exceptions, when: "exception.age_minutes > 240" }
```
