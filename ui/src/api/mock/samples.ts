/* Sample YAML documents (PRD Appendix A/B formats) used by the mock API. */

export const LINE_MODEL_V3 = `stateModel: order_line.standard_ship
version: 3
entity: order_line
quantityLevel: true
states:
  - { id: created, meta: Created }
  - { id: awaiting_sourcing }
  - { id: released, sla: { maxAge: PT4H, onBreach: playbook.store_backlog } }
  - { id: picking }
  - { id: packed }
  - { id: shipped }
  - { id: backordered, sla: { maxAge: P3D, onBreach: decision.backorder_next_action } }
  - { id: delivered, meta: Terminal }
  - { id: cancelled, meta: Terminal }
transitions:
  - from: created
    to: awaiting_sourcing
    trigger: event.order_validated
  - from: awaiting_sourcing
    to: released
    trigger: decision.sourcing_plan
    guards:
      - "plan.margin >= tenant.margin_floor || approval.present"
      - "!order.holds.exists(h, 'release' in h.blocks)"
    actions: [reserve_inventory, send_release_to_wms]
  - from: awaiting_sourcing
    to: backordered
    trigger: decision.sourcing_plan
    guards: ["plan.result == 'no_feasible_node'"]
  - from: backordered
    to: awaiting_sourcing
    trigger: event.inventory_available
  - from: released
    to: picking
    trigger: event.pick_started
  - from: picking
    to: packed
    trigger: event.packed
  - from: packed
    to: shipped
    trigger: event.shipped
    actions: [capture_payment, notify_customer]
  - from: shipped
    to: delivered
    trigger: event.delivered
  - from: released
    to: awaiting_sourcing
    trigger: event.store_rejected
    actions: [penalise_node, release_reservation]
  - from: [created, awaiting_sourcing, released, backordered]
    to: cancelled
    trigger: api.cancel_line
    permission: order.cancel
`;

export const LINE_MODEL_V2 = LINE_MODEL_V3
  .replace('version: 3', 'version: 2')
  .replace(`  - from: backordered
    to: awaiting_sourcing
    trigger: event.inventory_available
`, '')
  .replace(`  - { id: backordered, sla: { maxAge: P3D, onBreach: decision.backorder_next_action } }`, `  - { id: backordered, sla: { maxAge: P5D, onBreach: decision.backorder_next_action } }`);

export const HEADER_MODEL_V2 = `stateModel: order.standard_ship
version: 2
entity: order
quantityLevel: false
states:
  - { id: created, meta: Created }
  - { id: validated }
  - { id: sourcing }
  - { id: released }
  - { id: partially_shipped }
  - { id: shipped }
  - { id: delivered, meta: Terminal }
  - { id: cancelled, meta: Terminal }
transitions:
  - { from: created, to: validated, trigger: event.order_validated }
  - { from: validated, to: sourcing, trigger: event.intent_inferred }
  - { from: sourcing, to: released, trigger: rollup }
  - { from: released, to: partially_shipped, trigger: rollup }
  - { from: [released, partially_shipped], to: shipped, trigger: rollup }
  - { from: shipped, to: delivered, trigger: rollup }
  - { from: [created, validated, sourcing, released], to: cancelled, trigger: api.cancel_order, permission: order.cancel }
rollup:
  - { parentState: delivered, expression: "state['delivered'] == lines.total", priority: 10 }
  - { parentState: shipped, expression: "state['shipped'] + state['delivered'] == lines.total", priority: 20 }
  - { parentState: partially_shipped, expression: "state['shipped'] > 0", priority: 30 }
  - { parentState: cancelled, expression: "state['cancelled'] == lines.total", priority: 5 }
`;

export const BOPIS_MODEL_V1 = `stateModel: order_line.bopis
version: 1
entity: order_line
quantityLevel: true
states:
  - { id: created, meta: Created }
  - { id: awaiting_sourcing }
  - { id: released, sla: { maxAge: PT2H, onBreach: playbook.store_backlog } }
  - { id: picking }
  - { id: ready_for_pickup, sla: { maxAge: P5D, onBreach: decision.pickup_reminder } }
  - { id: picked_up, meta: Terminal }
  - { id: cancelled, meta: Terminal }
transitions:
  - { from: created, to: awaiting_sourcing, trigger: event.order_validated }
  - { from: awaiting_sourcing, to: released, trigger: decision.sourcing_plan, actions: [reserve_inventory, notify_store] }
  - { from: released, to: picking, trigger: event.pick_started }
  - { from: picking, to: ready_for_pickup, trigger: event.ready, actions: [notify_customer] }
  - { from: ready_for_pickup, to: picked_up, trigger: event.picked_up, actions: [capture_payment] }
  - { from: released, to: awaiting_sourcing, trigger: event.store_rejected, actions: [penalise_node] }
  - { from: [created, awaiting_sourcing, released, ready_for_pickup], to: cancelled, trigger: api.cancel_line, permission: order.cancel }
`;

