# Authoring state models

A state model is a YAML statechart for one entity type (`order`, `order_line`, `return`, `shipment`, ...). The engine
(`oms-statemachine`, `docs/ARCHITECTURE.md` §3.1) tracks **quantities per state** for quantity-level models, evaluates
CEL guards and holds on every transition, schedules SLA timers, derives header state from line quantities, versions
every model and migrates in-flight entities between versions with a dry run. Models are published through
`POST /api/v1/state-models/publish {yaml, reason}` after static checks (`POST /api/v1/state-models/validate {yaml}`).

Examples: `config/tenants/demo/state-models/` and `config/tenants/starter/state-models/` (order header and line for
standard ship and BOPIS, return, shipment), `docs/examples/state-models/order_line.preorder.yaml`.

## Format

```yaml
stateModel: order_line.standard_ship   # id; convention <entity>.<variant>
version: 3                             # integer; publish refuses a version that already exists
entity: order_line                     # order | order_line | return | return_line | shipment | payment | refund
quantityLevel: true                    # true: a line of 10 can be 4 shipped / 3 released / 3 backordered (FR-SM-02)
states:
  - { id: created, meta: Created }                       # exactly one Created state (the initial state)
  - { id: awaiting_sourcing }                             # meta defaults to Active
  - { id: released, sla: { maxAge: PT4H, onBreach: playbook.store_backlog } }
  - { id: picking, parent: released }                    # sub-state (parallel regions are deferred, FR-SM-04)
  - { id: backordered, sla: { maxAge: P3D, onBreach: decision.backorder_next_action } }
  - { id: delivered, meta: Terminal }                     # at least one Terminal state; Terminal states have no outgoing transitions
  - { id: cancelled, meta: Terminal, blocksIfHeld: [legal] }   # hold types that block entering this state
transitions:
  - from: awaiting_sourcing                              # one id or a list
    to: released
    trigger: decision.sourcing_plan                      # event.* | api.* | timer.* | decision.*
    guards:                                              # all must be true (CEL)
      - "plan.margin >= tenant.margin_floor || approval.present"
      - "!order.holds.exists(h, 'release' in h.blocks)"
    actions: [reserve_inventory, send_release_to_wms]    # names the orchestration workflow executes
  - from: [created, awaiting_sourcing, released, backordered]
    to: cancelled
    trigger: api.cancel_line
    permission: order.cancel                             # required on api.* triggers (checked for human actors)
    actions: [release_reservation, void_or_refund]
rollup:                                                  # header models only (quantityLevel: false), FR-SM-05
  - { parentState: cancelled, priority: 10, when: "ctx.total > 0 && state.cancelled == ctx.total" }
  - { parentState: shipped,   priority: 40, when: "state.shipped > 0 && state.shipped + state.cancelled == ctx.total" }
```

Keys accepted by the parser: `stateModel`, `version`, `entity`, `quantityLevel`, `states[]{id, meta, sla{maxAge,
onBreach}, parent, blocksIfHeld}`, `transitions[]{from, to, trigger, guards|guard, actions|action, permission}`,
`rollup[]{parentState, when|expression, priority}`.

## Triggers

| Prefix | Fired by | Example |
|---|---|---|
| `event.` | Integrations and the workflow: WMS/carrier webhooks, internal events | `event.shipped`, `event.store_rejected`, `event.inventory_available` |
| `api.` | A person or agent through the API; **must** declare `permission` | `api.cancel_line` (order.cancel), `api.hold_order` (order.hold) |
| `timer.` | SLA/timer expiry | `timer.pickup_window_expired` |
| `decision.` | The outcome of a decision; guards read `decision.value` or the plan | `decision.sourcing_plan`, `decision.return_eligibility` |

The validator rejects an unknown prefix (`invalid_trigger`) and an `api.*` trigger without a permission
(`missing_permission`). Several transitions may share a trigger with different guards (the first whose guards pass
applies); `duplicate_transition` flags identical from/to/trigger triples.

## Guards

CEL boolean expressions over a fixed set of roots (ADR-005). The engine always provides `tenant` (the tenant config
snapshot with dots replaced by underscores, so `tenant.margin_floor`), `state` (current state id), `now`, `ctx` and
`order.holds` (active holds with `type`, `blocks`); the caller adds `plan`, `approval`, `order`, `line`, `event`,
`decision`, `customer`, `node`, `payment`, `agent`, `mandate`, `input`, `answers` in `TransitionRequest.vars`.
`return` is a CEL reserved word: pass return data under `ctx` or `input` (the return model uses `ctx.refund_timing`).

A guard that references an undeclared root fails validation (`unknown_guard_root`); a syntax error is
`invalid_guard`. Guards must be total: when a trigger can arrive and no transition's guards pass, `fire()` returns
`applied=false` with `blockedBy` listing the guard texts (it never throws unless `vars.strict == true`).

## Holds (FR-SM-07)

A hold is typed (`compliance`, `payment`, `address`, `agent_verification`, `legal`, anything you define), scoped to an
order, line, return or shipment, and lists what it `blocks`: trigger names (`decision.sourcing_plan`, `event.shipped`)
or state ids (`release`). A hold on an order blocks its lines. Guards see holds through `order.holds`; the engine also
checks `blocksIfHeld` on the target state. Holds carry a reason code, a note, an optional TTL and are released with a
resolution; everything is in the ledger (`hold.placed`, `hold.released`).

