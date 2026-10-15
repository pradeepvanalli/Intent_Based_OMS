# Maple & Main General Store — general retail reference implementation

Tenant id `retail` · name "Maple & Main General Store" · vertical "General retail". One of the three open-source
reference tenants (`retail`, `electronics`, `apparel`) that `scripts/bootstrap.sh` seeds next to `demo`
(`OMS_DEMO_TENANTS=demo,retail,electronics,apparel`). Log in on the login page with tenant **Maple & Main General Store**
and any dev user (`admin/admin`, `ops/ops`, `cs/cs`, `risk/risk`, `finance/finance`, `strategy/strategy`).

## 1. The business

Maple & Main is a Midwest general-merchandise retailer: home, kitchen, outdoor, toys, cleaning, a small wine wall,
knives, drop-shipped furniture, lithium power tools and gift cards. Two distribution centres (Columbus OH, Reno NV),
six stores that do counter pickup, curbside, ship-from-store and, in four cities, same-day courier, and one drop-ship
furniture vendor in North Carolina. Channels: its own web shop, two marketplaces, POS ship-to-home from the stores.

**Pain today.** The legacy OMS routes with a static per-channel rule ("web ships from the nearest node with stock").
Stores get picks they cannot fill (a lamp the system swears is on the shelf), and every reject is a phone call from
the store to a planner who re-keys the order. Aerosols get booked on air services and bounced by the carrier. A
marketplace fire pit to Hawaii ships at a loss because nobody looks at the margin before release. On the holiday
weekend the Denver store drowns in web orders while the Reno DC idles. Curbside promises "ready in 20 minutes" on a
banner, not from any knowledge of the store's queue. Returns are approved by hand, so a $12 towel costs $10 in label
and handling to get back.

**What Intent OMS does differently.** Every one of those is a *decision the business owns* rather than a rule a
developer hard-coded:

| Problem | Where it is decided | What the business edits |
|---|---|---|
| Store cannot pick | `store_reject` playbook runs the `store_reject_reroute` decision (T1 choice) before re-sourcing; the workflow re-sources with the rejecting store excluded and penalised (`sourcing.reject_penalty`) | the decision's L0 rules (marketplace → DC; pickup orders → ask) and criteria; the penalty |
| Hazmat by air, oversized to a PO box | L0 compliance rules (`rules/compliance.yaml`): `hazmat_no_air`, `hazmat_ground_signature`, `oversized_no_po_box`; sourcing only considers hazmat-certified nodes | the CEL rules, the node capabilities |
| Loss-making marketplace orders | `sourcing.margin_floor` 12 %; a plan below it waits in `margin_floor_approvals` for finance | the floor, the rate card, the role that approves |
| Holiday surge | `dailyCapacity` per node is a hard daily cap in the candidate generator; overflow lands on the DCs automatically | capacities in `nodes.yaml` or `PUT /api/v1/nodes/{id}` |
| Curbside ETA | `curbside_eta_promise` (T1 score 0–4) from live store health and basket size | the rubric, the rules, `pickup.curbside_promise_minutes` |
| Returns | `return_risk` L0 fast lanes: trusted customer → instant refund, `cheap_item_keep_it` (≤ $12), out-of-window denial, flagged customer → review | the thresholds, the flagged list, the window |

## 2. The network

