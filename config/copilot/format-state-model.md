## State model (YAML, PRD Appendix A)
`stateModel` (id, e.g. order_line.standard_ship), `version` (int, next version), `entity` (order|order_line|return|
shipment), `quantityLevel` (bool), `states` (list of `{id, meta: Created|Active|Terminal, sla: {maxAge, onBreach},
parent, blocksIfHeld}`; exactly one Created state; every non-terminal state needs an outgoing transition; every state
must be reachable), `transitions` (list of `{from: id | [ids], to, trigger, guards: [CEL], actions: [names],
permission}`), optional `rollup` for header models (`{parentState, when, priority}`).
Trigger vocabulary: `event.<name>` (integration facts), `decision.<name>` (fired by decisions), `api.<name>` (human or
API actions, usually with `permission`), `timer.<name>`. Actions are names the orchestration implements:
reserve_inventory, send_release_to_wms, penalise_node, release_reservation, capture_payment, notify_customer_shipped,
notify_customer_delivered, void_or_refund, create_review_task, place_hold, release_hold, notify_customer.
A "risk review" is modelled as a new state (e.g. `risk_review`) entered by a guarded transition
(`guards: ["order.agent_order == true && order.total > 500 && order.destination_state == 'CA'"]`) with
`actions: [create_review_task]`, and left by `api.approve_review` / `api.reject_review` with a permission.

Example (complete):
```yaml
stateModel: order_line.simple
version: 1
entity: order_line
quantityLevel: true
states:
  - { id: created, meta: Created }
  - { id: awaiting_sourcing }
  - { id: released, sla: { maxAge: PT4H, onBreach: playbook.store_backlog } }
  - { id: shipped }
  - { id: delivered, meta: Terminal }
  - { id: cancelled, meta: Terminal }
transitions:
  - { from: created, to: awaiting_sourcing, trigger: event.order_validated }
  - from: awaiting_sourcing
    to: released
    trigger: decision.sourcing_plan
    guards: ["plan.margin >= tenant.margin_floor || approval.present"]
    actions: [reserve_inventory, send_release_to_wms]
  - { from: released, to: shipped, trigger: event.shipped, actions: [capture_payment] }
  - { from: shipped, to: delivered, trigger: event.delivered }
  - { from: [created, awaiting_sourcing, released], to: cancelled, trigger: api.cancel_line, permission: order.cancel, actions: [release_reservation, void_or_refund] }
```