API: `POST /api/v1/orders/{id}/holds {type, blocks, reasonCode, note, ttl}` or `POST /api/v1/holds {type, scopeType,
scopeId, blocks, ...}`; `POST /api/v1/holds/{id}/release {resolution}`. The capture pipeline places a `compliance` hold
blocking `decision.sourcing_plan`, `release` and `event.shipped` when a rule says `hold`; agent orders above the mandate
get an `agent_verification` hold.

## SLAs (FR-SM-08)

`sla: { maxAge: <ISO-8601 duration>, onBreach: playbook.<name> | decision.<name> }` on a state starts a timer when
quantity enters the state and cancels it when the quantity leaves. On expiry the sweeper (every
`oms.statemachine.sweep-ms`) appends `sla.breached` to the entity's aggregate and publishes `SlaBreachedEvent`; the
monitoring module raises an exception of type `sla_breach` and runs the named playbook, or evaluates the named
decision (the backorder model does this with `decision.backorder_next_action`). `sla.yaml` can declare the same SLAs
per order type (`PUT /api/v1/sla-definitions`) plus `expectedNextEventWithin`, which drives the stuck-order sweeper and
the `nextExpectedEvent` shown on the order view. `invalid_sla_on_breach` and the duration format are checked at publish.

## Quantity-level models and roll-ups (FR-SM-02, FR-SM-05)

With `quantityLevel: true` the engine keeps `entity_state_qty` buckets per state. `fire()` with `qty` moves part of
the quantity (`fromState` selects the bucket when several could match; null means "any matching"). The status view
returns the dominant state plus `quantities[{state, qty, since}]`.

Header models (`quantityLevel: false`) derive their state from line buckets with `rollup` rules evaluated by
`priority` (lowest first, first match wins) over `state` (map state id → quantity across lines), `ctx.total`,
`ctx.states_with_qty` and `lines`. Roll-up runs automatically after every line move (`trigger: rollup`); a header model
without a matching rule falls back to the engine default. Keep explicit header transitions for header-only actions
(hold, cancel) so the model stays statically complete (`FR-SM-10`).

## Actions

`actions` are names, not code. The orchestration workflow reads them from `TransitionResult.actions` and executes
them: the demo vocabulary is `reserve_inventory`, `send_release_to_wms`, `send_pick_to_store`, `penalise_node`,
`release_reservation`, `capture_payment`, `notify_customer_*`, `void_or_refund`, `issue_return_label`,
`issue_refund`, `run_disposition`, `raise_exception`. A new action name needs a handler in
`backend/oms-orchestration` or it is logged and ignored. Decide what is an action (side effect the workflow performs)
and what is a guard (condition the engine checks) before writing the YAML.

## Static checks (FR-SM-10)

`POST /api/v1/state-models/validate` returns `{ok, issues[{severity, code, message, ref}]}`. Errors block publish:

| Code | Meaning |
|---|---|
| `missing_model_id`, `missing_entity`, `invalid_version`, `no_states`, `parse_error` | Structural |
| `missing_initial`, `multiple_initial`, `missing_terminal` | Exactly one `Created`, at least one `Terminal` |
| `duplicate_state`, `unknown_state`, `unknown_parent` | State references |
| `unreachable_state`, `dead_end`, `terminal_outgoing`, `unbounded_loop` | Graph shape: every Active state is reachable from Created and can reach a Terminal; Terminal states have no exits |
| `invalid_trigger`, `unknown_trigger_kind`, `missing_permission` | Trigger grammar |
| `invalid_guard`, `unknown_guard_root` | CEL |
| `invalid_sla_on_breach` | `onBreach` must be `playbook.<name>` or `decision.<name>` |
| `invalid_rollup_expression`, `unknown_rollup_root`, `unknown_rollup_state` | Roll-up rules |

## Versioning and migration (FR-SM-06)

Entities pin the model version they started on. Publishing version n+1 affects new entities only. To move in-flight
entities:

```bash
curl -X POST $BASE/api/v1/state-models/order_line.standard_ship/migrate -d '{"fromVersion":3,"toVersion":4,"dryRun":true}'
# → {"total":412,"migrated":398,"blocked":14,"entities":[{"entityId":"...","state":"picking","blocked":"state picking does not exist in v4"}]}
```

Read the per-entity report, fix the model (keep every state that has in-flight quantity, or add a transition out of
it), then run with `dryRun:false`. The migration appends `statemodel.migrated` and never changes quantities, only the
pinned version. Rename a state by adding the new one in n+1, migrating, and removing the old one in n+2.

## Binding to order types

`order-types.yaml` binds `headerStateModel`, `lineStateModel` and `workflowType` per order type. Several order types
can share a model (the demo binds `ship_from_store`, `same_day_local`, `drop_ship`, `pre_order` and `backorder` to the
standard-ship models with different `sourcing:` constraints). Change the binding with `PUT /api/v1/order-types/{name}`;
existing orders keep their pinned model and version.

## Worked example: pre-order line

`docs/examples/state-models/order_line.preorder.yaml` adds an `awaiting_release_date` state with a `timer.release_date`
trigger ahead of sourcing, a longer `backordered` SLA that invokes `decision.backorder_next_action`, a
`substitute_offered` state with an `api.accept_substitute` transition (permission `order.modify`), and keeps the rest
of the standard-ship vocabulary so the same workflow and WMS webhooks drive it. Validate it before publishing to see the
checks pass, then bind it from a `pre_order` order type.