| Node | Type | Where | Capabilities | Daily capacity | Cutoff (local) | Notes |
|---|---|---|---|---|---|---|
| `DC-COL` | dc | Columbus OH (39.9612, -82.9988) | ship, hazmat | 18 000 | 17:00 ET | main DC; aerosols and battery packs |
| `DC-RNO` | dc | Reno NV (39.5296, -119.8138) | ship, hazmat | 12 000 | 16:00 PT | west coast and Hawaii / Alaska |
| `ST-CHI` | store | Chicago, Lincoln Park (41.9214, -87.6513) | bopis, curbside, sfs, same_day | 160 | 14:00 CT | thin staffing, reject rate 8 %: the store that rejects |
| `ST-DAL` | store | Dallas, Uptown (32.8010, -96.8030) | bopis, curbside, sfs | 140 | 14:00 CT | |
| `ST-ATL` | store | Atlanta, Midtown (33.7838, -84.3830) | bopis, sfs, same_day | 120 | 13:30 ET | |
| `ST-DEN` | store | Denver, Cherry Creek (39.7177, -104.9535) | bopis, curbside, sfs, same_day | **60** | 14:00 MT | small on purpose: the surge scenario fills it |
| `ST-SEA` | store | Seattle, Ballard (47.6687, -122.3850) | bopis, curbside, sfs, same_day | 150 | 14:00 PT | |
| `ST-PHX` | store | Phoenix, Arcadia (33.5083, -111.9870) | bopis, sfs | 130 | 13:00 MST | no curbside, no same-day |
| `VND-GFF` | vendor | Hickory NC (35.7332, -81.3412) | ship | 400 | 12:00 ET | Great Falls Furniture Co., drop-ships sofas, dressers, bookcases |

Catalogue: 41 SKUs (`catalog.yaml`) with cogs, weight and the flags the rules read (`hazmat` aerosols and the
standalone battery pack, `ageRestricted` wine 21+ and knives 18+, `oversized` grills / rugs / furniture, `lithium`
tools and toys, `digital` gift card). Order types (`order-types.yaml`): `standard_ship`, `bopis`, `curbside` (pickup
variant requiring the `curbside` capability), `ship_from_store`, `same_day_local`, `drop_ship`, `backorder`.
Decisions: the fourteen starter decisions plus `store_reject_reroute` and `curbside_eta_promise`, each with a golden
set. Seeded data (`sample-orders.yaml`, `inventory.yaml`): 37 orders, 3 returns, 2 exceptions; format in
`docs/SAMPLE_ORDERS_FORMAT.md`.

## 3. The five scenarios

`scripts/reference/retail.sh` runs all five against `http://localhost:8080` (or `BASE=…`), one at a time with
`scripts/reference/retail.sh R3`. Every call below is what the script does; `$TOKEN` is `scripts/token.sh admin admin retail`.
The seeded orders already contain each scenario (keys in brackets), so the UI has something to show before you run anything.

### R1 — Store-reject reroute (seeded: `F01` ship-from-store, `B01` BOPIS)

1. Capture a `ship_from_store` order for 1 × `KIT-DUTCH-6QT` to 3300 N Clark St, Chicago. Chicago has 3, Dallas 6,
   Atlanta 4, so the plan releases to `ST-CHI`.
   ```bash
   curl -X POST $BASE/api/v1/orders -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d '{
     "channel":"web","channelOrderId":"R1-1","orderType":"ship_from_store","shipMethod":"ground","customerRef":"c1",
     "customer":{"ref":"c1","name":"Amelia Harris","tier":"gold","ageVerified":true,"age":40},
     "lines":[{"lineId":"1","sku":"KIT-DUTCH-6QT","qty":1,"unitPrice":{"amount":119.00,"currency":"USD"}}],
     "addresses":[{"type":"ship_to","line1":"3300 N Clark St","city":"Chicago","state":"IL","zip":"60657","country":"US"}],
     "tenders":[{"type":"card","token":"tok_visa_ok","amount":{"amount":131.00,"currency":"USD"}}]}'
   ```
2. The store rejects: `POST /webhooks/wms/ST-CHI {"eventId":"…","type":"store_rejected","orderId":"<id>"}`.
3. The workflow fires `event.store_rejected` (line back to `awaiting_sourcing`, reservation released, node penalised),
   calls `resource` with `ST-CHI` excluded → the new group is at the next store with stock, raises a `store_reject`
   exception whose `store_reject` playbook evaluates `store_reject_reroute` before its `resource` step.
