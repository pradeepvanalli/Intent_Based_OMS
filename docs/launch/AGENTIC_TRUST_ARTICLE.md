# LinkedIn article: the trust layer underneath the agent-shopping fight

*Long-form LinkedIn Article (use LinkedIn's "Write article" option, not a short post). Ready to paste as plain text;
bold the section headers yourself with LinkedIn's toolbar if you want them to stand out, the copy reads fine either
way. Good cover image: `docs/images/control-tower-agentic.png` or a screenshot of the mandate/evidence-pack flow.
Fill in the one `[ ... ]` placeholder (your name) before posting. This is a companion piece to
`LINKEDIN_ARTICLE.md` — post that one for the launch, this one when the agent-commerce news cycle is live, or
whichever order reads better as a two-part series.*

---

## Title

Everyone's arguing about who wins AI shopping. The real fight is about who's liable when the AI is wrong.

## Subtitle

Amazon blocking shopping bots, Shopify opening its catalog to them, Musk weighing a rival — it's all a fight over
distribution. I built the part of the stack none of them are talking about: proof.

---

## Body

Pay attention to commerce news this week and you'll see three moves that look unrelated and aren't. Amazon blocked
Meta's shopping agent from buying on its site, citing transparency and security concerns. Reports surfaced that it's
also limiting Grok's shopping bots, and Elon Musk responded by floating an Amazon competitor built agent-first.
Shopify went the other way and opened its catalog of a billion-plus products to third-party AI agents. Different
bets, same question underneath: when an AI agent decides what you buy instead of you scrolling a page yourself, who
does the buyer's attention belong to?

That's the question getting all the airtime. It's the wrong question to spend your engineering time on, because it's
not yours to answer — it's a distribution fight between platforms with trillion-dollar storefronts. There's a
second question sitting right behind it that nobody on either side of that fight is answering, and it's the one that
actually determines whether any of this works: **when an agent places a real order with real money on a retailer's
behalf, what stops it from getting it wrong, and what does that retailer hand the card network when the chargeback
comes in?**

That's not a distribution problem. That's an order-management problem. It's the one I've been building for, and I
open-sourced the result before this was front-page news: **Intent OMS**, Apache-2.0, on GitHub now.

**Why this is an OMS problem and not a platform problem**

Every answer to "should we let agents check out" eventually runs into the same wall: a chatbot can describe an
order, but something downstream has to enforce what it's actually allowed to do, catch the moment its instructions
were tampered with, and prove after the fact exactly what it was authorized for. That enforcement and proof layer
doesn't live in the agent. It can't — the agent is the thing being constrained. It has to live in the system of
record that actually moves the money and the inventory. That's the order management layer, and right now almost
none of it is built for a non-human buyer.

Intent OMS treats an agent-placed order as what it is: a decision problem, the same kind the system already solves
for sourcing, splitting, and refunds. Every agent registers with a trust tier. Every order carries a signed mandate
— merchant, category, a hard spend limit, an expiry — verified before a cent moves. The instruction text the agent
sends along ("find him something for his workshop, nothing over $150") is screened and stored as data, never
executed as a command, so a prompt injection buried in a product description or a compromised agent can't turn into
an unauthorized action. Every one of those checks leaves a trace. None of this is a roadmap slide — it's running
code today, with tests, in a system you can read.

Here's what that actually looks like at the scale of one order and one 2am incident, because the architecture only
means something once you see it hold up against a real situation.

**Example one: a birthday gift, a signed mandate, and a chargeback three weeks later**

A buyer tells their shopping agent: "Get my dad a birthday gift, he's into woodworking, keep it under $150." The
agent's wallet issues a signed mandate — merchant, category "home-and-garden," spend limit $150, expires in 24
hours — and the agent discovers Intent OMS's capabilities at `/.well-known/ucp`: what it can order, what
authentication it needs, where to send the signed request.

The order comes in over the UCP order endpoint with the mandate attached. Before anything else happens, Intent OMS
checks the mandate the same way a bank checks a card: does it exist, is it still active, does the spend limit cover
this total in this currency, does the signature actually verify against the agent's registered key. The
instruction text — "he's into woodworking" — gets screened for injected instructions and stored as plain data
attached to the order, not interpreted as a command to the system. The typed decision layer picks a fulfillment
node the normal way, weighing cost, distance, and promise date. The order captures. The mandate is marked consumed
so it can't be replayed on a second order.

Three weeks later the buyer's bank opens a dispute: "I didn't authorize this." One click produces the evidence
pack — the exact mandate, its spend limit and expiry, the verified signature, the timestamp, the decision trace for
every step the order took. That's not a support rep piecing together four systems under deadline. That's a PDF a
retailer can hand to a card network the same day. The difference between "we let an AI agent buy things" and "we
can prove what we let it buy, and when, and under what limit" is the entire difference between a pilot program and
a production system a CFO will sign off on.

**Example two: an order goes quiet at 2am, and the system has to decide whether to touch it**

An order stalls. Not failed, not cancelled — just quiet. A payment that didn't clear, inventory that might have
moved, an address the carrier flagged, a fulfillment node running behind. In a conventional system this sits in a
queue until a person starts at 9am, opens four screens, and manually works out which of those five things actually
happened.

Intent OMS runs a typed decision — `stuck_order_diagnosis` — that looks at all five signals together and names the
single most likely cause with a confidence score, something a rules engine genuinely can't do well because rules
check one condition at a time and this requires weighing overlapping, partial evidence the way a person would. Say
it comes back highly confident: the address didn't verify, nothing else is actually wrong. Before it touches
anything, it checks the guardrails the business set — is autonomous action even on for this type of problem, is
today's cap on autonomous actions already used up, is the estimated cost of this one action under the per-action
limit. All clear, so it sends the address-confirmation request itself and logs the action as reversible with one
click.

Right next to it, a second stalled order gets the same diagnosis — inventory moved, re-sourcing needed — but
re-sourcing this one would cost more than the guardrail allows per action. The system doesn't do it anyway. It
escalates to a person with the full diagnosis attached and says exactly why it stopped: "estimated impact exceeds
the per-action cap," not a shrug, not a generic "needs review." And two things never happen autonomously no matter
how the guardrails are configured, by hard rule: a refund and a cancellation. Some decisions should always have a
person's name next to them.

That's the behavior that actually matters when you hand a system autonomy: not that it does more, but that it tells
you the truth about what it didn't do and why, every single time.

**What this buys you that a chatbot bolted onto a rules engine doesn't**

Run those two examples past any of the big OMS platforms — Manhattan, Blue Yonder, IBM Sterling, Salesforce Order
Management — and you'll find deep, mature order orchestration built over a decade. What you won't find, as far as
I've been able to tell, is a shipped answer to either of these two problems: a signed, spend-limited mandate an
agent can be held to, or an autonomous action that explains its own refusal in the business's own guardrail terms.
Those systems were built for a world where the buyer was a person clicking a button. This is a different buyer.

**What's still missing, because a real list beats a highlight reel**

I'm not going to pretend this is finished. It isn't. There's no ERP connector yet — SAP, NetSuite, Dynamics —
which is the first question any enterprise buyer asks. There's no B2B order model: contract pricing, credit terms,
approval chains. Tenant isolation today is enforced in the application layer, not at the database row-security
level, which is the first thing a strict compliance review will ask about. One real carrier integration today, not
the dozens an enterprise rollout needs. No published benchmark for what this does at Black Friday volume. Those are
real gaps, and they're the next things to build, not things I'm hiding.

**Why I think this is the right fight to be in**

Amazon and Shopify are placing different bets on who owns the customer relationship once agents do the shopping.
They might both be right, or both be wrong, and I don't think that's a question an open-source OMS needs to settle.
The question it does need to settle, before any retailer bets real revenue on letting an agent transact on their
behalf, is whether that order can be trusted, limited, and explained after the fact. That's infrastructure, not a
storefront, and infrastructure gets built once and used by whoever wins the fight above it.

**If you're thinking about what it takes to actually let agents transact on your systems — not demo it, actually
run it — I want to hear from you.** That's a specific ask: I'd like to talk to people building or evaluating
agent-commerce infrastructure right now. Comment, message me, or go try the mandate flow yourself — it's in the
Playground, no sales call required.

— [Your name]

#agenticcommerce #ai #ordermanagement #opensource #ecommerce #retailtech #ap2 #mcp
