# AGENTS.md — oms-learning (Self-Optimizing Order Orchestration)

Closes the loop from outcome back to policy. Read the root `AGENTS.md` first. Requirement → code → test map:
`docs/SOO_TRACEABILITY.md`. Mechanism and demo walkthrough: `docs/UC_SELF_OPTIMIZING_ORCHESTRATION.md`. This file
is what to know before touching this code.

## The mental model

Two loops. The **fast loop** (`.../learning/learner/`, plus learners in oms-inventory and oms-agent) turns episodes
and outcomes into typed facts and parameter proposals, no LLM. The **slow loop** is Analyst → Author → Critic in
`oms-copilot` (hypotheses, changesets, review) and the off-policy evaluator in `oms-simulation`. Nothing from
either loop changes live behaviour until it clears evaluation, approval where the tier needs it, and promotion.

## Five rules that keep it safe

1. **Every learner is governed.** Implement `GovernedLearner` with a `LearnerDescriptor` (plain-English name and
   one-line summary — they are shown to people — requirement ids, `readsOutcomesOf` for the decisions whose
   outcome coverage matters, `features` allowlist, `feeds` for the live families it can feed). Start every run with
   `LearnerGovernance.begin(KEY)` and stop when `mayRun()` is false; draft proposals or hypotheses only when
   `mayPropose()`. Event-driven learners call `recordRunThrottled`. `LearnerRegistry` registers the learner for every
   tenant at its default autonomy (L1).
2. **Live paths read learned values only through `ParameterResolver`.** A value is served only after a promoted
   `ParameterChange` on `parameter:live:<family>` (created through `POST /learning/live-parameters/proposals`), within
   its range and max step, and only while the learner is on, unpaused and above L0. Otherwise the static value is
   served. Never read `LearnedFactRepository` directly from sourcing, promise, inventory or monitoring code; use
   `LearnedFactCache` + `ParameterResolver`.
3. **Learners propose, the gate decides.** A learner may write facts and proposals. It never calls
   `DecisionStudioService.publish/edit/createDraft`, never changes an agent's trust tier, and never bypasses
   `PromotionGuard` (autonomy × tier, change budgets, freeze windows, never-autonomous changes such as refunds,
   cancellations, pricing, objective and autonomy). `PromotionService` applies every change type and keeps a rollback
   snapshot.
4. **Propensity is the probability of the logged arm.** The generic runtime logs the main question's options as
   `arms` with propensity 1.0. Anything that samples (see `SourcingPolicyBandit`) logs the arm actually applied with
   its true draw probability, keeps a sampled-but-unused arm in `details.shadow`, seeds the draw once and stores the
   seed, and offers a static `replay(...)`.
5. **One episode per decision point.** `EpisodeAssembler` keys episodes by decision, subject, line and fulfilment
   group (`DecisionPoints`). A scorer that re-evaluates the same order must not open new episodes; it moves
   `last_decision_id`.

## Extension points (add a bean, don't edit a shared file)

| You want to… | Add |
|---|---|
| Turn a new ledger event into an outcome | an `OutcomeMapper` bean (see `CoreOutcomeMapper`) |
| Put learned facts into a decision's input | a `FeatureContributor` bean (see `NodeReliabilityFeatures`); list every fact used |
| Calibrate confidence / route a provider / route an experiment | a `ConfidenceCalibration` / `ProviderRouter` / `ExperimentRouter` bean |
| Add a learner | a `GovernedLearner` bean (batch learners also return `runnable() = true` and implement `runNow()`) |
| Add a fact type | a `FactKey` value with its `TypedValue` shape; write it with versioning (`FulfilmentFacts` shows how) |

All four hook interfaces are called only for LIVE and SHADOW evaluations; replay, simulation and golden-set runs
never see them, so a recorded decision replays from its recorded input.

## Dependency direction

`oms-learning` depends only on `platform`/`decision`/`workflow`. Sourcing, inventory, monitoring, orchestration,
copilot, simulation, agent and app depend on it. If you want to import one of those here, the code belongs in that
module instead.

## Testing

- `TenantIsolationTest` for any new table or query path.
- `EpisodePipelineIntegrationTest` for episode/outcome/reward changes.
- `PromotionGuardTest` for any gate rule; `LiveUseFlowIntegrationTest` for live use.
- `SourcingPolicyBanditPropensityTest` / `...DeterminismTest` for any sampling learner.
- The demo tenant's reference data comes from `oms-app/.../glue/soo/SooDemoSeeder` (actor `system:demo-seed`);
  guardrail monitors ignore it. The Playwright suite (`make e2e`) drives the Learning console end to end.
- The shared `test` tenant is reused across test classes in one run; reset any state your test asserts on first.
