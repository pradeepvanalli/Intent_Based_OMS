# Self-Optimizing Order Orchestration — closing the loop from outcome to policy

## The business problem

Every policy in an OMS is written by a person and stays fixed until a person changes it: sourcing weights, split
rules, safety stock, decision thresholds, reservation TTLs. Networks, carriers, customers and fraud patterns drift
every week, so a fixed policy decays from the day it ships — and the data needed to correct it already sits in the
event ledger, the decision records and the human task queue. Nothing turned that data back into policy. Self-
Optimizing Order Orchestration (SOO) closes that loop: it measures every outcome, proposes better policy, proves
the proposal is actually better on replayed and live traffic, and promotes it within guardrails a tenant controls.

## What "learns by itself" means here

The LLM's weights never change in production. Learning happens in three bounded, versioned, reversible places:

1. **Parameters** — numbers the system tunes from outcomes: node reliability, lane transit times, promise
   calibration, safety stock, a sourcing-policy bandit's arm weights. Updated online, in minutes, no LLM involved.
2. **Knowledge** — typed, versioned facts about customers, segments, nodes and lanes, derived from outcomes ("this
   customer cluster tolerates splits," "this pickup node has high refused-delivery risk").
3. **Policy** — rule and threshold changes that a typesafe LLM drafts as a changeset. These pass the exact same
   lint, golden-set, impact-diff and approval gates an ordinary decision change already passes before going live.

Statistics decide whether a change is better; the LLM proposes and explains but never declares a win.

## The two loops

```
Decide → Log → Observe → Learn  (fast loop: parameters update in minutes, no LLM)
Hypothesize → Draft change → Evaluate → Promote  (slow loop: nightly, gated by replay + shadow + canary)
```

**Fast loop.** Every decision already made through the normal decision fabric gets an episode opened against it
(`EpisodeAssembler`), joined to whatever happened next — delivery, a return, a refund, a customer contact, a
stranded unit (`OutcomeObserver`). Once enough of an episode's outcome is known, its reward is computed from the
tenant's objective weights and the episode moves from provisional to mature as later outcomes (a return, a
chargeback) land and correct it. Parameter learners — node reliability, lane transit distributions, a contextual
bandit over the five sourcing policies, customer preference facts, fraud-threshold tuning — read this stream and
update typed, versioned facts, all without ever touching an LLM.

**Slow loop.** Nightly, an Analyst role reads aggregated outcome digests (never a single order, never raw PII — a
minimum cell size keeps it from overfitting to one case) and raises typed hypotheses. An Author role turns an open
hypothesis into a changeset — a threshold move, a new rule, a weight change — re-validated against the live
artifact before it's ever stored, never trusted as free text. A Critic reviews the changeset and the evidence
against it; its verdict is evidence for a human reviewer, never a pass/fail gate on its own.

## Proof before promotion

A changeset is a `Proposal`, and nothing about it is live until it clears, in order:

1. **Offline replay.** An inverse-propensity and doubly-robust estimator replays the proposal against logged
   history — every decision now logs which option it chose and the actual probability it was chosen with
   (`propensity`), which is what makes a fair replay possible at all. The result is a lift estimate with a 95%
   confidence interval, not a single number asserted as fact.
2. **Shadow.** The proposal runs in parallel on live traffic, logged, never acting.
3. **Canary.** A sticky, bounded traffic share actually sees the new policy, with a persistent holdout always kept
   on the old one so cumulative lift stays measurable against a true baseline, and an automatic rollback the moment
   a guardrail — override rate, false-positive rate, customer contact rate, decision latency — moves the wrong way.
4. **The promotion gate**, by risk tier: a T0 cosmetic change promotes automatically once replay and shadow pass; a
   T1 change promotes automatically only once its specific learner has been granted L3 autonomy, otherwise it waits
   for approval; T2 always needs a human approver who isn't the author; T3 is never autonomous, full stop. A
   postal-code-scoped change always needs approval regardless of tier, by rule.

Every proposal, evaluation, promotion and rollback is written to the event ledger with a payload digest, and every
promoted change has a one-click rollback.

## Guardrails that don't move

* **Autonomy is opt-in and per learner.** Every learner starts at L1 (recommend-only) in a fresh tenant. Nothing
  self-promotes until a tenant explicitly raises a specific learner's autonomy level.
* **Refunds, cancellations, pricing and autonomy-level changes themselves are never autonomous** — the same
  hard-excluded set the Agentic Control Tower already enforces, unchanged by anything SOO adds.
* **Change budgets.** A maximum number of promotions per learner per week, and a maximum single-step movement for
  any numeric change — a threshold cannot jump, only creep, and only within a cap a tenant sets.
* **Coverage and drift monitors pause learning automatically**, not just alert: if outcome data for a decision
  stops arriving (a webhook goes quiet) or a fraud-feature distribution shifts hard, the affected learners drop to
  observe-only until a person looks.
* **Tenant-isolated by construction.** One tenant's outcomes never train another tenant's facts; nothing here is
  shared across tenants without explicit, separate opt-in.
* **Kill switches at every level** — global, per-learner, per-tenant — drop instantly to the last-approved policy.

## What's live today versus a documented next step

This was built as a complete first implementation of the architecture above, not a finished product — treat the
following as an honest status, not a feature list:

* **Fully wired and tested**: episode/outcome capture, propensity logging on every decision, the node-reliability
  and sourcing-policy-bandit learners (with a seeded, replayable random draw), customer preference and fraud-
  threshold learners, the Analyst/Author/Critic pipeline, the off-policy evaluator with real confidence intervals,
  the tiered promotion gate, change budgets, coverage/drift auto-pause, and the Learning Console.
* **Recommend-only by design, not yet auto-applied**: safety-stock and reservation-TTL proposals, several fraud and
  decision-fabric tuning proposals — these generate real, evaluated `Proposal` rows for a human to act on rather
  than being wired to auto-promote, which is the conservative default this architecture is supposed to have.
* **Documented extension points, not yet built**: a weekly transfer-recommendation report, fact decay by recency,
  cross-tenant opt-in shared priors, a full sequential-testing early-stop for canaries (today's is a simpler z-score
  heuristic), and a published load-test benchmark for episode assembly at scale.

## Seeing it

Open the **Learning Console** (`/learning`) to see active learners and their autonomy level, the proposal queue
with each one's replay evidence and critic review, running experiments, and lift against the persistent holdout.
The REST surface is under `/api/v1/learning` — `GET /learners`, `PUT /learners/{id}/autonomy`, `GET /proposals`,
`POST /proposals/{id}/evaluate`, `POST /proposals/{id}/approve`, `GET /experiments`, `GET /objective`. Two read-only
MCP tools, `learning_status` and `explain_policy_change`, let an agent ask what's learning right now and why a
specific change was made — no MCP tool can approve or promote anything.
