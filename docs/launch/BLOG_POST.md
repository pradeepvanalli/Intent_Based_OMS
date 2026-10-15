# Intent OMS: an order management system built on typed decisions

*Launch article. 1,200 to 1,600 words. Publish on the blog of your choice and link from the LinkedIn announcement.
Replace `REPO` with the repository URL.*

---

Order management looks like a data problem and is actually a decision problem. An order arrives. Something has to
decide which node ships it, whether to split it, whether the address is safe, whether the return deserves an instant
refund, whether the instruction from a shopping agent is a purchase or an attack. A mid-market retailer makes those
calls thousands of times a day, and every one of them moves money or touches a customer.

I spent the last stretch building an open-source OMS where those calls are the product. It is called Intent OMS. It is
Apache-2.0, it runs on Java 21 and PostgreSQL, and you can have it on a laptop in three minutes. This article explains
what is wrong with the conventional shape, what the decision fabric is, and walks through one scenario with the actual
trace.

## The problem with the conventional OMS

In a conventional OMS, decisions live in a rules engine configured at implementation time by an integrator. "Ship from
the nearest node." "Split if any line is out of stock." "Refund on scan." These rules are fine until they are not, and
they are not fine often.

The rules cannot read. When a customer writes "birthday gift for my dad, please send everything together in one box"
and the cap is only in stock in New Jersey, the rule splits the order into two boxes. One split costs the retailer
somewhere between $10 and $18 in carrier spend. Multiply by a holiday.

The rules cannot change quickly. A policy change is a ticket, a test cycle and a release. Nobody can show what would
have flipped last month under the new rule, so nobody is confident enough to approve it.

The rules cannot explain. The "explain" features that have appeared in the last two years narrate the rules engine
after the fact. The customer gets a status word.

And the rules do not know what to do when they are not sure. Anything not covered lands in a queue, and the queue
grows until someone adds another rule.

The first wave of "AI in the OMS" answered this with a chat assistant on top of the same engine. That is the wrong
layer. A system of record should not take free-text actions from a model. It should ask the model typed questions.

## The decision fabric

Every decision in Intent OMS is a YAML object. It has a name, a risk tier, a list of questions, enumerated options for
each, an escape option, deterministic rules, a default, and a golden set of labelled cases. Decisions are versioned,
linted, simulated against history, gated, approved, rolled out as canaries, replayable and explainable.

At runtime a decision passes through four layers.

**L0: rules.** CEL expressions evaluated first, in priority order. Policy that must never be a judgement call lives
here. A flagged customer gets store credit by rule. Hazmat does not fly by rule.

**L1: a typed decision model.** The model is asked the question and can only answer with one of the enumerated
options. It also returns a confidence. The default provider is Jev (TypeSafe System One) through OpenRouter; in the
demo it answers in 350 to 850 ms at about $0.00002 per call. The provider is a Spring bean, so any model with
structured output can sit behind the same interface.

**L2: an LLM, strict JSON only.** When the confidence lands in the confirm band on a T1 or T2 decision, a second model
is shown the trace and the same enumerated options and asked to pick one. Anything that is not a valid option is
discarded.

**L3: a person.** Below the confirm band, or on any T3 decision, a task opens in a queue with the recommendation
attached. Escalation is a first-class outcome with an SLA, not an exception path.

The risk tier sets the bands. A T2 decision that moves money acts only at confidence 0.9 or above. A T3 decision never
acts. Thresholds are changed by humans in the studio and by nobody else.

Kill switches drop any decision, any provider or the whole AI layer to its deterministic default instantly. With no AI
key at all, the system still captures, sources, ships and returns orders. You see more tasks; you do not see a stall.

## One scenario, with the trace

The first scripted scenario is called "the gift that must arrive together". Two identical baskets (a hoodie, running
shoes and a cap) ship to Seattle. The cap is only stocked in New Jersey. Buyer A writes "birthday gift for my dad,
please send everything together in one box". Buyer B writes "I need the shoes before Friday's race, the rest can come
whenever".

The note is an input to the `fulfilment_intent` decision, tier T1. For buyer B the stored trace reads:

