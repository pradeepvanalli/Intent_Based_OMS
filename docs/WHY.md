# Why order management should be built on typed LLM decisions now

Order management is a decision machine. Which node ships this line. Whether to split. Whether this return gets an
instant refund or a reviewer. Whether an agent's instruction is a purchase or an injection. A retailer makes these calls
thousands of times a day, and each one moves money or touches a customer.

In a conventional OMS those calls live in a rules engine configured once at implementation. The rules are owned by the
integrator. Changing one is a ticket, a test cycle and a deploy. Nobody can say what would have flipped last month if
the rule had been different. The system cannot read "birthday gift, please send everything together", so it ships two
boxes and charges the retailer for both. When something is not covered by a rule, it falls to a queue, and the queue
grows.

Language models change what is possible here, but not as the first wave of "AI in the OMS" suggested. An LLM that
writes free text and takes actions is the wrong tool for a system of record. The right tool answers a typed question.

## What a typed decision is

A decision in Intent OMS is a YAML object. It has a name, a risk tier, a set of questions, enumerated options for each,
an escape option, deterministic rules that run first, a default, and a golden set of labelled cases. The model is asked
the question and can only answer with one of the options. It also reports a confidence.

That confidence is the key. A decision tier sets the bands. In the act band the answer is applied. In the confirm band
a second model, restricted to strict JSON, is shown the trace and asked to pick one of the same options. Below that, a
person gets a task with the recommendation attached. Money-moving decisions (T2) act only above 0.9. Recommendation-only
decisions (T3) never act at all.

The result is a system that is autonomous exactly as far as its measured calibration allows and no further. In the
demo, the typed layer answers in 350 to 850 ms through a gateway at about $0.00002 per call. Cheap enough to ask on
every order, fast enough to ask inside a workflow step.

## Why now

Three things have arrived at the same time.

Typed decision models exist and are available through ordinary API gateways. Intent OMS uses Jev by default, but the provider is a Spring bean and any
model with structured output can sit behind the same interface.

Shopping agents are placing orders. MCP and UCP are real protocols with real traffic. A merchant now needs to accept a
signed mandate, enforce its limits, keep the agent's instruction text as data rather than instruction, and produce an
evidence pack when the chargeback arrives. That is a decision problem, not a connector problem.

Retailers can finally read their own policies. A copilot turns "never split an order with hazmat items" into a
diffed draft; a merchandising lead sees the change, the cases it flips and the approvals it needs. Policy stops being
implementer-owned configuration.

## Where the limits are

Honesty about the limits is the design.

**The LLM never publishes configuration.** The copilot produces drafts. Every draft passes lint, an impact diff on
real history, a golden-set evaluation gate, and approval by a different person from the author. Thresholds and bands
are changed by humans in the studio, never by a model or an agent.

**Rules come first.** Policy that must never be a judgement call is an L0 rule evaluated before any model. A flagged
customer gets store credit by rule. Hazmat does not fly by rule. The model handles the middle, where a rule would
either be too blunt or impossible to write.

**Golden sets gate every version.** A decision version goes live only when its labelled cases pass the accuracy and
calibration thresholds the tier requires. The weekly drift monitor re-checks calibration against what actually
happened.

**A human is in the loop by construction.** Escalation is a first-class outcome with a queue, an SLA and a
recommendation attached, not an exception path. Kill switches drop any decision, provider or the whole AI layer to its
deterministic default instantly, and the system keeps capturing, sourcing, shipping and returning without a key at all.

**The trace is the product.** Every decision record stores the layers that ran, the rules that fired, the band, the
provider and the latency. The operator reads it in the Why drawer. The customer reads a safe sentence derived from it.
The simulator replays it against a proposed version. Nothing is narrated after the fact.

## What this buys a retailer

Fewer split shipments, because intent is read rather than guessed. Faster refunds for good customers and slower ones
for abusers, with reason codes CS can say out loud. A policy change before lunch instead of in the next release. Proof
of what an agent was allowed to do. And a ledger from which every order and every decision can be rebuilt.

None of that requires trusting a model with the keys. It requires a typed question, measured answers, and people
where the stakes say they belong. That is what Intent OMS does, and it is why it is open source: the design is only
credible if you can read it.
