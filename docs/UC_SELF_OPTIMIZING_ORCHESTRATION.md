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

## What's live today, and what is not

An earlier version of this section called the first build complete. It was not: a strict audit found 6 of the 63
functional requirements done and runtime bugs in what existed. The work since then fixed the bugs and built the
rest. The status of every requirement ID, with the code that implements it and the test or live check that proves
it, is in **`docs/SOO_TRACEABILITY.md`**; that file, not this section, is the source of truth.

In short, live in the running app:

* **Outcome loop.** One episode per decision point, typed outcomes from real ledger events (deliveries, shipments,
  refunds, returns, chargebacks via the Stripe webhook, customer contacts, stranded stock), rewards for all seven
  terms with per-term maturity, and coverage monitoring that pauses only the learners whose data went quiet.
* **Fast loop.** 26 learners across fulfilment and promise, fraud, inventory, customers and decision quality, every
  one governed (on/off, pause, autonomy) and listed in the Learning console in plain words.
* **Live use under control.** A learned value reaches a live decision only after a promoted change that bounds it
  (range, max step) and only while its learner is on; switching it off takes effect at once.
* **Proof before promotion.** Replay evaluation with confidence intervals for decision changes, shadow runs, sticky
  canaries with a sequential test, a persistent holdout and a lift report; every change type can be applied and
  rolled back exactly.
* **Governance and explainability.** The autonomy × tier gate, never-autonomous changes (refunds, cancellations,
  pricing, objective, autonomy), change budgets, freeze windows, a ledger event for every governance step, change
  cards, a weekly digest, and the Why drawer showing which learned facts and versions a decision used.

Known limits, stated rather than hidden:

* **Acceptance criteria AC2/AC3** (holdout lift with a confidence interval above zero; no guardrail worse than
  baseline over four weeks) need weeks of real traffic. The mechanism runs end to end on the demo tenant's seeded,
  labelled history; that is a demonstration, not a measured result.
* **Holdout routing** covers promoted decision changes; config and live-parameter changes are measured by shadow runs
  and bounded promotion instead.
* **Replay evaluation** is counterfactual for decision changes; for config and parameter changes there is no
  recorded data for the alternative value, so the report is an observational comparison and a person decides.
* **Storefront conversion (CX-02) and chargebacks** need a real storefront and Stripe account; the ingestion
  endpoints exist and are exercised with test traffic.
* **NF-08** is a local k6 run on one machine (`docs/perf/`), not a production-sized load test.

## Seeing it

Start the app with the demo tenant (`make try` or `scripts/bootstrap.sh`); the demo seeder fills about 135 days of
labelled history, so the **Learning console** (`/learning`) is populated on first login: Overview (is learning
helping, what changed, what needs you, outcome coverage), Learners, Changes (each with its change card), Experiments
and Settings (objective, test-case candidates, fairness, weekly summaries). Open any order's decision and its **Why**
drawer shows how learning shaped it. `scripts/usecases/uc8-self-optimizing.sh` drives the whole loop on real orders
against a running app, and `make e2e` runs the Playwright suite that covers it.

The REST surface is under `/api/v1/learning` (listed in `docs/ARCHITECTURE.md` §3.14). Two read-only MCP tools,
`learning_status` and `explain_policy_change`, let an agent ask what is learning and why a change was made; no MCP
tool can approve or promote anything.
