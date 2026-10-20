# Business demo script — Intent OMS

A run-of-show for presenting Intent OMS to business stakeholders (prospects, leadership, partners) who will never
touch a terminal. Everything below is driven by mouse clicks in the browser against the seeded `demo` tenant.
No command line, no jargon on screen — if a term needs explaining, the line to say it in plain words is right there.

**Audience:** supply chain / ops / CX leaders, not engineers. Assume they know what an order, a return and a
chargeback are, and nothing about decision engines, reinforcement learning or Kubernetes.

**Length:** ~20 minutes core (Acts 1–4) + 5 minutes for the self-optimizing story (Act 5) if the room has appetite
for "what's next" rather than just "what it does today". Cut Act 5 for a first meeting; lead with it for a
repeat audience who already gets the basics.

---

## Before the room

1. Confirm the service is up: open `http://localhost:8080` (or your deployed URL) — it should land on the login
   page. If it's blank or errors, run `make run` (or `scripts/try.sh`) and wait ~90 seconds.
2. Confirm seed data is loaded: log in as `admin` (click the **admin** button, password auto-fills) and check the
   Control Tower isn't empty. If it looks sparse, the demo tenant needs reseeding — ask engineering, don't try to
   fix it live.
3. **Open four browser tabs in advance**, logged in as:
   - Tab 1 — **strategy** (strategy lead): Control Tower, Learning console
   - Tab 2 — **ops** (ops analyst): Orders, Tasks
   - Tab 3 — **cs** (CS rep): Returns, a specific return detail
   - Tab 4 — **admin**: Learning console (Changes tab), for the approval step
   Switching users mid-demo by clicking buttons on a login screen looks like a toy. Pre-logged-in tabs look like
   a real company with real roles.
4. Zoom the browser to 110–125%. Nobody in the back row can read 14px type over a video call.
5. Have `docs/DEMO_USE_CASES.md` closed — it's the engineering version of this script, don't let it show on screen.

**The one thing that can derail you:** this is a live system, not slides. If an order is in a state you didn't
expect, don't fight it — say "let's look at a different one" and move to the next order in the list. There are
30+ seeded orders; you don't need the exact one from a dry run.

---

## The one-sentence pitch (say this before anything loads)

> "Every order management system makes thousands of small decisions a day — which warehouse ships this, what
> delivery date to promise, whether this return is a fraud risk. Today those decisions are buried in config files
> nobody has opened in a year. Intent OMS makes every decision visible, in plain English, the moment it happens —
> and over the next twenty minutes I'll show you three things: how it decides, how it explains itself, and how it
> gets better on its own without anyone touching a setting."

---

## Act 1 — A decision, explained (5 min)

**Goal:** land the core idea — *every decision has a reason you can read, not a black box.*

1. **Tab 1, logged in as strategy.** Land on **Control Tower**. Say: "This is the view a strategy lead opens every
   morning — not a wall of charts, a list of what needs a human." Point at the open exceptions/tasks counts.
2. Click into **Orders** in the left nav (Tab 2 is already there, but do this in Tab 1 so the audience sees you
   navigate). Pick any order with two or more line items — look for one with "split" or two fulfilment groups in
   the list.
3. Open it. Walk the order summary: items, where each is shipping from, promise date.
4. **This is the moment:** click **Why** next to the sourcing decision (or the "decision trace" link on the order).
   A drawer opens showing the decision in plain English — the node chosen, the promise date, the confidence, the
   factors that drove it (cost, on-time likelihood, whether this was a gift/urgent order).
   > "This isn't a log file. It's the system explaining its own reasoning the way you'd explain it to your boss:
   > here's what I chose, here's why, here's how sure I am."
5. If the order split into two packages, point that out specifically: "The customer wrote 'gift, please send it
   all together' — the system read that, and traded off a slightly higher shipping cost against the risk of
   annoying a customer who explicitly asked for one box. That trade-off is visible, not hidden in a scoring
   formula nobody can read."
6. **Payoff line:** "Every one of the thousands of decisions this system makes today has this. Not just the ones
   someone thought to log."

---

## Act 2 — Money: returns and fraud (5 min)

**Goal:** show the business owns the economics, not an engineer.

1. **Tab 3, logged in as cs.** Open **Returns**. Point at the list — note the different refund-timing labels
   (instant, on carrier scan, on inspection, escalate).
