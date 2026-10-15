# Authoring decisions

A decision definition is a YAML document that says what question the business is asking, which typed answers are
allowed, how confident the system must be before it acts on its own, and what happens otherwise. The runtime
(`DecisionRuntime`, `docs/ARCHITECTURE.md` §3.3) evaluates it in fixed layers: L0 rules → pre-checks → L1 typed provider
→ band → post-checks → L2 LLM confirmation (confirm band) → L3 human escalation → default. Nothing a model returns can
be outside the options you declare (GR-04).

Examples: `config/tenants/demo/decisions/` (14 decisions), `config/tenants/starter/decisions/` (the same with
production gates), `docs/examples/decisions/` (two annotated extras).

## Anatomy

```yaml
decision: return_risk          # name; also the REST path segment and the DecisionService.decide() key
version: 1                     # managed by the studio once published
owner: returns-and-risk        # business owner shown in the studio
tier: T2                       # T0 | T1 | T2 | T3 — sets default bands and approvals (below)
provider: jev                  # L1 provider: jev | llm.anthropic | llm.openai | llm.structured (alias) | heuristic
fallbackProvider: heuristic    # used on timeout, error, kill switch, budget; then `default`
timeoutMs: 800                 # GR-07 budget for the L1 call
default: on_inspection         # the deterministic answer when every layer fails or the kill switch is on
mainQuestion: abuse_risk       # optional: which question's answer is the decision value (default: first choice, else composite)
state:
  include:                     # dotted paths copied from the input into the provider state (GR-09 tokenises PII keys)
    - return.reason
    - customer.history.returns_12m
    - candidates[].backlog_units   # [] fans out over a list
rules:                         # L0, first match by priority (lower first); evaluated before any model
  - id: out_of_window
    when: "input.return.days_since_delivery > input.policy.window_days"   # CEL over `input`
    then: deny_out_of_window   # a value (short-circuits as L0_RULE/ACT), or `escalate`, or `block`
    reason: out_of_window
    priority: 10
preChecks:                     # CEL; a failing check blocks the model and applies the default
  - "has(input.request.text) && size(input.request.text) > 0"
questions:                     # one or more typed questions asked in a single provider call
  abuse_risk:
    type: score                # choice | score | noul
    instructions: How high is the abuse risk of this return?
    feeds: refund_timing       # free-text tag for the studio
    criteria:                  # score: 2–10 rubric levels, index = value (0..n-1)
      - No abuse signals ...
      - Low risk ...
  serial_returner:
    type: noul                 # a probability for one statement; confidence = |2p − 1|
    statement: The customer is a serial returner.
    reasonCode: true           # surfaces as a reason code when p > 0.5
bands:                         # confidence thresholds; omitted entries use the tier defaults
  act: { confidence_gte: 0.9 }
  confirm: { confidence_gte: 0.5 }
  escalate: { otherwise: true }
postChecks:                    # CEL over `answers`, `decision`, `input`, `chosen_plan`, `tenant`; failure discards the answer
  - "!(input.item.hazmat == true && decision.value == 'donate')"
composite:                     # optional: weighted sum of score questions
  weights: { on_time: 0.7, cost_efficiency: 0.3 }
  outputKey: node_score
topK:                          # optional: two-stage ranking for long candidate lists (FR-DEC-05)
  scoreQuestion: on_time
  k: 20
  candidatesField: candidates
cache:
  ttl: PT10M                   # cache by (version, input hash) (FR-DEC-11)
evaluation:                    # golden-set gate enforced at publish (GR-11)
  goldenSet: return_risk_gold
  minAccuracyInActBand: 0.90
  maxCalibrationError: 0.05
```

## Question types

| Type | Answer | Confidence | Use for |
|---|---|---|---|
| `choice` | one key from `criteria` (map option → description, ≤ 255 options) | provider's probability mass on the chosen option | classification, routing, "which action" |
| `score` | a level index from the `criteria` list (2–10 rubric levels) | provider confidence | risk, likelihood, quality on an ordinal scale |
| `noul` | probability that `statement` is true; `criteria: {"true": ..., "false": ...}` optional | `\|2p − 1\|` | yes/no flags, reason codes (`reasonCode: true`) |

Every `choice` question must include an **escape option** (GR-03): an option that means "not enough information"
(`unclear`, `other`, `escalate`). Declare it with `escapeOption: unclear` or name an option `unclear`/`other`/
`escalate`; when the provider picks it the band is forced to ESCALATE. Lint code `missing_escape_option` blocks publish.

