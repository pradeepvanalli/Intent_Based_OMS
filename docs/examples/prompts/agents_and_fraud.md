# Agents, mandates and address risk

### Agent spending limits

> Shopping agents we have not vetted may place orders up to $200 and must get a human confirmation for anything
> that cancels or returns.

Expected: an agent policy draft: `trustTier: 1` defaults with `limits.maxOrderValueUsd: 200`,
`confirmActions: [order_cancel, return_create]` for the listed agents (`PUT /api/v1/agents/{id}`), and the tenant
key `agent.default_max_order_value: 200`. No decision change.

### Mandate scope

> Refuse any agent order whose items are outside the categories named in the mandate.

Expected: the copilot explains scope checking happens in `MandateService` before capture and that the T3
`agent_mandate_match` decision only recommends; it drafts a sharper `within_mandate` statement/criteria and reminds
the reviewer that T3 never acts (a `risk_review` task is always opened when the hold is placed).

### Reshipper addresses

> Treat any address that matches a known freight forwarder as high risk and hold the order.

Expected: `address_risk` L0 rule `when: "input.address.freight_forwarder_match == true"`, `then: true`,
`reason: freight_forwarder`, priority 10, with the note that a `true` value feeds the `address_hold` and that the
`zip_proxy` lint stays clean because no ZIP is used.

### Prompt injection sensitivity

> Be stricter about instructions hidden in agent text.

Expected: tenant key draft `agent.injection_threshold: 0.6` (from 0.7) with the last 30 days' `injection_screen`
distribution so the reviewer can see how many more orders would be held.
