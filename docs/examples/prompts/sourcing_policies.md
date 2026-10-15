# Sourcing and promise policies

### Gifts ship together

> Anything that looks like a gift should ship in one box even if it is slower.

Expected: no new rule needed; the copilot shows that `fulfilment_intent` already has `is_gift` (Noul) and the
`ship_complete` option "Everything together in one delivery, even if it arrives later", and offers a criteria
sharpening draft: "Everything together in one delivery, even if it arrives later; gifts, house moves and events
usually want this". Impact: flips from `ship_as_available` to `ship_complete` on gift-like notes.

### Margin floor

> Nothing should ship below a 15% contribution margin without finance signing off.

Expected: tenant setting draft `sourcing.margin_floor: 0.15` (`PUT /api/v1/admin/config/sourcing.margin_floor` with a
reason), with an estimate of how many of the last 30 days' orders would have hit the `margin_floor_approvals` queue.
Reviewer: the finance controller.

### Prefer a node

> Prefer the New Jersey DC for east-coast orders unless it is more than a day slower.

Expected: the copilot explains that node preference is economics plus the `sourcing_node_score` decision, not a hard
rule, and proposes (a) checking `DC-NJ` health and cost profile, (b) a `sourcing.weights` change raising `time`, and
(c) if a hard preference is wanted, a post-check on `sourcing_node_score` is not the right tool; it suggests
`order-types.yaml` `sourcing.nodeTypes`/`capabilities` constraints or a per-state `compliance.restricted_states`-style
key as the nearest fit and flags the gap.

### Same-day radius

> Offer same-day delivery only within 25 km of a store.

Expected: `order-types.yaml` draft for `same_day_local` with `sourcing: { capabilities: [same_day], maxDistanceKm: 25 }`
and the matching `sourcing.order_type_constraints` tenant key; plus a `carrier_service_choice` rule draft if that
example decision is installed (`same_day_outside_radius` with 25).

### Split tolerance

> Silver customers who complained about split shipments before should never get a split again.

Expected: `split_acceptability` L0 rule `when: "has(input.customer.history.split_complaints) && input.customer.history.split_complaints > 0 && input.customer.tier == 'silver'"`,
`then: false`, `reason: prior_split_complaint`. Reviewer: whether to drop the tier condition.
