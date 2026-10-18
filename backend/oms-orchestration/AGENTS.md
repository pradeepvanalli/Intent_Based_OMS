# AGENTS.md — oms-orchestration (workflows + Agentic Control Tower)

Order workflows wiring every module together, and the Agentic Control Tower (`autonomy` package) that diagnoses and
fixes stalled orders on its own, within guardrails. Read the root `AGENTS.md` first. Full signatures:
`docs/MODULE_NOTES.md` § oms-workflow / § Agentic Control Tower. Mechanism: `docs/UC_AGENTIC_CONTROL_TOWER.md`.

## Workflows

`StandardShipOrderWorkflow` is the one to read first — every other order workflow follows its shape. Steps run
through `WorkflowContext.activity(name, RetryPolicy, Callable)`; activity results round-trip through JSON (return
Map/List/String/Number/Boolean or a record via `Json.convert`, not an arbitrary Java object). A new action a state
model's YAML references needs a handler registered here — the YAML names the action, this module implements it.

**If you're adding a field to an event this module appends** (anything via `appendOrderEvent`/`s.appendOrderEvent`),
check `docs/MODULE_NOTES.md`'s `oms-learning` section first — several of this module's events (`order.delivered`
most notably) are now also read by SOO's `OutcomeObserver`, so a payload change here can silently change what a
learner sees. Compute real facts at the source (e.g. `onTime` from `FulfilmentGroup.promiseDate()`) rather than
leaving a downstream consumer to guess.

## The Agentic Control Tower — don't weaken the guardrail order

`GuardrailService.evaluate` checks, in this exact order: kill switch → master enable switch → hard-never-autonomous
rule → allow-list membership → confidence floor → daily action cap → dollar-impact cap. Refund and cancel are
excluded from the allow-list **at the config-loading layer**, not just checked at evaluation time — a tenant
cannot configure them in even by mistake. If you add a new `AutonomousActionType`, it defaults to NOT in
`NEVER_AUTONOMOUS` unless you explicitly add it there; get this right, don't assume it's safe by omission.

Every autonomous diagnosis goes through the real decision fabric (`DecisionService.decide("stuck_order_diagnosis",
...)`) — never bypass it with a hardcoded mapping, even for "obviously" clear-cut cases; that's what the L0 rules
in the decision's own YAML are for. An action that's attempted but finds nothing to do (an honest `outcome=failed`,
like `ReauthorizeAction` on an already-declined payment) is correct behavior, not a bug to "fix" by making it
pretend to succeed.

## Undo is a contract, not a convenience

`AutonomyUndoService` only implements undo for actions it can genuinely reverse (`re_source`, `re_promise`,
`release_hold`); `retry_payment`/`notify_customer` refuse outright with "not meaningfully reversible" rather than
pretending. If you add a new autonomous action type, decide honestly whether it's undoable before wiring it in —
an action marked undoable that can't actually be reversed is worse than one honestly marked not undoable.

## Demo seeding

`DemoSeeder` loads every tenant in `oms.demo.tenants`; a tenant whose config folder doesn't exist is skipped with a
warning, not a failure. The demo tenant's base order set is still the Java `DemoOrders.all()` fallback (no
`sample-orders.yaml` for it) — adding demo orders to it is additive; creating a `sample-orders.yaml` for the demo
tenant would *replace* that fallback entirely, which is almost never what you want (see how the four Agentic
Control Tower demo orders, `AG01`-`AG04`, were added to `DemoOrders.all()` specifically to avoid this).
