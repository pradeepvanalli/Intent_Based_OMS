# The Agentic Control Tower — diagnosing (and fixing) why an order silently stalled

## The business problem

An order sits in `awaiting_sourcing` for two days. Nobody did anything wrong — the payment might have been declined
and is waiting for a retry, the warehouse might be short on one SKU, the shipping address might have failed
verification, the sourcing node might be backed up, or the carrier might simply have gone quiet. Today an ops analyst
answers "why is this stuck?" by opening four different screens — payments, inventory, the node health dashboard, the
carrier tracking page — and cross-referencing them by hand. A rules engine does not help much here: it can check one
condition at a time ("if payment declined then …"), but a stalled order is rarely explained by one condition in
isolation, and the four signals routinely conflict (a backed-up node *and* a borderline inventory shortfall at the
same time, say). That manual cross-referencing, multiplied across every stuck order in the pipeline, is where ops
teams lose hours every week — and it is exactly the kind of judgement call Jev is good at and a rules engine is not.

## The mechanism

1. **Scan.** `AnomalyScanner` looks every two minutes (configurable, and on demand via `POST /autonomy/scan-now`) for
   open orders that look wrong by one of four real signals: no progress in a configurable window, a high SLA
   breach-risk score, a margin-floor approval that has sat open too long, or an exception past its SLA.
2. **Diagnose.** Each candidate's real state — payment status and retries, inventory ATP at its sourcing node, node
   backlog and reject rate, carrier shipment status, open holds and tasks, the customer's own notes — is assembled
   (never fabricated: a signal that is not available is simply left out) and handed to a new decision,
   `stuck_order_diagnosis` (Choice, tier T2), through the ordinary decision fabric — the same `DecisionService.decide()`
   every other decision in the system goes through, so every diagnosis is a real, inspectable decision record with a
   **Why** trail. Three L0 rules handle the clear-cut cases (a declined payment with retries left, an address that
   failed verification, a clean inventory shortfall with nothing else wrong); Jev adjudicates everything else,
   including the genuinely ambiguous cases where two signals compete.
3. **Act or ask.** When the decision lands in the ACT band *and* every tenant guardrail passes, the diagnosis maps to
   one of the OMS's existing remediation actions — `ResourceAction`, `RepromiseAction`, `ReauthorizeAction` or
   `ContactCustomerAction` — and that action runs for real, through the normal service calls. The attempt, the
   guardrails that were in force, and the outcome are all written to an audit trail (`AutonomousAction`), and the
   action is undoable where a genuine reverse exists. When the decision needs a person (low confidence, or the escape
   option "unclear"), or a guardrail blocks the attempt, a human task opens in the ops queue with the full diagnosis
   already attached — reasoning, confidence, and (when a guardrail blocked it) exactly which one and why — so the
   person reviewing it is not starting cold.
4. **Guardrails, not vibes.** Autonomy ships **off** by default for every tenant. Turning it on still bounds it: a
   daily cap on autonomous actions, a per-action dollar-impact cap, an allow-list of action types (refund and cancel
   are never on it, no matter what a tenant configures), an optional confidence floor above the decision's own
   threshold, and an independent kill switch for an instant, no-questions-asked stop.

## Walking through the demo orders

Four orders were added to the demo tenant's seed (`AG01`-`AG04`), each engineered to stall for a different, real
reason rather than a fabricated one. All four show up once the demo tenant seeds (`autonomy.enabled` is on for
`demo` with a 25-action daily cap and a $150 per-action cap) or immediately after `POST /api/v1/autonomy/scan-now`.

* **AG01 — a declined card with retries left, escalates honestly.** Captured with a tender that always declines
  (`tok_visa_decline`); `stuck_order_diagnosis`'s L0 rule reads the real payment status and confidently picks
  `payment_retry_needed` in the ACT band. Guardrails allow the attempt (retry_payment is on the default allow-list),
  so the executor calls `ReauthorizeAction` for real — and `ReauthorizeAction` only re-authorises *open*
  authorisations that are expiring, not one that has already failed outright, so it correctly finds nothing to do.
  Rather than mark the order handled, the system records the honest attempt (`AutonomousAction.outcome = failed`)
  and opens a human task with the diagnosis and what was tried already attached. This is the safety story: an
  autonomous system that recognises the limits of its own remedy instead of papering over it.
