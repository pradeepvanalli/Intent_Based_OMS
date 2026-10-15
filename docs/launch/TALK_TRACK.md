# Live demo talk track (10 minutes)

For a call with a company that is building or replacing an OMS. Assumes a running instance with
`OPENROUTER_API_KEY` set and the `retail` reference tenant loaded (`scripts/reference/retail.sh --load-only`). Have a
second browser tab logged in as `finance` for the margin-floor approval.

Goal of the call: a second meeting with the people who own order flow, and one real order export to replay.

## 0:00 Framing (1 minute)

"Order management is a decision problem. Which node, split or not, refund now or review, is this agent allowed to buy
this. Conventional systems put those decisions in rule tables owned by the integrator. I built an OMS where each
decision is a typed, versioned object the business owns, with a model answering inside enumerated options and a person
stepping in when the confidence says so. Ten minutes, four things, then your questions."

## 1:00 Playground: a decision you can watch (2.5 minutes)

Open `/playground`. Place the gift order with the note "birthday gift for my dad, send everything together".

Talk while the trace animates: "Rules first. Nothing fires. Then the typed layer: `ship_complete`, confidence 1.0,
under a second, two thousandths of a cent. The plan is one box from New Jersey even though it is cross-country, and
every plan considered is listed with cost, margin and ETA."

Place the second order with "shoes before Friday's race, the rest whenever". "Same basket, different sentence, two
packages. Nobody wrote a rule for that."

Open the Why drawer on the order. Read the trace line out loud: "`value ship_complete | layer L1_SYSTEM_ONE | band ACT |
provider jev`. This is the thing I would ask you to compare with your current system: can it show you this?"

Toggle to the customer-safe explanation. "Same trace, safe sentence, on the public tracking page."

## 3:30 Margin floor: a decision a person makes (1.5 minutes)

Place the Honolulu dumbbell order. "No plan ships this at a profit. Margin minus 2.4% against a 12% floor. The
workflow does not guess; it parks on a finance task with the economics and the alternatives." Switch to the finance tab,
show the task, approve with a reason. "The order releases, the approval is in the ledger, the KPI counts it."

## 5:00 Copilot and studio: policy from a sentence (2.5 minutes)

Open `/copilot`. Type: "Never split an order that contains hazmat items. Marketplace orders below a 10 percent margin
need finance approval."

While it generates: "This is the part people assume is dangerous, so here is what the model is allowed to do: produce a
draft. Nothing else."

Show the change set: cards, diffs, generated golden cases, risk tier, approvals required. Stage it. Open
`/studio/split_acceptability`. Run Impact: "This is the proposed version replayed over the last 30 days of real
decisions. These are the orders that would have flipped." Run Evaluate: "Golden set gate." Submit: "I cannot approve my
own change. Someone else does. Then a 25% canary, then live, rollback is one click, and there is a kill switch that
drops the decision to its deterministic default."

## 7:30 Margin replay: their orders (1 minute)

Open `/replay`, run the bundled sample. "This is where I would like to go next with you. Export your orders from
Shopify or your current system, upload them here, and read what intent-aware sourcing, keep-it returns and a
profitability floor would have done to last quarter. It runs locally."

## 8:30 What it is and is not (1 minute)

"Java 21, Spring Boot, Postgres, one deployable. Apache-2.0. Three reference tenants with five scenarios each. 541
backend tests. Runs with no AI key. It is new, so the threat model, compliance mapping and gap list are published; I
would rather you read them than take my word."

## 9:30 Ask (30 seconds)

"Two things. One real order export so we can run the replay together. And a conversation with whoever owns the order
flow about what they would want a decision to look like. I am looking for a team building a new OMS to work with."

## Objections and answers

**"We cannot let an LLM make decisions that move money."**
Neither can this system. Money-moving decisions are tier T2 or T3. T2 acts only at confidence 0.9 and above and
otherwise opens a task. T3 never acts; it recommends. The rules that must never be a judgement call are L0 and run
before any model. Show the tier table in `docs/DECISIONS_GUIDE.md`.

**"Who changes the thresholds?"**
Humans, in the studio, with approvals. Never the copilot, never an agent. It is a hard guardrail (GR-14).

**"What happens when the model provider is down or we run out of budget?"**
Each decision declares a fallback provider and a default. The system keeps capturing, sourcing, shipping and
returning. You see more tasks. There is a per-tenant daily AI budget and kill switches per decision, per provider and
for the whole layer. Run the demo with no key to prove it.

**"How do we know the model is right?"**
Every version is gated by a labelled golden set with accuracy and calibration thresholds. A weekly drift monitor
re-checks calibration against outcomes. Every decision record stores its confidence so you can audit it after the
fact. Bring your own labelled cases; they are the most valuable thing you can contribute.

**"Is it production ready?"**
It is new software with a published threat model, compliance mapping and known gaps. The platform has OIDC, RBAC, PII
tokenisation, an append-only ledger enforced in the database, and a production profile that refuses to start with
insecure settings. I would stage it on a channel or a region first, in shadow mode, and compare decisions before
switching anything live.

**"We already have a rules engine / a WMS / an ERP."**
Keep them. The OMS consumes prices and inventory and talks to the WMS through a signed release document. Rules you
trust become L0 rules. Shadow mode lets you run a decision alongside your existing logic and compare before acting.

**"Can we use our own model?"**
Yes. A provider is a Spring bean. Anthropic, OpenAI-compatible endpoints and OpenRouter ship; a custom provider is one
class (`docs/examples/extensions/CustomDecisionProvider.java`).

**"What about PII going to a model?"**
Names, e-mails, phones and addresses are tokenised per tenant before any provider call. Rotating the key makes
historical tokens unlinkable.

**"What does it cost to run?"**
PostgreSQL and one JVM. The typed layer measured at about $0.00002 per call in the demo; the copilot is the expensive
part at roughly $0.15 per change set, and it is used a few times a day, not per order. The AI economics page shows cost
per thousand decisions live.

**"What is missing?"**
EDI, parallel statechart regions, a visual designer, SCIM, multi-region active-active. `docs/ROADMAP.md` has the next
two quarters. If one of those is a blocker for you, say so; it changes the order.