2. Open a return that shows **"on inspection"** or **"escalate"** as its timing.
3. Say: "This isn't a flat rule — 'always wait for inspection'. The system scores abuse risk per return: how many
   times has this customer returned something, is this a high-value item with a cheap substitute claim, is the
   receipt missing. Low risk, low value — refund goes out before the box is even scanned by the carrier. That's
   real money: faster refunds measurably increase repeat purchase, and most returns aren't fraud."
4. Click into the return's **Why** / decision detail if available, and point at the reason codes in plain English
   (e.g. "serial returner" or "high value mismatch") rather than a score like "0.73".
5. **Pivot to the money line directly:** "NRF put 2025 US returns at roughly $850 billion, a fifth of everything
   bought online. Even a small shift in refund timing and abuse catch rate on a number that size is real margin —
   and here it's a dial the business sets, in a policy studio, not a backlog ticket to an engineer."

---

## Act 3 — When things go wrong (5 min)

**Goal:** show resilience and human-in-the-loop, not full autonomy pretending to be magic.

1. **Tab 2, logged in as ops.** Open **Tasks**. Point at the queue counts by team (CS, ops, risk).
2. Open one task — ideally an escalation or exception task tied to a decision (a stuck order, a risk review, a
   margin-floor approval).
3. Say: "Most tasks in most OMS platforms are a free-text note and a status dropdown. Here, every task carries the
   decision that created it — the system's own recommendation, and why it couldn't act on its own."
4. Show the recommended action on the task, and the choices available to the person (approve, override with a
   reason, escalate further).
5. **Key message:** "The system knows its own limits. Refunds, cancellations, pricing — things that move money or
   affect a customer directly — are never fully automatic here. That's a design choice, not a missing feature.
   The business decides what's allowed to run on its own and what always needs a person, decision by decision."

---

## Act 4 — Change without a ticket (3 min)

**Goal:** the business owns configuration, not an implementation team.

1. **Tab 1 or Tab 4.** Open **Studio** (Policy Studio) from the left nav, or, faster for time, skip to Act 5 if
   the room is engineering-adjacent and already gets "config lives outside code".
2. Say in one line: "A policy change here — a new threshold, a new rule — goes through review and approval inside
   the product, the same morning it's proposed. Not a ticket queued behind a sprint."
3. Move on quickly — this act is a bridge to Act 5, don't over-invest time here unless the room is asking about
   change management specifically.

---

## Act 5 — It gets better on its own (the big one, 5–7 min)

**Goal:** this is the announcement. Everything before this act earned the right to say this sentence and have it
land: *the system doesn't just execute policy, it improves it, under control.*

1. **Tab 1, logged in as strategy.** Open **Learning** from the left nav. Land on the **Overview** tab.
2. Say: "Every decision we just looked at — which warehouse, what refund timing, what risk score — has a
   consequence days or weeks later. The parcel arrived late. The customer called. The card got charged back.
   Today, on almost every OMS on the market, that feedback never makes it back to the decision. Here, it does,
   automatically."
3. Point at the headline number on the Overview tab (the perfect-order rate, shown against a holdout group).
   > "This number is the system's report card: orders where everything went right — delivered complete, on time,
   > undamaged, no return — compared against a slice of customers deliberately held back on the old policy, so we
   > can prove the new one is actually better, not just different."
4. Click the **Learners** tab. Scroll the list.
   > "These are 27 separate things the system watches and learns from — this warehouse's real on-time rate, this
   > shipping lane's actual transit time, this customer's tolerance for split shipments, which fraud signals
   > actually predict a chargeback. Each one has a name a business person can read, and a dial: suggest only,
   > or act within limits I've set."
5. **This is the trust moment — spend real time here.** Click into one learner (node reliability or the fraud
   threshold tuner is a good pick) and show its autonomy setting and its kill switch.
   > "Nothing here learns in secret. Every learner can be paused — one at a time, or everything at once, instantly
   > — by someone with the right role. And turning a learner off doesn't break anything; the system just falls
   > back to the number a person configured."