```
decision fulfilment_intent v1 tier T1 | layer L1_SYSTEM_ONE | band ACT | action act | value earliest_partial | confidence 1.0 | provider jev
decision sourcing_node_score v1 tier T1 | layer L1_SYSTEM_ONE | band CONFIRM | action confirm | value DC-NV | confidence 0.73 | provider jev
```

Read the first line left to right. The decision and its version. The tier. The layer that answered: L1, the typed
model. The band: act. The value: `earliest_partial`, which is one of three enumerated options. The confidence. The
provider. For buyer A the value is `ship_complete` at the same confidence, and the order leaves New Jersey as one box,
cross-country, because that is what the buyer asked for.

The second line is the node score for the sourcing plan. The confidence of 0.73 is below the T1 act band, so the band
is confirm, and the reason code `confirm_default_applied` tells you the default policy was used rather than a model
verdict. Nothing is hidden about why the plan came out the way it did.

Every plan the planner considered is stored with cost, margin, ETA and score:

```
  ship_as_available    packages 1 | cost $19.05 | margin 50.7% | latest ETA 2026-10-11 | score 0.803
✔ ship_complete        packages 1 | cost $19.05 | margin 50.7% | latest ETA 2026-10-11 | score 0.803
  earliest_partial     packages 2 | cost $41.75 | margin 41.5% | latest ETA 2026-10-11 | score 0.412
```

The same scenario has a third order: two 20 kg dumbbell sets to Honolulu, which no plan can ship at a profit. The
margin comes out at minus 2.4% against a 12% floor, so the workflow parks on a `margin_floor_approvals` task carrying
the economics and the alternatives. The finance controller approves with a reason, the workflow resumes, and the KPI
"margin-floor compliance" counts it. Nothing ships below the floor without a recorded approval.

Operators read all of this in the Why drawer. Customers read a safe sentence derived from the same trace on the public
tracking page.

## Policy from a sentence

The studio is where decisions change. A draft goes through lint, an impact diff over the last 30 days of real inputs,
a golden-set evaluation gate, submission with a reason, approval by a different person from the author, a canary, and
promotion to live. Rollback is one click. In the demo run, a change to `fulfilment_intent` flipped 6 of 33 historical
decisions and passed its golden set with a calibration error of 0.012.

The copilot sits in front of the studio. Type "Gold customers with a receipt get an instant refund for returns under
$250. Never split an order that contains hazmat items." and you get a change set: one card per change, a unified diff,
generated golden cases, the risk tier and the approvals required. Staging turns it into drafts. The model never
publishes.

## Margin replay

The quickest way to find out whether any of this would pay for itself is to try it on your own orders. Export them from
Shopify as CSV, upload the file at `/replay`, and read the report: shipping saved, margin recovered, splits avoided
under the policies you choose. A bundled sample runs without an upload. Everything runs locally.

The same replay engine powers the studio's impact diff and the what-if simulator, which is how scenario 3 takes a
distribution centre offline over last week's orders before anyone touches production.

## What else is in the box

Shopping agents place orders over MCP (11 tools) and UCP, with signed mandates, limits enforced before capture,
instruction text screened and stored as data, and a one-click dispute evidence pack. Returns run through one flow across
channels with risk-scored refund timing and keep-it. A durable Postgres workflow engine handles retries, timers,
signals, saga compensation and human task queues. Statecharts are YAML with quantity-level status. Every state change
appends to an append-only ledger in the same transaction, and any order can be rebuilt from it.

Three reference tenants ship with the release: a general merchandise store with BOPIS and hazmat, an electronics
retailer with pre-orders and warranty triage, and an apparel brand with bracketing and size exchanges. Each has five
scripted scenarios.

## How to try it

```bash
git clone REPO && cd intent-oms
make try
```

Or open it in GitHub Codespaces, or build from source with `scripts/bootstrap.sh`. Open `/playground`, place an order,
and watch the trace animate. Add an OpenRouter key to switch the typed layer on; without one, everything runs on rules
and defaults.

The threat model, compliance mapping and known gaps are published alongside the code. It is new software. Judge it by
reading it.

If you are building a new OMS, I want to hear what you are planning. The repository is at REPO; my LinkedIn is linked
from the README.
