# LinkedIn posts

One announcement post, a five-post series for the following two weeks, three alternative hooks and a reply template.
Every post is under 1,300 characters so it reads without "see more" on most feeds. Replace `REPO` with the final
repository URL and `LINKEDIN` with your profile URL before posting. Attach the screenshot named under each post.

Posting guidance is in `LAUNCH_CHECKLIST.md`.

---

## Announcement

Image: `docs/images/hero-playground.png`

```text
I open-sourced an order management system where every decision is a typed object with a confidence score.

Not a chatbot bolted onto a rules engine. The OMS itself.

Sourcing, promising, returns, exceptions and agent-placed orders all run on one decision fabric:
L0 deterministic rules
L1 a typed decision model that can only answer within enumerated options
L2 an LLM restricted to strict JSON
L3 a person, whenever the confidence band or the risk tier says so

In the demo a typed decision answers in 350 to 850 ms at about $0.00002 per call.

What you can do with it today:
- Place an order in the Playground and watch the trace animate, layer by layer
- Change a policy from a sentence. The copilot drafts, the studio gates it, a human publishes
- Upload a Shopify export and replay your own orders against the policies
- Accept orders from shopping agents over MCP and UCP, with signed mandates and evidence packs

Java 21, Spring Boot, Postgres. Apache-2.0. Three reference tenants (general merchandise, electronics, apparel). Runs with no AI key at all.

Try it in three minutes: REPO

If your team is building a new OMS, I want to talk. Message me here.

#ordermanagement #retailtech #opensource #ecommerce #supplychain
```

---

## Series post 1: the gift that must arrive together

Image: `docs/images/decision-trace.png`

```text
A customer wrote "birthday gift for my dad, please send everything together in one box."

Most order management systems cannot read that sentence. The cap was only in stock in New Jersey, so the order splits into two boxes and the retailer pays for both.

In Intent OMS the note is an input to a typed decision called fulfilment_intent. The trace from the live run:

value ship_complete | layer L1_SYSTEM_ONE | band ACT | confidence 1.0 | provider jev

One box from New Jersey, cross-country, because that is what the buyer asked for. The other buyer on the same day wrote "shoes before Friday's race, the rest whenever." Same decision, answer earliest_partial, two packages.

Every plan the planner considered is stored with cost, margin and ETA. The one that shipped is marked. The Why drawer shows the rule that fired, the band and the provider.

This is scenario 1 of 7 in the repo. It runs on your laptop in three minutes, with or without an AI key.

REPO

Building an OMS and want to see this against your own orders? Message me.

#ordermanagement #ecommerce #opensource #retailtech
```

---

## Series post 2: policy from a sentence

Image: `docs/images/copilot-changeset.png`

```text
"Gold customers with a receipt get an instant refund for returns under $250. Never split an order that contains hazmat items."

Type that into the copilot in Intent OMS and you get back a change set: one card per change, a unified diff, generated test cases, the risk tier and the number of approvals it needs.

Then the part that matters. The model never publishes.

The draft goes through lint, an impact diff on the last 30 days of real decisions, a golden-set gate, approval by someone other than the author, a 25% canary and one-click rollback. In the demo run the impact diff flipped 6 of 33 historical decisions and the golden set passed with a calibration error of 0.012.

A policy change before lunch, with every step in the audit log, and nobody trusted a model with the keys.

Open source, Apache-2.0: REPO

If you are building an OMS and your policies live in implementer-owned rule tables, I would like to show you this.

#ordermanagement #retailtech #opensource #ai
```

---

## Series post 3: margin replay of your own orders

Image: `docs/images/margin-replay.png`

```text
Here is the fastest way to find out whether a smarter OMS would pay for itself.

Export your orders from Shopify as CSV. Upload the file at /replay in Intent OMS. Read the report: shipping saved, margin recovered, splits avoided, under the policies you choose.

No integration. No project. Your own last quarter, replayed against intent-aware sourcing, keep-it returns and a profitability floor.

A bundled sample runs without any upload, so you can see the shape of the report before you share a byte of data. Everything runs locally; nothing leaves your machine unless you point it at a model provider.

The same replay engine is what the policy studio uses to show you what a change would have flipped before you publish it.

Open source: REPO

If you run order management for a retailer and want to try this on a real export, message me. I will walk you through it.

#ecommerce #ordermanagement #margin #opensource #retailtech
```

---

## Series post 4: agent commerce with proof

Image: `ui/screenshots/14-agents.png` (or a fresh capture of the agents page)

```text
Shopping agents are placing orders now. Who is liable when one of them is wrong?

Today the merchant is. The chargeback arrives and there is no record of what the buyer actually delegated.

Intent OMS treats agent orders as a decision problem, not a connector problem:
- Agents are registered with a trust tier and limits
- Every order carries a signed mandate; an over-limit mandate is refused before capture
- The agent's instruction text is stored as data and screened. In the demo, an injected instruction scored 0.64 on mandate match, below the act band, so the order was held and a risk task opened
- One click produces a dispute evidence pack
- The same order works over MCP (11 tools) and UCP

Scenario 4 of 7 in the repo, runs on a laptop.

REPO

Building the agent channel into a new OMS? That is exactly the conversation I want to have.

#agenticcommerce #ordermanagement #mcp #opensource
```

---

## Series post 5: who it is for and how to get involved

Image: `ui/screenshots/02-control-tower.png`

```text
Two weeks ago I open-sourced Intent OMS. Here is who it is for and what I am looking for.

Mid-market retailers replacing a spreadsheet-and-ERP flow, or a first OMS whose rules nobody dares touch. Start from one of three reference tenants: general merchandise with BOPIS and hazmat, electronics with pre-orders and warranty triage, apparel with bracketing and size exchanges. Each has five scripted scenarios.

OMS teams building a new platform who want a typed decision layer, a policy studio and an event ledger they do not have to design from scratch.

Agencies who want configuration to be versioned data they can hand over.

What I want: feedback from people who run order management, labelled cases for the golden sets, and conversations with teams building something new. The roadmap for the next two quarters is public and has help-wanted labels.

570 backend tests, Apache-2.0, runs with no AI key.

REPO
LINKEDIN

#ordermanagement #opensource #retailtech #supplychain
```

---

## Alternative hooks

Swap for the first line of the announcement or any series post.

1. "Your OMS makes ten thousand decisions a day and cannot explain one of them. I built one that can."
2. "One split shipment costs a retailer $10 to $18. The sentence that would have prevented it was in the order notes."
3. "I let a language model into an order management system. Here is everything it is not allowed to do."

## Comment-reply template

Use for comments that ask "how is this different from X", "is it production ready" or "can I see it".

```text
Thanks, [name]. Short version: every decision is a YAML object with enumerated options, a risk tier and a confidence band, so the model can only answer inside the options and a person steps in when the band says so. The model never publishes configuration and never moves money. It is new, so I publish the threat model and the gaps rather than claim production readiness. Fastest way to judge it is the Playground or a margin replay of your own export: REPO. Happy to walk you through it on a call.
```

Replies to "we are building an OMS":

```text
That is exactly who I built this for. The reference implementations and the one-pager (docs/launch/PITCH_ONE_PAGER.md) show what adopting it looks like. I would like to hear what you are planning; message me and we can set up 30 minutes.
```