4. `POST /api/v1/decisions/store_reject_reroute/evaluate-now {"input":{"exception":{…},"order":{…}},"mode":"LIVE"}`
   shows the choice; with `order.channel = marketplace` the L0 rule `marketplace_ships_from_dc` answers without a model.

**UI:** Orders → the order → *Fulfilment groups* (one `rejected` at ST-CHI, one `released` elsewhere), *Exceptions*
(type `store_reject`, queue `store_ops`, playbook steps), *Decisions → Why* on `store_reject_reroute`. Settings → Nodes
shows the penalty on ST-CHI. The seeded BOPIS order `B01` shows the pickup case: no other pickup store within range, so
the line goes to `backordered` and the decision's `ask_customer` rule is what a second reject would trigger.

### R2 — Hazmat + oversized routing (seeded: `H01`)

1. Capture 2 × `CLN-SPRAY-DISINF` (aerosol, class 2.1) + 1 × `OUT-COOLER-45` (oversized) to Indianapolis by `ground`.
   Stores are nearer but lack the `hazmat` capability, so only `DC-COL` / `DC-RNO` are candidates
   (`GET /api/v1/orders/{id}/sourcing/options` → `requiredCapabilities: [hazmat]`); rule `hazmat_ground_signature`
   adds a signature requirement.
2. The same basket with `"shipMethod":"two_day"` is refused at capture: 422 `validation_failed`, issue `HAZMAT_AIR`.
3. `OUT-GRILL-22` to an address with `"addressType":"po_box"` is refused: `OVERSIZED_PO_BOX`.

**UI:** the order's line badges (hazmat, oversized, signature), the fulfilment group at a DC; Settings → Compliance
rules to edit the CEL.

### R3 — Margin-floor marketplace order (seeded: `M01` fire pit to Honolulu, `M02` mops to Anchorage)

1. Capture a `marketplace` order for 1 × `OUT-FIREPIT` ($159.00, 14 kg) to Honolulu HI, `ground`. The cheapest plan
   (Reno, oversized band to Hawaii ≈ $82) leaves a contribution margin well under 12 %.
2. `GET /api/v1/orders/{id}/sourcing/plans` lists every plan with `marginRatio` and `meetsMarginFloor: false`; the
   workflow parks the order and opens a `margin_floor_approval` task in `margin_floor_approvals`.
3. Finance decides: `POST /api/v1/tasks/{taskId}/complete {"outcome":"approved","reason":"…"}` as `finance/finance`
   → `order.margin_approved`, the plan is released; `"rejected"` cancels instead.

**UI:** Orders → margin card (ratio vs floor, approval present), Tasks → `margin_floor_approvals`, Analytics →
`margin_floor_compliance`. Change `sourcing.margin_floor` in Settings → Config and replay the order in Simulation.

### R4 — Holiday surge capacity (seeded: `G01`–`G08`)

1. Eight web orders in twenty minutes, all Denver metro, 10 units each (6 × `TOY-PUZZLE-500` + 4 × `TOY-PLUSH-FOX`).
   `ST-DEN` carries the stock and is the cheapest node for Denver, but `dailyCapacity: 60` counts units released since
   midnight UTC, so after the sixth order the candidate generator drops the store and the plans move to `DC-RNO` /
   `DC-COL` — no planner, no phone call.
2. `GET /api/v1/orders?nodeId=ST-DEN` vs `?nodeId=DC-RNO`, or `GET /api/v1/control-tower/pipeline?nodeId=ST-DEN`.

**UI:** Control Tower → pipeline by node; Orders list filtered by node and promise date. Raise the cap for the weekend
with `PUT /api/v1/nodes/ST-DEN` (`dailyCapacity`) and watch the next batch stay local; the `curbside_eta_promise`
decision reads the same backlog, so curbside promises degrade honestly on the same afternoon (R5).

### R5 — Curbside promise (seeded: `C01` picked up, `C02` released)

