# Bootstrap a tenant from a business description

## Prompt

> We are an outdoor-gear retailer selling online and in six stores across the US. We ship from two distribution
> centres (Nevada and New Jersey), offer buy-online-pick-up-in-store at every store, and ship from stores when a DC
> is out of stock. Standard returns are 30 days with a receipt; clearance items are final sale. We do not sell
> alcohol or tobacco. Gift orders should ship in one box. We want finance to approve anything that ships below a 10%
> margin. Marketplace orders should always ship complete.

## Expected outcome

A tenant configuration proposal (the same files as `config/tenants/starter`), delivered as drafts for review:

* **Order types**: `standard_ship`, `bopis`, `ship_from_store` (sourcing `nodeTypes: [store]`); `drop_ship`,
  `same_day_local`, `pre_order`, `backorder` omitted or marked optional. `channelDefaults` with
  `marketplace: ship_complete`, `web: ship_as_available`.
* **State models**: the six standard models unchanged (the vocabulary is what the workflows drive).
* **Nodes**: `DC-NV`, `DC-NJ` placeholders with `capabilities: [ship]`; six `STORE-00n` placeholders with
  `[bopis, sfs]` and a note to fill coordinates and cutoffs.
* **Tenant config**: `sourcing.margin_floor: 0.10`, `returns.window_days: 30`, `sourcing.channel_default_policy`
  as above.
* **Compliance rules**: the hazmat, lithium, export and restricted-ZIP rules kept; alcohol and tobacco rules kept
  but noted as inert since no such categories exist (or removed, flagged for the reviewer).
* **Decisions**: the fourteen starter decisions with T1 `fulfilment_intent` unchanged (gift → `ship_complete` is
  already criteria and a Noul `is_gift`), `return_risk` with the existing `final_sale` and `out_of_window` rules.
* **Playbooks, SLAs, queues**: starter defaults.

Reviewer checks: node coordinates and cutoffs, the real catalogue, the margin floor against finance's number, and
that no rule was dropped silently.

## Variant

> Same as above, but we are B2B only: net-30 terms, no returns without an RMA approved by sales, orders over
> $10,000 need a sales manager's approval before release.

Expected: `channelDefaults: { b2b: ship_complete }`; a `return_risk` L0 rule `then: escalate` for every B2B return
(`input.order.channel == 'b2b'`) routed to a `sales_review` queue (the copilot proposes the queue); a sourcing
post-check or workflow hold proposal for orders above $10,000 (the copilot explains that release approvals above a
value are a hold placed by a playbook on `order.captured`, and drafts the playbook `b2b_high_value_review` with
`hold` blocking `release` plus `create_task` to `sales_review`).