Write option descriptions as the criteria a careful colleague would apply, not as labels. Lint flags `vague_option`
and `vague_rubric_level` when a description is too short, and `overlapping_options` when two descriptions share most
of their words.

## Tiers and bands

| Tier | Meaning | Default bands | Approvals to publish |
|---|---|---|---|
| T0 | Low-stakes classification, reversible | act ≥ 0.5, else escalate | 1 |
| T1 | Operational decisions with money or customer impact bounded by policy | act ≥ 0.8, confirm ≥ 0.5, else escalate | 1 |
| T2 | Money-moving or customer-affecting judgement calls | act ≥ 0.9, confirm ≥ 0.5, else escalate | 2 (distinct people) |
| T3 | Never acts: recommendation only, a person decides | confirm ≥ 0.5, else escalate | 2 |

Bands decide the action: **act** applies the answer; **confirm** asks the L2 LLM to pick one of the enumerated options
with the trace as context (T1/T2 only; the answer is accepted only if it is a valid option), otherwise a person;
**escalate** creates an L3 escalation (a human task in queue `decision_escalations` through the orchestration
`EscalationHandler`) and returns `requiresHuman=true` with a recommendation. A T1 decision in the confirm band with no
L2 verdict applies its `default` deterministically so degraded mode does not flood a queue. Thresholds can only be
changed by humans in the studio, never by the copilot or an agent (GR-14).

## L0 rules

Rules run before any model and are the right place for policy that must never be a judgement call: legal
restrictions, hard limits, trusted fast lanes. `when` is CEL over `input` (the full decision input, not just
`state.include`). `then` is one of:

* a **value** that is a valid option / level: the decision resolves at layer `L0_RULE`, band ACT, with `rulesFired`
  and `reasonCodes` set;
* `escalate`: straight to L3 with the reason;
* `block`: the action is `blocked` and the default applies.

Lower `priority` runs first; the first match wins. Lint `rule_value_not_an_option` rejects a value that is not an
option; `option_blocked_by_rule` warns when a rule makes an option unreachable.

```yaml
rules:
  - id: trusted_customer
    when: "input.return.receipt == true && input.customer.history.returns_12m <= 1 && input.return.value_usd <= 250"
    then: 0
    reason: trusted_customer_fast_lane
    priority: 40
```

CEL tips: use `has(input.customer.history.flags)` before reading optional fields; lists support `in`,
`exists(x, ...)`, `size()`; strings support `matches()`, `startsWith()`. `CelEngine.validate` runs at lint time, so a
syntax error is reported as `cel_invalid` before the draft can be submitted.

## State includes and privacy

`state.include` is the only thing a provider sees. Keep it minimal and relevant. Keys named `name`, `email`, `phone`
or `address` are tokenised per tenant before leaving the system (GR-09); the trace shows them as `em_3f9a…`. Lint
`protected_attribute` blocks race, religion, gender and similar; `zip_proxy` warns when ZIP appears in a decision
where it would act as a demographic proxy rather than a deliverability signal (document the reason in a comment as
`address_risk` does). `pii_in_prompt` flags PII values in instructions or criteria text.

Untrusted text (customer notes, agent instruction text) is allowed in `state` as data; it is never concatenated into
the instructions (GR-08). The `injection_screen` decision runs on such text before it reaches any LLM layer.

## Composite and top-K

`composite.weights` turns several `score` questions into one weighted value published under `outputKey`; the runtime
normalises probabilities so the composite is the main answer when `mainQuestion` is absent and there is no choice
question. `topK` handles long candidate lists (sourcing has up to `sourcing.candidate_limit` nodes): the
`scoreQuestion` is asked per candidate in chunks, the best `k` go to the final question. Inputs use `candidates[]`
paths; results arrive as `answers.ranking` plus `details.topK.ranking[{id, score, confidence, components, rank}]`.

## Pre- and post-checks (GR-01)

Pre-checks gate the model (`input` root). Post-checks gate the answer (`answers`, `decision.value`, `input`,
`chosen_plan`, `tenant`): a failed post-check discards the answer, logs `decision.postcheck_failed` and applies the
default. Use them for invariants that must hold regardless of what any layer says, such as "never split hazmat" or
"never donate hazmat".

## Golden sets and the evaluation gate (GR-11)

A golden set is a JSON file of labelled cases:

```json
{"goldenSet": "return_risk_gold",
 "cases": [{"caseId": "rr_01", "input": {"return": {...}, "customer": {...}, "policy": {"window_days": 30}}, "expected": {"value": 0}}]}
```