export const RETURN_MODEL_V1 = `stateModel: return.standard
version: 1
entity: return
quantityLevel: false
states:
  - { id: requested, meta: Created }
  - { id: eligibility_review }
  - { id: approved }
  - { id: in_transit, sla: { maxAge: P14D, onBreach: decision.return_not_received } }
  - { id: received }
  - { id: inspected }
  - { id: refunded, meta: Terminal }
  - { id: denied, meta: Terminal }
  - { id: closed_keep_it, meta: Terminal }
transitions:
  - { from: requested, to: eligibility_review, trigger: event.return_created }
  - { from: eligibility_review, to: approved, trigger: decision.return_eligibility, guards: ["answers.risk <= 2"] }
  - { from: eligibility_review, to: denied, trigger: decision.return_eligibility, guards: ["answers.risk >= 4"], permission: return.decide }
  - { from: eligibility_review, to: closed_keep_it, trigger: decision.keep_it, actions: [refund_full] }
  - { from: approved, to: in_transit, trigger: event.carrier_scan }
  - { from: approved, to: refunded, trigger: decision.refund_timing, guards: ["answers.timing == 'instant'"], actions: [refund] }
  - { from: in_transit, to: received, trigger: event.received }
  - { from: in_transit, to: refunded, trigger: decision.refund_timing, guards: ["answers.timing == 'on_carrier_scan'"], actions: [refund] }
  - { from: received, to: inspected, trigger: event.inspected, actions: [run_disposition] }
  - { from: inspected, to: refunded, trigger: api.refund, actions: [refund] }
  - { from: inspected, to: denied, trigger: api.deny, permission: return.decide }
`;