6. Click the **Changes** tab. Find a change with status "Live" or "Promoted" and open it.
   > "Here's a value the system learned — say, a warehouse's real reject rate — that actually went into production.
   > But look at how it got there: it was proposed, tested against historical data with a confidence interval, a
   > different person had to approve it than the one who proposed it, and it's bounded — it can only move so far
   > from the number we started with, in one step."
7. Point out the **rollback** affordance if visible.
   > "And if it turns out to be wrong — one click, and it's back to exactly what it replaced. Not 'retrain the
   > model and redeploy next sprint.' One click, right now."
8. **Close the act with the three-sentence version**, said slowly, looking at the room, not the screen:
   > "It logs every decision with the alternatives it didn't pick. It learns from what actually happened. And
   > nothing it learns goes live without evidence, a second person, and a way to undo it. That's the difference
   > between 'AI-optimized' as a marketing word and self-optimizing as something you can actually audit."

---

## Closing (2 min)

1. Go back to **Control Tower** (Tab 1) to end where you started — the everyday view, not the lab.
2. The ask, said directly, matched to the room:
   - **Prospect evaluating vendors:** "What I'd suggest next is a 90-day pilot on one line of business, with a 5%
     holdout, so the lift number you saw a minute ago is measured on your own traffic, not a demo tenant."
   - **Internal leadership / board:** "This is live and open source today. The question in front of us is whether
     we run a pilot internally or let this shape how we evaluate the vendors we're already talking to."
   - **Partner / investor:** "The code and the full audit trail — every requirement mapped to the test that proves
     it — are public. I'm not asking you to trust a deck."
3. Stop talking. Let the first question come before you fill the silence.

---

## Anticipated questions and the plain-English answer

- **"Isn't this just a recommendation engine with extra steps?"** No — it changes live behaviour, but only through
  the gate you just saw: evidence, approval where the risk calls for it, bounded movement, instant rollback. A
  recommendation engine doesn't touch production; this does, under control.
- **"What stops it from learning something biased or wrong?"** Three things: the business sets hard limits it can
  never cross (refunds, cancellations, pricing are never automatic); a fairness check runs on every learner that
  touches customer outcomes; and the kill switch — tenant-wide or per learner — takes effect immediately.
- **"How long until it actually gets better?"** The mechanism works from day one; the honest answer on *how much*
  better takes real traffic — weeks, not a demo. That's why the holdout group exists: so the lift number is proven,
  not asserted.
- **"What does it cost to run?"** Most of it runs with no AI calls at all — the fast-learning loop is plain
  statistics, not a language model. A language model is only in the loop for the handful of things that genuinely
  need judgment (classifying an ambiguous intent, drafting a policy change for a human to review), and routing
  picks the cheapest model that's good enough.
- **"Can we see the code?"** Yes — it's open source, Apache 2.0, on GitHub today. Every requirement has a public
  traceability document mapping it to the code and the test that proves it.

---

## If something breaks on screen

- **A page is empty or errors:** navigate to a different order/return/learner from the list — you don't need the
  exact record, any seeded one tells the same story.
- **A number looks "wrong" or surprising:** don't explain it away in detail live. Say "that's exactly the kind of
  thing the Why drawer / audit trail is for — let's look at why" and open it. The product's whole pitch is that
  nothing is hidden; an odd number is a feature to lean into, not a bug to hide.
- **Someone asks something you don't know:** "Good question — let me get back to you with the specifics rather
  than guess in front of you." Write it down. Follow up same day.

---

## One-page cheat sheet (print this, not the rest)

| Act | Screen | One thing to click | The line |
|---|---|---|---|
| 1. A decision, explained | Orders → an order → Why | Open the Why drawer | "Every decision explains itself, in plain English." |
| 2. Money | Returns → a return | Refund timing + reason codes | "The business sets the dial, not an engineer." |
| 3. When things go wrong | Tasks → a task | The decision behind the task | "It knows its own limits — money decisions always need a person." |
| 4. Change without a ticket | Studio | (bridge, keep brief) | "A policy change ships the same morning, inside the product." |
| 5. It gets better on its own | Learning → Overview → Learners → Changes | A promoted change, its evidence, rollback | "Evidence, a second approver, bounded, reversible — in one click." |
| Close | Control Tower | — | Match the ask to the room; then stop talking. |

**Logins (tenant `demo`, password = username):** `admin`, `ops`, `cs`, `risk`, `finance`, `strategy`, `platform`.
