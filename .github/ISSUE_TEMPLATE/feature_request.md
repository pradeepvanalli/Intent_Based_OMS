---
name: Feature request
about: Propose a capability, a configuration option or a change to a contract
title: "[module] short description"
labels: enhancement, needs-triage
---

## Problem

What are you trying to do, and what stops you today? Describe the business situation (order type, channel, policy)
rather than the solution.

## Proposed change

How would you like it to work? If it is a new decision, state model or playbook capability, sketch the YAML.
If it changes a REST or Java contract listed in `docs/ARCHITECTURE.md` or `docs/MODULE_NOTES.md`, say which.

## Guardrails

Which of the guardrails in `docs/TRACEABILITY.md` §8 does this touch (GR-01 to GR-17)? How does the proposal keep them?

## Alternatives considered

Including whether tenant configuration (`config/tenants/<tenant>/**`), an SPI (`PlaybookAction`, `DecisionProvider`,
`AddressVerifier`, `PaymentGateway`, `EscalationHandler`) or the copilot already covers it.

## Additional context

Requirement IDs from `docs/requirements.txt` if applicable, links, screenshots.