1. Capture a `curbside` order at `ST-DEN` (`shipMethod: pickup`, `addresses[type=pickup].line1 = ST-DEN`,
   `extensions.pickupNodeId`, `pickupMode: curbside`, `vehicle`, `arrivalMinutes`). Only stores with the `curbside`
   capability qualify; pickup is free and same-day.
2. `POST /api/v1/decisions/curbside_eta_promise/evaluate-now` with `store{backlog_units, staffing, pick_rate,
   minutes_to_cutoff, open}`, `order{units, line_count, has_oversized, promise_minutes}`, `customer{arrival_minutes}`
   → score 0–4 (`idle_store_small_basket` → 4 by rule; a surge afternoon scores 0–1 and the banner should not promise
   20 minutes).
3. Store events: `POST /webhooks/wms/ST-DEN {"type":"staged_for_pickup"}` then `{"type":"picked_up"}` → payment is
   captured at pickup; an uncollected order is cancelled and voided after `orchestration.pickup_window` (P4D).

**UI:** the order timeline (`released → picked → ready_for_pickup → picked_up`), payments captured at pickup, the
`curbside_eta_promise` version and golden set in Policy Studio.

### Returns (seeded)

`D01` towels ($24, receipt, first return) → `trusted_customer` fast lane, instant refund. `D02` delivered 45 days ago →
`deny_out_of_window` (L0, no model call). `D03` flagged customer (`returns.flagged_customers`) claiming "box arrived
empty" → score 4, `risk_review` task. Returns → each RMA → *Why* shows the rule that fired.

## 4. What to change to make it yours

1. **Network** (`nodes.yaml`): your node ids, coordinates, cutoffs, `dailyCapacity` and capabilities. Keep ids stable
   (webhooks address `/webhooks/wms/{nodeId}`). Drop `curbside` from stores that do not offer it; the `curbside` order
   type then cannot route there.
2. **Catalogue** (`catalog.yaml`): your SKUs with `cogs`, `weightKg` and the flags. The flags are what the compliance
   rules and sourcing read; a wrong `hazmat: false` is a carrier fine.
3. **Money** (`tenant-config.yaml`): `sourcing.margin_floor`, `sourcing.split_penalty_usd`, `tax.rates`,
   `returns.window_days`, `returns.keep_it_max_value_usd` (and the matching `cheap_item_keep_it` rule in
   `decisions/return_risk.yaml`), `appeasement.role_limits`, `guardrail.*`. The keys under `pickup.*` and `store.*` are
   this tenant's own vocabulary; the decisions' comments say which rule reads which.
4. **Rate card** (`shipping-rates.yaml`): your negotiated rates and zones. Every plan score and the margin floor depend
   on it; the oversized band is where general merchandise loses money.
5. **Compliance** (`rules/compliance.yaml`, `compliance.restricted_states`): review the alcohol state list with legal;
   add categories (tobacco, CBD, live plants) as rules or as `restrictedStates` on the SKU.
6. **Decisions**: read `store_reject_reroute` and `curbside_eta_promise` with store operations, adjust the rules and
   criteria, extend the golden sets with your own cases, then publish through the studio. The starter decisions carry
   production evaluation gates (0.90 accuracy / 0.05 calibration); without an AI key the heuristic fallback will not
   pass them, so bootstrap with `scripts/new-tenant.sh <id> "<name>" --from config/tenants/retail --publish emergency`.
7. **Playbooks and queues** (`playbooks.yaml`, `exception-taxonomy.yaml`): map `store_ops`, `regional_manager`,
   `loss_prevention_lead` to real teams; every queue a playbook names must exist.
8. **Sample data**: edit `sample-orders.yaml` / `inventory.yaml` or delete them (the seeder then loads config only).
   Validation names the order key and field; unknown skus, nodes, order types or exception types fail the tenant's seed.
9. **Agents** (`agents/agents.yaml`): the two placeholders are harmless; register only the agents you work with.
10. **Secrets** through the API, never in this folder: `webhooks.inbound_secret`, marketplace secrets, alert webhooks.
