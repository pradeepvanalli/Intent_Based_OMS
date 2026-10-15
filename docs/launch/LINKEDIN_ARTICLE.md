# LinkedIn article: launch of Intent OMS

*Long-form LinkedIn Article (not a short post — use LinkedIn's "Write article" option). Ready to paste as plain
text; bold the section headers yourself with LinkedIn's toolbar if you want them to stand out, the copy reads fine
either way. Attach `docs/images/hero-playground.png` or `docs/images/control-tower-agentic.png` as the cover image.
Fill in the one `[ ... ]` placeholder (your name) before posting. Replace "Intent OMS" with "Intent-Based OMS" in the
title/intro if you prefer that phrasing — the product itself is branded "Intent OMS" throughout the repo.*

---

## Title

Intent OMS: an order management system built to still make sense in five years

## Subtitle (optional, LinkedIn supports one under the title)

Why I built an intent-based OMS on typed AI decisions instead of a chatbot bolted onto a rules engine — and open-sourced it.

---

## Body

Every order management system eventually hits the same wall. A rule was written three years ago by an integrator who
has since left. Nobody remembers why it says "split if any line is backordered." Changing it means a ticket, a test
cycle, and a release. So nobody changes it, and the system keeps making the same mistake at scale, every day, forever.

That's the part people don't say out loud about OMS software: it isn't that the software is old. It's that the
*decisions* inside it are frozen the moment they're written, while the business around them keeps moving. Add AI to a
system like that the way most vendors are doing it right now — a chat assistant layered on top of the same rigid
engine — and you haven't fixed the problem. You've added a layer that can't be audited, can't be rolled back cleanly,
and can silently drift as the underlying model changes. That's not future-proof. That's a new kind of fragile.

I spent the last few weeks building the OMS I think should exist instead, and I open-sourced it: **Intent OMS**,
Apache-2.0, on GitHub now.

**The idea: make every decision a typed object, not a black box**

Here's the core bet the whole system is built on. Sourcing a node, splitting a shipment, scoring a return's fraud
risk, deciding whether a shopping agent's order is legitimate — these aren't facts to compute, they're judgment
calls. So treat them that way, explicitly, as first-class objects with a name, a risk tier, a list of allowed
answers, and a confidence score. Never let a model write free text into a system of record. Ask it a typed question
and make it answer inside the options you gave it.

Every decision in Intent OMS runs through four layers, in order:

**L0 — deterministic rules.** Anything that must never be a judgment call lives here as a CEL expression. Hazmat
doesn't fly, by rule. A flagged customer gets store credit, by rule. No model ever touches these.

**L1 — a typed decision model.** This is where Jev comes in — TypeSafe's System One model, built specifically for
this kind of typed, calibrated answer. It gets the question and can only respond with one of the enumerated options,
plus a confidence score. In the live demo it answers in 350–850ms through OpenRouter at roughly $0.00002 a call.
Because the interface is just "typed question in, typed answer with confidence out," any model with structured output
can sit behind it — this is the part of the design that's actually future-proof. When a better typed-decision model
exists in eighteen months, it's a provider swap, not a rewrite.

**L2 — an LLM, strict JSON only.** When confidence lands in the "not sure yet" band, a second model sees the same
enumerated options and the trace so far, and has to pick one. Anything outside the valid options is discarded
outright.

**L3 — a person.** Below the confidence threshold for a decision's risk tier, or on anything that moves real money, it
stops being automatic. A task opens with the model's recommendation already attached, so the person isn't starting
cold.

The risk tier sets the bar. A decision that touches money only acts automatically above 90% confidence. A decision
that's genuinely irreversible never acts automatically at all, no matter how confident the model is. And there's a
kill switch at every level — one provider, one decision, or the whole AI layer — that drops instantly to a safe
deterministic default. Pull the AI key out entirely and the system still runs: more tasks go to people, nothing
stalls.

**The use case I'm proudest of: an Agentic Control Tower that fixes things on its own**

This is the newest piece, and it's the one I'd point to if you only read one paragraph of this post.

Every ops team has lived this: an order goes quiet. Not failed, not cancelled — just stuck. Is it the payment? The
inventory position? A bad address? A carrier that lost the scan? A backed-up fulfillment node? Today, a person opens
four different screens and manually cross-references all of it before they even know what to try.

I built a typed decision — `stuck_order_diagnosis` — that looks at all of those signals together and names the single
most likely cause, with a confidence score. This is a genuinely hard problem for a conventional rules engine, because
rules can only check one condition at a time; they can't weigh five partial, overlapping signals against each other
the way a person would. A typed model can.

Here's the part that matters most: when the diagnosis is confident *and* it's within guardrails the business
explicitly set — a daily cap on autonomous actions, a dollar-impact cap per action, an allow-list of what it's even
allowed to attempt — the system fixes the order itself. Re-sources it, re-promises it, retries a declined payment,
confirms an unverified address. Every action is logged and most are reversible with one click. Refunds and
cancellations are never autonomous, by hard rule, regardless of how the guardrails are configured — some things should
always need a human.

And when it isn't confident, or a guardrail says no? It doesn't pretend. It escalates to a person with the full
diagnosis attached, and it says exactly why it didn't act — "estimated impact exceeds the per-action cap," not a
shrug. I watched this run live on seeded demo data: one order got an address-confirmation email sent automatically,
and three others escalated for three different, fully explainable reasons. That honesty — showing you *why* it didn't
act, not just that it didn't — is the difference between autonomy you can trust in production and autonomy you have
to babysit.

**Other things in the box that I think matter for where this category is heading**

*Policy from a sentence.* Type "gold customers with a receipt get an instant refund under $250" into the copilot and
you get back a reviewable change set — a diff, generated test cases, the risk tier, the approvals it needs. The model
drafts; a person publishes. Always.

*Margin replay of your own data.* Export your orders from Shopify as CSV, upload it, and see what this system's
policies would actually have saved on your own last quarter — shipping avoided, margin recovered, splits avoided.
No integration, no sales call. A bundled sample runs with no upload at all.

*Agent commerce, built in from day one.* Shopping agents are already placing orders. Intent OMS treats that as a
decision problem: agents are registered with a trust tier, every order carries a signed mandate with limits enforced
before capture, and instruction text from an agent is screened and stored as data — never executed as an instruction.
One click produces a dispute evidence pack. This is the part I'd call out specifically as future-proofing: whatever
agentic commerce looks like in two years, "verify the mandate, never trust free text as a command" doesn't stop being
the right architecture.

**Why "future-proof" isn't just a marketing word here**

Three concrete reasons:

1. The model behind any decision is a provider behind an interface. Jev today, something else in two years — the
   typed contract doesn't change, so nothing downstream breaks.
2. Autonomy is additive, not all-or-nothing. You start with every decision escalating to a person, watch the
   confidence and the calibration, and only widen the guardrails as trust is earned — on your own data, at your own
   pace.
3. Every decision, autonomous or not, leaves a full trace: the layer that answered, the confidence, the provider,
   the rules that fired. You're never left explaining a black box to a customer, an auditor, or your own team six
   months from now.

**What's in the repo**

Java 21, Spring Boot, PostgreSQL. 570+ backend tests, 80+ UI tests. Three complete reference implementations —
general merchandise, electronics, apparel — each with five scripted scenarios you can run end to end. A threat model,
a security policy, and an honest list of what's still deferred, published alongside the code rather than hidden. It
runs in three minutes with Docker, in a browser with GitHub Codespaces, or from source.

It's new software. I'm not calling it finished — I'm calling it ready to be judged by people who actually run order
management for a living.

**If you're building a new OMS, or dealing with the limits of your current one, I want to hear from you.** That's a
genuine, specific ask: I'd like to talk with teams who are evaluating or building something in this space. Comment,
message me, or just go try it — the fastest way in is the Playground, or a margin replay of your own export.

— [Your name]

#ordermanagement #opensource #ecommerce #retailtech #agenticAI #supplychain