`POST /api/v1/decisions/{name}/versions/{v}/evaluate` runs every case through the draft in SIMULATION mode and reports
accuracy in the act band and calibration error (mean |confidence − accuracy| over 10 bins). Publishing (non-emergency)
requires `passed: true` against `evaluation.minAccuracyInActBand` and `maxCalibrationError`. The demo tenant relaxes
these to 0.6 / 0.5 so it passes with the heuristic provider; the starter uses the production 0.90 / 0.05, which a
deterministic fallback will not meet: plan to run the evaluation with Jev or an LLM configured, or publish the initial
versions through the emergency path with a retro review (what `scripts/new-tenant.sh` does by default).

Aim for 30–100 cases per decision covering every option and the boundaries of each rule. Add real cases over time from
labelled records (`POST /api/v1/decision-records/{id}/label {"label":"correct","details":{"value":false}}` feeds
calibration; `label` with the right value becomes a candidate golden case).

## Lifecycle: draft → shadow → canary → live

1. `POST /decisions/{name}/drafts {yaml}` creates version n+1 as `draft`. Edit with `PATCH` dotted paths or replace
   the YAML with `POST .../versions/{v}/yaml`.
2. `lint`, `impact` (re-run the last N days of stored inputs of the live version against the draft: flips, band mix,
   escalations, cost, latency) and `evaluate`.
3. `submit {reason, ticket}` → `pending_approval`; `approve` by someone other than the submitter (two people for
   T2/T3) → `approved`.
4. `publish {rollout: "shadow"}` runs the version on every live decision asynchronously and stores SHADOW records
   without acting: compare in `GET /analytics/decisions/{name}`.
5. `publish {rollout: "canary:25"}` serves the version to 25% of subjects (by hash of subject id); only one canary at
   a time.
6. `publish {rollout: "live"}` retires the previous live/canary/shadow versions. `rollback` swaps the most recently
   retired version back. `kill-switch {on: true}` drops the decision to its `default` instantly without changing
   versions.

Emergency publish (`emergency: true`, needs `config.admin`) skips approvals, evaluation and lint and schedules a 48 h
retro review (`decision.definition.emergency_published`). Use it for incidents and initial bootstraps, not for routine
changes.

## Calling a decision from code

```java
DecisionResult r = decisions.decide("return_risk", input, DecisionOptions.live("return", rmaId, actor));
if (r.acts()) { apply(r.value()); }                 // ACT (or L0 rule / cached)
else if (r.requiresHuman()) { attach(r.escalationId()); }   // recommendation only
else { applyDefault(r.value()); }                   // DEFAULT layer: details.defaultReason says why
```

`decide` never throws for provider failures. Every call persists a `decision_record` and a `decision.evaluated` ledger
event; `why(recordId)` renders the trace. For simulation against a specific version use
`DecisionRuntime.evaluate(def, input, DecisionOptions.simulation(...))` or `POST .../evaluate-now {version}`.

## Worked example 1: a VIP appeasement choice (T2)

`docs/examples/decisions/vip_appeasement.yaml` extends the demo `appeasement` decision with two L0 rules (a role
limit that escalates, a hard cap that blocks), a choice question whose options are amounts the finance team approved,
and a post-check that the chosen credit never exceeds the order total. Bands are the T2 defaults; the `default` is
`none` so a failed provider never grants money.

## Worked example 2: carrier service choice (T1)

`docs/examples/decisions/carrier_service_choice.yaml` picks a carrier service for a shipment from promise slack,
weight, hazmat and destination. L0 rules force `ground` for hazmat (policy, not judgement) and `same_day` only inside
the same-day radius; the choice question has the escape option `defer`; a post-check refuses `air_*` when
`input.shipment.hazmat`. It is a T1 decision because the cost impact is bounded by the rate card.

## Worked example 3: a pre-order line state model

Decisions and state models meet in guards: `docs/examples/state-models/order_line.preorder.yaml` shows a line model
whose `backordered` state has an SLA whose `onBreach` is `decision.backorder_next_action`, so the decision above
chooses `wait`, `resource`, `cancel_line` or `offer_substitute` when stock is late.

## Checklist before you submit

* Every choice has an escape option; every definition has a `default` that is safe when wrong.
* Tier matches the stakes: anything that moves money or affects a customer's refund is T2 or T3.
* Rules cover the policy that must never be a judgement call; the model covers the middle.
* `state.include` has nothing a human reviewer would not need; no protected attributes.
* A golden set exists with cases for every option and rule boundary; the impact diff was read, not just run.
* The change reason says *why* in business terms; the ticket links to the decision record.