export const DECISION_YAML: Record<string, string> = {
  fulfilment_intent: `decision: fulfilment_intent
version: 7
owner: omnichannel-strategy
tier: T1
provider: jev
fallbackProvider: llm.anthropic
timeoutMs: 800
default: channel_default_policy
purpose: Infer which fulfilment outcome the buyer wants from notes, choices and history.
state:
  include:
    - order.channel
    - order.notes
    - order.intent_choices
    - customer.tier
    - customer.history.split_acceptance_rate
    - plan_options[].packages
    - plan_options[].eta
    - plan_options[].cost_delta
questions:
  intent:
    type: choice
    instructions: Which fulfilment outcome does the buyer most want?
    criteria:
      ship_complete: Everything together, even if it arrives later
      ship_as_available: Each item as soon as it is ready
      earliest_partial: One specific item is needed urgently
      pickup_consolidated: Prefers to collect in store
      unclear: Not enough information to tell
  split_ok:
    type: noul
    statement: This buyer would accept the order in more than one package.
  is_gift:
    type: noul
    statement: The order notes indicate the order is a gift.
rules:
  - id: explicit_choice
    when: "order.intent_choices.size() == 1"
    then: "order.intent_choices[0]"
    reason: Buyer made an explicit choice at checkout
    priority: 10
  - id: hazmat_no_split
    when: "order.has_hazmat"
    then: ship_complete
    reason: Hazmat lines must ship as one consignment
    priority: 20
bands:
  act: { confidence_gte: 0.8 }
  confirm: { confidence_gte: 0.5 }
  escalate: { otherwise: true }
preChecks:
  - "order.lines.size() > 0"
postChecks:
  - "chosen_plan.margin >= tenant.margin_floor"
  - "!(order.has_hazmat && chosen_plan.packages > 1)"
evaluation:
  goldenSet: fulfilment_intent_gold_v7
  minAccuracyInActBand: 0.90
  maxCalibrationError: 0.05
`,
  sourcing_node_score: `decision: sourcing_node_score
version: 4
owner: omnichannel-strategy
tier: T1
provider: jev
fallbackProvider: heuristic
timeoutMs: 600
default: 2
purpose: Score each candidate node's likelihood of shipping the allocation on time.
state:
  include:
    - node.id
    - node.type
    - node.health.backlog
    - node.health.pick_rate
    - node.health.reject_rate
    - node.health.staffing
    - node.cutoff_minutes_remaining
    - candidate.distance_km
    - candidate.transit_days
    - candidate.coverage_ratio
questions:
  on_time:
    type: score
    instructions: How likely is this node to ship the allocation before cutoff and meet the promised date?
    levels:
      - Very unlikely — backlog exceeds a day or node is rejecting most releases
      - Unlikely — backlog is high or staffing is thin
      - Even — normal operating conditions
      - Likely — low backlog, healthy pick rate
      - Very likely — idle capacity and strong recent reliability
composite:
  weights: { on_time: 1.0 }
  outputKey: node_score
topK:
  scoreQuestion: on_time
  k: 12
  candidatesField: candidates
bands:
  act: { confidence_gte: 0.8 }
  confirm: { confidence_gte: 0.5 }
  escalate: { otherwise: true }
evaluation:
  goldenSet: sourcing_node_score_gold_v4
  minAccuracyInActBand: 0.85
  maxCalibrationError: 0.06
`,
  exception_classify: `decision: exception_classify
version: 3
owner: order-operations
tier: T0
provider: jev
fallbackProvider: heuristic
timeoutMs: 500
default: other
purpose: Classify a raised exception into type, severity and owning queue.
state:
  include:
    - event.type
    - event.message
    - order.status
    - order.channel
    - order.age_hours
    - order.promise_date
    - node.id
    - carrier.last_scan
questions:
  type:
    type: choice
    instructions: What kind of exception is this?
    criteria:
      address: The ship-to address failed verification or the carrier could not deliver
      inventory_short: Stock was not available at the chosen node (short pick, backorder)
      carrier_delay: The carrier has missed a scan or the delivery date
      payment: Authorisation expired, declined or capture failed
      store_reject: The store rejected or timed out on the release
      system: An integration or internal error
      other: None of the above fits
  severity:
    type: score
    instructions: How urgent is this for the customer and the business?
    levels: [Informational, Low, Medium, High, Critical]
bands:
  act: { confidence_gte: 0.5 }
  escalate: { otherwise: true }
evaluation:
  goldenSet: exception_classify_gold_v3
  minAccuracyInActBand: 0.88
  maxCalibrationError: 0.05
`,
  sla_breach_risk: `decision: sla_breach_risk
version: 2
owner: order-operations
tier: T0
provider: jev
fallbackProvider: heuristic
timeoutMs: 500
default: 2
purpose: Predict the risk that an open order breaches its promise or state SLA.
state:
  include:
    - order.status
    - order.age_hours
    - order.hours_to_promise
    - order.holds
    - node.health.backlog
    - node.health.reject_rate
    - carrier.on_time_rate_7d
    - carrier.last_scan_age_hours
questions:
  risk:
    type: score
    instructions: How likely is this order to miss its promise or breach the current state SLA?
    levels: [Negligible, Low, Moderate, High, Imminent]
bands:
  act: { confidence_gte: 0.5 }
  escalate: { otherwise: true }
`,
  return_risk: `decision: return_risk
version: 5
owner: loss-prevention
tier: T2
provider: jev
fallbackProvider: llm.anthropic
timeoutMs: 800
default: on_inspection
purpose: Assess abuse risk on a return and pick the refund timing.
state:
  include:
    - return.reason
    - return.lines[].sku
    - return.lines[].value
    - return.receipt_present
    - customer.history.returns_90d
    - customer.history.not_received_claims
    - customer.history.return_rate
    - customer.tenure_days
    - order.channel
questions:
  risk:
    type: score
    instructions: How likely is this return to be abusive or fraudulent?
    levels: [Very unlikely, Unlikely, Possible, Likely, Very likely]
  timing:
    type: choice
    instructions: When should the refund be issued?
    criteria:
      instant: Refund now; trusted customer, low value
      on_carrier_scan: Refund when the carrier scans the package
      on_inspection: Refund after the warehouse inspects the item
      store_credit_only: Offer store credit rather than a refund
      escalate: Not enough information; a person should decide
  many_not_received_claims:
    type: noul
    statement: The customer has an unusual number of item-not-received claims.
    reasonCode: true
  serial_returner:
    type: noul
    statement: The customer's return rate is far above the norm for this category.
    reasonCode: true
  high_value_mismatch:
    type: noul
    statement: The returned item value is inconsistent with the customer's purchase history.
    reasonCode: true
  receipt_missing:
    type: noul
    statement: The return lacks proof of purchase.
    reasonCode: true
rules:
  - id: final_sale
    when: "return.lines.exists(l, l.final_sale)"
    then: block
    reason: Final-sale items are not returnable
    priority: 5
  - id: outside_window
    when: "return.days_since_delivery > tenant.return_window_days"
    then: escalate
    reason: Outside the return window
    priority: 10
bands:
  act: { confidence_gte: 0.9 }
  confirm: { confidence_gte: 0.6 }
  escalate: { otherwise: true }
postChecks:
  - "!(answers.timing == 'instant' && return.value > tenant.instant_refund_cap)"
evaluation:
  goldenSet: return_risk_gold_v5
  minAccuracyInActBand: 0.92
  maxCalibrationError: 0.05
`,
  return_disposition: `decision: return_disposition
version: 2
owner: loss-prevention
tier: T1
provider: jev
fallbackProvider: heuristic
timeoutMs: 600
default: restock
purpose: Decide what to do with a returned item after inspection.
state:
  include:
    - return.lines[].sku
    - return.lines[].condition
    - return.lines[].category
    - return.lines[].value
    - inventory.network_atp
    - vendor.rtv_allowed
questions:
  disposition:
    type: choice
    instructions: What should happen to the returned item?
    criteria:
      restock: Sell again as new
      refurbish: Minor repair or repackaging then sell
      liquidate: Sell to a liquidator
      return_to_vendor: Vendor accepts returns of this item
      donate: Low value, unsellable but usable
      destroy: Unsafe or worthless
      unclear: Not enough information from the inspection
bands:
  act: { confidence_gte: 0.8 }
  confirm: { confidence_gte: 0.5 }
  escalate: { otherwise: true }
`,
  injection_screen: `decision: injection_screen
version: 1
owner: ai-governance
tier: T0
provider: jev
fallbackProvider: heuristic
timeoutMs: 400
default: false
purpose: Flag untrusted text that contains instructions aimed at the system.
state:
  include:
    - input.text
    - input.source
questions:
  injection:
    type: noul
    statement: This text contains instructions directed at an AI system or attempts to change its behaviour.
bands:
  act: { confidence_gte: 0.5 }
  escalate: { otherwise: true }
`,
  backorder_next_action: `decision: backorder_next_action
version: 2
owner: order-operations
tier: T2
provider: jev
fallbackProvider: llm.anthropic
timeoutMs: 800
default: wait
purpose: Choose what to do with a line that has been backordered past its SLA.
state:
  include:
    - line.sku
    - line.days_backordered
    - line.inbound_eta_days
    - line.substitute_available
    - customer.tier
    - order.channel
    - order.value
questions:
  action:
    type: choice
    instructions: What should happen to this backordered line?
    criteria:
      wait: Inbound stock arrives soon; keep the customer informed
      substitute: Offer an equivalent item at the same price
      cancel_line_refund: Cancel the line and refund
      source_from_vendor: Drop-ship from the vendor at higher cost
      escalate: A person should contact the customer
bands:
  act: { confidence_gte: 0.9 }
  confirm: { confidence_gte: 0.6 }
  escalate: { otherwise: true }
`,
  cs_intent: `decision: cs_intent
version: 2
owner: customer-care
tier: T0
provider: llm.anthropic
timeoutMs: 1500
default: other
purpose: Classify an inbound customer message so the reply draft uses the right order facts.
state:
  include:
    - input.text
    - order.status
    - order.channel
questions:
  intent:
    type: choice
    instructions: What does the customer want from this message?
    criteria:
      wismo: Asks where the order is, when it arrives, or for tracking
      refund_request: Wants money back for an item or the whole order
      cancel_request: Wants to cancel before the order ships
      address_change: Wants the parcel delivered somewhere else
      damaged_item: Reports an item that arrived damaged or defective
      other: Not enough information to tell
bands:
  act: { confidence_gte: 0.7 }
  confirm: { confidence_gte: 0.5 }
  escalate: { otherwise: true }
`,
};