* **AG02 — an address that needs confirming, acts live.** Ships to a Canadian address; `MockAddressVerifier` reports
  it `unverified` (the one deterministic way to get a non-blocking "address not verified" without an invalid US
  ZIP/state combination, which would block capture outright). The same L0 rule fires on `address.verified == false`,
  lands in ACT, guardrails allow `notify_customer`, and `ContactCustomerAction` sends the real "please confirm your
  delivery address" message — a live, visibly successful autonomous action, end to end, no human involved.
* **AG03 — a backed-up pickup store, acts live.** A BOPIS order pinned to `ST-AUS-03`, the worst-health node in
  `nodes.yaml` (backlog 40, reject rate 0.12), with stock chosen to be comfortably available there so inventory never
  competes with the node signal. No L0 rule covers "node is backed up" alone, so Jev (or the deterministic heuristic
  when no key is configured) has to read the real `node.backlog_units`/`reject_rate` and judge it — a genuine,
  provider-scored diagnosis of `node_capacity_backlog`, mapped to `RepromiseAction`, which recomputes the promise for
  real.
* **AG04 — the hard case: two real signals at once.** The same backed-up `ST-AUS-03`, but also short on `CNDL-SOY`
  against what the deterministic demo inventory generator stocks there. The inventory L0 rule explicitly steps aside
  when node backlog is also high (it only fires when inventory is the *only* story), so this order has no rule
  shortcut at all: it genuinely could be `inventory_reallocation_needed` or `node_capacity_backlog`, and Jev has to
  weigh both signals together — exactly the case a rules engine cannot resolve by checking one condition at a time.
  With the heuristic fallback this typically lands in CONFIRM/ESCALATE (moderate confidence); with Jev configured,
  watch the **Why** drawer on the resulting decision record to see which signal it weighed more heavily and why.

## Seeing it live

1. Sign in to the `demo` tenant as `admin` and open **Control Tower** — the "Autonomous operations" section below the
   pipeline/at-risk cards shows the header stats (actions today / cap, $ impact resolved, human escalations,
   automation rate), the guardrails panel (kill switch, caps, allow-list, confidence override) and the live
   anomaly → diagnosis → outcome feed.
2. Click **Run a scan now** (`POST /api/v1/autonomy/scan-now`) to diagnose AG01-AG04 immediately rather than waiting
   for the next scheduled tick.
3. AG02 and AG03 show `⚡ Acted automatically: notify_customer / re_promise, <when>`; AG01 shows
   `⏸ Escalated to decision_escalations: autonomous retry_payment did not complete …` with a link to the task; AG04
   shows the decision's own escalation (or, with Jev configured, a live action) with a link to its decision record.
4. Toggle the kill switch (`POST /api/v1/autonomy/kill-switch {"enabled": true}`) and run the scan again on a fresh
   candidate (`POST /api/v1/orders` a new order the same way, then `POST /scan-now`) to see every subsequent diagnosis
   escalate with `blockedReason: "the autonomy kill switch is engaged"` instead of acting — the emergency stop,
   proven live.
5. Inspect the audit trail directly: `GET /api/v1/autonomy/actions?outcome=executed` for what ran autonomously,
   `GET /api/v1/autonomy/anomalies` for the full feed, and `POST /api/v1/autonomy/actions/{id}/undo` on an undoable
   (`re_source`/`re_promise`/`release_hold`) action to reverse it for real.

## What this is not

This is not a general-purpose autonomous agent loose on the order book. It only ever acts on one decision
(`stuck_order_diagnosis`), maps to four pre-built, already-audited remediation actions, and is bounded by guardrails
a tenant controls and can shut off instantly. Refund and cancel — the two genuinely irreversible, money-moving
actions — are hard-excluded from autonomy regardless of configuration; those always go to a person.
