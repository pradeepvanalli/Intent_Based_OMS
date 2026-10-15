## Decision definition (YAML, PRD Appendix B extended)
Required: `decision` (snake_case), `version` (int), `owner`, `tier` (T0..T3), `provider` (jev|llm|heuristic),
`default` (L0 value used when AI is unavailable), `state.include` (dotted input paths the model may see),
`questions` (map; at least one). Optional: `fallbackProvider`, `timeoutMs`, `mainQuestion`, `rules` (L0, evaluated by
ascending `priority`; first match wins; `then` is a value, `escalate` or `block`), `preChecks`, `postChecks`, `bands`
(`act.confidence_gte`, `confirm.confidence_gte`, `escalate.otherwise: true`), `composite`, `topK`, `cache.ttl`
(ISO-8601), `evaluation` (`goldenSet`, `minAccuracyInActBand`, `maxCalibrationError`), `changeReason`, `ticket`.
Question types: `choice` (criteria map option -> description; MUST contain an escape option such as `unclear`,
`other`, `none_of_these`, `not_enough_information`, `escalate`), `score` (criteria list of 2..10 rubric levels low to
high; value is the level index), `noul` (a statement; `reasonCode: true` makes it a reason code).
Tier T3 never acts; T2 acts only at high confidence. Thresholds (bands) are approved by people.

Example (complete):
```yaml
decision: address_risk
version: 1
owner: fulfilment-ops
tier: T1
provider: jev
fallbackProvider: heuristic
timeoutMs: 800
default: false
state:
  include:
    - address.verification_status
    - address.type
    - address.po_box
    - order.total
    - order.ship_method
rules:
  - id: freight_forwarder_block
    when: "input.address.freight_forwarder_match == true && input.order.total > 500"
    then: escalate
    reason: freight_forwarder_high_value
    priority: 10
questions:
  risky:
    type: noul
    statement: This delivery address is likely to fail or be fraudulent.
    reasonCode: true
bands:
  act: { confidence_gte: 0.8 }
  confirm: { confidence_gte: 0.5 }
  escalate: { otherwise: true }
evaluation:
  goldenSet: address_risk_gold
  minAccuracyInActBand: 0.9
  maxCalibrationError: 0.05
```
Golden test case shape: `{"caseId": "x_01", "input": {...same top-level keys as state.include roots...}, "expected": {"value": <value>}}`.
