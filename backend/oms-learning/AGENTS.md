# AGENTS.md — oms-learning (Self-Optimizing Order Orchestration)

Closes the loop from outcome back to policy. Read the root `AGENTS.md` first. Full signatures and the honest
gap list: `docs/MODULE_NOTES.md` § Self-Optimizing Order Orchestration. Mechanism and demo walkthrough:
`docs/UC_SELF_OPTIMIZING_ORCHESTRATION.md`. This file is what to know before touching this code specifically.

## The mental model

Two loops. The **fast loop** (`.../learning/learner/`) reads episodes and outcomes and updates typed parameters and
facts in minutes, no LLM — node reliability, lane transit times, a contextual bandit over the five sourcing
policies, customer preference facts, fraud thresholds. The **slow loop** is an Analyst (extends `oms-copilot`,
nightly) that raises hypotheses from aggregated outcome digests, an Author (extends `oms-copilot`) that drafts
policy changesets, an Evaluator (extends `oms-simulation`) that replays a changeset against logged history with a
confidence interval, and a Critic that reviews the evidence. Nothing from either loop is live until it clears
replay → shadow/canary → the tiered promotion gate (`PromotionGuard`, pure and unit-tested — read it before
changing any tier/autonomy logic, it's the single place that decision lives).

## Dependency direction — don't get this backwards

`oms-learning` depends only on `platform`/`decision`/`workflow`. It never depends on `sourcing`, `inventory`,
`copilot`, `simulation`, `orchestration` or `agent` — those modules depend on *it*. If you're tempted to add an
import from `oms-learning` into any of those, the code almost certainly belongs in the other module's own package
instead (this is why `SourcingPolicyBandit` lives here but is *called from* `oms-sourcing`, and why
`AgentReputationLearner` lives in `oms-agent`, not here, even though conceptually it's a learner).

## The propensity rule

Every decision logs `propensity` on its `decision_record` row (added by this module's `V401` migration in
`oms-decision`). For an ordinary decision this defaults to `1.0` — a deterministic choice needs no correction. The
*only* place that should ever log a genuinely sampled, non-1.0 propensity is something that actually explores among
several arms with real randomness, like `SourcingPolicyBandit`. If you add a new learner that samples, follow its
exact pattern: seed the random draw once, persist the seed in `decision_record.details.randomSeed`, and provide a
static `replay(...)` method that reproduces the choice from that seed — this is what makes NF-04 (determinism) hold
and what the off-policy evaluator depends on to be statistically honest.

## What a learner may and may not do

A learner may write `LearnedFact` rows (via `LearnedFactRepository`) and `Proposal` rows (via `ProposalRepository`)
freely — those are recommendations. A learner may **never** call `DecisionStudioService.publish()`, `.edit()`, or
`.createDraft()` directly, never call `AgentRegistry.update()` to change a trust tier, and never bypass
`PromotionGuard`. If your learner wants a decision or a config value to actually change, it raises a `Proposal` and
stops — the promotion gate decides, under governance, whether and when that becomes real.

## Before you add a new outcome source

`OutcomeObserver` maps real ledger event types to the typed `OutcomeData` catalog. If the event you need doesn't
exist yet (chargeback and stranded-inventory signals don't, as of this writing), the fix is to add the field to the
*producing* event's payload at its source (see how `StandardShipOrderWorkflow.deliver()` computes `onTime` against
`FulfilmentGroup.promiseDate()` before appending `order.delivered`) — not to guess at it in the observer. Check
`docs/MODULE_NOTES.md`'s gap list before assuming a signal is missing; it might just not be wired into the reward
yet (`RewardCalculator.components()` is the pure function to extend for that, not the formula itself).

## Testing

`TenantIsolationTest` is the one to extend if you add a new table or query path — every new piece of this module
needs to prove one tenant's data never leaks into another's. `SourcingPolicyBanditDeterminismTest` is the pattern
for any new sampling learner. Scoped `mvn -pl oms-learning test` runs against whatever Postgres `PostgresTestBase`
resolves — see the root `AGENTS.md`'s note on the local dev database's Flyway history if you hit a validation error
after running many scoped test passes.
