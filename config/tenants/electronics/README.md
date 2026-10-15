# Voltline Electronics — reference tenant for consumer electronics

`config/tenants/electronics/` is a complete, internally consistent tenant for a high-average-order-value electronics
seller. Load it the way you load the demo (`OMS_DEMO_TENANTS=demo,electronics OMS_DEMO_SEED=true`, which also captures
`sample-orders.yaml` and feeds `inventory.yaml`) or through the API with
`scripts/new-tenant.sh electronics "Voltline Electronics"`. Then run `scripts/reference/electronics.sh` for the five
scenarios below, or `scripts/reference/electronics.sh E3` for one of them.

| | |
|---|---|
| Tenant id | `electronics` |
| Name / vertical | Voltline Electronics / Consumer electronics (`tenant-config.yaml` carries `name`, `vertical`, `description` as metadata) |
| Dev login | `admin` / `admin` on tenant `electronics` (`scripts/token.sh admin admin electronics`) |

## The business

Voltline sells phones, laptops, TVs, monitors, audio, chargers, loose battery packs, accessories, gift cards, extended
warranties and certified-refurbished devices: about 35 SKUs, $540 average order, web, marketplace and agent channels.
It ships from two DCs (Memphis, Sparks) and four stores, drop-ships TVs and monitors from Lumen Display Co., and runs a
refurbishment centre in Louisville that receives every warranty RMA and stocks the refurbished range.

**Pain with the legacy OMS.** Every policy that matters is a hard-coded flag or a spreadsheet next to the system:

* **Launch day.** A new phone opens for pre-order with 10 units in the first wave and 12 orders in 14 minutes. The old
  OMS allocates by whoever's order the batch job sees first; a gold member who ordered a few minutes late is in the same
  queue as everyone else, and the merchandising team cannot say *why* an order went to wave 2.
* **High-value fraud.** A $2,399 laptop addressed to a Doral freight forwarder with the note "will re-export" ships
  because address verification said "deliverable". Export control is a PDF the trade-compliance desk keeps.
* **Lithium.** A loose power bank booked on a next-day air service is caught by the carrier, not the OMS; a pack
  addressed to a PO box bounces back a week later.
* **Backorders.** A sold-out laptop with 60 units on a PO sits in "backordered" for three weeks; nobody reads the
  customer's "need it by Friday or cancel" until the chargeback.
* **Bundles.** The phone ships from Memphis, the case from the Chicago store, the charger follows two days later.
* **Vendor drop-ship.** Lumen's ASN does not arrive; the first signal is the customer asking where the TV is.
* **Warranty returns.** Defective, buyer's remorse and transit damage all land in the same inspection bin with the same
  refund and the same restocking fee; RTV claims against the vendor are a monthly spreadsheet.

**What changes with Intent OMS.** Each of those is a typed decision or an L0 rule the business owns:

| Policy | Where it lives | Tier |
|---|---|---|
| Fair pre-order allocation by order time, loyalty protection for gold | `decisions/preorder_allocation.yaml` (rules `in_wave`, `loyalty_protection`, `payment_declined`, `reseller_burst`), `inventory.yaml` wave stock, `order_line.preorder` state model | T2 |
| High-value address risk | `decisions/high_value_address_risk.yaml` (Score 0–4) + `rules/compliance.yaml` `export_controlled_to_forwarder`; playbook `high_value_review` → queue `risk_review` | T2 |
| Lithium: no air for loose packs, no PO boxes, adult signature; export control; signature above $500 | `rules/compliance.yaml` (12 rules; `block` rejects capture, `hold` captures and blocks release) | L0 |
| Backorder next action: wait / substitute / cancel / split | `decisions/backorder_next_action.yaml`; SLA `backordered P7D → decision.backorder_next_action`; `order_line.preorder` transitions on the answer | T1 |
| Bundles ship in one box | order type `bundle_ship_complete`: every channel default `ship_complete`, no split decisions, `fulfilment_intent` rule `bundle_always_complete` | — |
| Vendor SLA | `sla.yaml` `drop_ship released PT48H → playbook.vendor_repromise`; exception type `vendor_sla_breach`; queue `vendor_ops` | — |
| Warranty triage and disposition | `decisions/warranty_triage.yaml` (defective / buyer_remorse / transit_damage), `decisions/return_disposition.yaml` (refurbish / return_to_vendor / destroy / restock), `returns.reason_taxonomy` | T1 |
| Trade-in credit, marketplace channel, serial numbers | line discount + `extensions.tradeIn`; channel `marketplace` with `ship_complete` default; `extensions.serials` / `extensions.imeis` per line id (`extension-schema.json`) | — |

## Network

| Node | Type | Where | Capabilities | Role |
|---|---|---|---|---|
| `DC-MEM` | dc | Memphis TN 38118 | ship, hazmat | Primary DC; late 18:00 cutoff; holds launch wave stock and loose battery packs |
| `DC-SPK` | dc | Sparks NV 89431 | ship, hazmat | West-coast DC |
| `ST-AUS-01` | store | Austin TX 78758 | ship, bopis, sfs, same_day | Ship-from-store (accessories, phones); no hazmat |
| `ST-CHI-02` | store | Chicago IL 60611 | ship, bopis, sfs, same_day | Ship-from-store |
| `ST-NWK-03` | store | Newark NJ 07105 | ship, bopis, sfs | Ship-from-store; weakest health signals (reject rate 9 %) |
| `ST-SJC-04` | store | San Jose CA 95128 | ship, bopis, sfs, same_day | Ship-from-store |
| `VND-LUMEN` | vendor | Carson CA 90746 | ship | Drop-ship vendor for TVs and monitors; 48 h ship SLA |
| `RFB-LOU` | 3pl | Louisville KY 40209 | ship, hazmat | Refurbishment centre: receives RMAs, stocks `RFB-*` SKUs |

## Files

| File | What it configures |
|---|---|
| `tenant-config.yaml` | Metadata (`name`, `vertical`, `description`) and runtime keys: 8 % margin floor, two-package cap, $14 split penalty, 15 % restocking fee on opened/damaged only, refunds above $1,000 need approval, `returns.reason_taxonomy`, launch policy knobs (`preorder.*`), vendor SLA hours |
| `order-types.yaml` | `standard_ship`, `ship_from_store`, `pre_order`, `backorder`, `drop_ship`, `bundle_ship_complete` |
| `state-models/` | `order.standard_ship`, `order_line.standard_ship`, `order_line.preorder` (backorder decision transitions, substitute offer), `return.standard`, `shipment.standard` |
| `decisions/` | 17 decisions: the starter's fourteen adapted to electronics, `backorder_next_action` rewritten (wait / substitute / cancel / split), plus `preorder_allocation`, `warranty_triage`, `high_value_address_risk` |
| `golden-sets/` | 100 labelled cases: `fulfilment_intent`, `exception_classify`, `return_risk`, `preorder_allocation`, `warranty_triage`, `high_value_address_risk`, `backorder_next_action` |
| `rules/compliance.yaml` | Lithium (loose packs never fly, PO-box hold, bulk-air review, adult signature), export control (non-US block, forwarder hold), adult signature at $500+, oversized (no PO box, no air), restricted ZIPs |
| `catalog.yaml` | 35 SKUs with `lithium`, `hazmat`/`hazmatClass`, `exportControlled`, `oversized`, `digital`, `finalSale`, `category` and informational `serialised`, `releaseDate`, `batteryWh`, `vendorReturns` |
| `nodes.yaml`, `shipping-rates.yaml` | The network above; parcel + oversized freight rate card with 30 kg band |
| `playbooks.yaml` | `vendor_repromise`, `carrier_damage_claim`, `high_value_review`, `launch_allocation`, `substitute_reminder`, `rtv_claim`, `carrier_exception` plus the standard set; spend limits $400 reship / $1,000 refund |
| `sla.yaml` | Per order type incl. `drop_ship released PT48H`, `pre_order backordered P7D`, bench inspection within 2 days |
| `exception-taxonomy.yaml` | Types incl. `carrier_damage`, `vendor_sla_breach`, `allocation_shortfall`, `high_value_address`, `warranty_rtv`; queues incl. `vendor_ops`, `launch_desk`, `returns_ops` |
| `agents/agents.yaml` | Marketplace bot (UCP, $3,000), concierge assistant (ACP, $1,500, confirm every order), internal ops MCP agent |
| `inventory.yaml` | Positions that make the scenarios work: 10 Nova X2 on hand + 350 on ASN, Aero 14 sold out with 60 on PO, TVs at the vendor, refurbished at `RFB-LOU` |
| `sample-orders.yaml` | 33 orders (12 launch pre-orders, forwarder laptop, PO-box battery pack, bundle, drop-ship TVs, three backorders, trade-in, marketplace, ship-from-store with a store reject, declined card, refurbished, gift cards), 3 returns, 2 exceptions |
| `extension-schema.json` | JSON Schema for `order.extensions` (serials, IMEIs, bundleId, tradeIn, launch, warranty, marketplace); install with `PUT /api/v1/extension-schema` |

## Scenarios

All calls assume:

```bash
BASE=http://localhost:8080
TOKEN=$(scripts/token.sh admin admin electronics "$BASE")
H=(-H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" -H "X-Tenant-Id: electronics")
```

`scripts/reference/electronics.sh` runs E1–E5 end to end and prints the order ids and the UI links; the snippets below
are the load-bearing calls of each scenario.

### E1 · Launch-day pre-order allocation

Twelve pre-orders for `PHN-NOVA-X2-256` arrive within 14 minutes (`sample-orders.yaml` L01–L12, `placedAt`
16:00:04 → 16:14:09 UTC). Ten units are on hand at `DC-MEM`; 350 are on an ASN and not sellable
(`inventory.include_inbound: false`). The first ten orders by time release; L11 and L12 land in `backordered`. L12 is a
gold member.

```bash
# one of the twelve (the gold member who was late)
curl -s -X POST "$BASE/api/v1/orders" "${H[@]}" -d '{
  "channel":"web","channelOrderId":"VL-E1-12","orderType":"pre_order","customerRef":"cust-vl-3012",
  "customer":{"ref":"cust-vl-3012","name":"Theodore Nakamura","email":"theo.n@example.com","tier":"gold","history":{"orders_12m":11,"returns_12m":0,"split_acceptance_rate":0.2}},
  "lines":[{"lineId":"1","sku":"PHN-NOVA-X2-256","qty":1,"unitPrice":{"amount":1099.00,"currency":"USD"}}],
  "addresses":[{"type":"ship_to","name":"Theodore Nakamura","line1":"950 Mass Ave","line2":"Apt 9B","city":"Cambridge","state":"MA","zip":"02139","country":"US"}],
  "tenders":[{"type":"card","token":"tok_visa_ok","amount":{"amount":1175.93,"currency":"USD"}}],
  "intentChoices":["ship_complete"],"shipMethod":"ground",
  "notes":[{"source":"customer","text":"Gold member since 2021, pre-ordered the minute I saw the email."}],
  "extensions":{"launch":{"campaign":"nova-x2-launch","waveRequested":1}}}'

# the launch desk asks the T2 decision about a waiting order (rank 12, gold, two protected units left)
curl -s -X POST "$BASE/api/v1/decisions/preorder_allocation/evaluate-now" "${H[@]}" -d '{
  "subjectType":"order","subjectId":"<order id of L12>","mode":"LIVE",
  "input":{"order":{"launch_rank":12,"qty":1,"minutes_after_launch":14,"channel":"web","payment_status":"authorized","notes":""},
           "customer":{"tier":"gold","history":{"orders_12m":11,"preorders_cancelled_12m":0},"orders_in_launch":1},
           "allocation":{"wave_units":10,"wave_units_remaining":0,"protected_units_remaining":2,"protected_tiers":["gold"],"next_wave_eta_days":14}}}'
# → layer L0_RULE, rule loyalty_protection, value allocate_now. The same call for L11 (standard tier) → waitlist.
```

**Look at:** the order page of L10 (released from `DC-MEM`) next to L11 (line in `backordered`, SLA 7 days →
`backorder_next_action`); the Why drawer on `preorder_allocation` for L12 (`rulesFired: loyalty_protection`,
`reasonCodes: loyalty_tier_allocation_protection`) and for L11 (`fair_allocation_by_order_time` did not fire, model
answered `waitlist`); the `launch_desk` queue for anything the rules escalated (`reseller_burst`). In the studio, open
`preorder_allocation`: the wave size and the protected tiers are inputs, the fairness policy is two rules the
merchandising team can change with two approvals.

### E2 · High-value address risk hold

A $2,399 `LAP-FORGE-16` (export-controlled) to a freight forwarder in Doral FL, note "ship to my forwarder, will
re-export" (`sample-orders.yaml` H01).

```bash
curl -s -X POST "$BASE/api/v1/orders" "${H[@]}" -d '{
  "channel":"web","channelOrderId":"VL-E2-1","orderType":"standard_ship","customerRef":"cust-vl-3020",
  "customer":{"ref":"cust-vl-3020","name":"R. Albrecht","email":"r.albrecht.import@example.com","tier":"standard","history":{"orders_12m":0,"returns_12m":0,"split_acceptance_rate":0.5}},
  "lines":[{"lineId":"1","sku":"LAP-FORGE-16","qty":1,"unitPrice":{"amount":2399.00,"currency":"USD"}}],
  "addresses":[{"type":"ship_to","name":"R. Albrecht c/o SkyBridge Freight Forwarding","line1":"8400 NW 36th St","line2":"Suite 450 - Box 11923","city":"Doral","state":"FL","zip":"33166","country":"US","addressType":"freight_forwarder"}],
  "tenders":[{"type":"card","token":"tok_visa_ok","amount":{"amount":2542.94,"currency":"USD"}}],
  "intentChoices":["ship_complete"],"shipMethod":"two_day",
  "notes":[{"source":"customer","text":"ship to my forwarder, will re-export"}]}'

curl -s "$BASE/api/v1/orders/<id>/view" "${H[@]}"      # holds[]: compliance EXPORT_FORWARDER_REVIEW blocking release; warnings HIGH_VALUE_ADULT_SIGNATURE, FORWARDER_ADULT_SIGNATURE

curl -s -X POST "$BASE/api/v1/decisions/high_value_address_risk/evaluate-now" "${H[@]}" -d '{
  "subjectType":"order","subjectId":"<id>","mode":"LIVE",
  "input":{"address":{"verification_status":"unverified","type":"freight_forwarder","freight_forwarder_match":true,"po_box":false,"prior_failed_deliveries":0,"matches_billing":false},
           "order":{"total":2542.94,"ship_method":"two_day","notes":"ship to my forwarder, will re-export","export_controlled":true,"item_categories":["laptop"]},
           "customer":{"history":{"orders_12m":0,"chargebacks_12m":0},"account_age_days":1},"payment":{"avs_match":true}}}'
# → L0_RULE forwarder_with_export_controlled_item, value 4

# score 3–4 → playbook high_value_review (address_risk hold + risk_review task); raise it as the typed exception
curl -s -X POST "$BASE/api/v1/exceptions" "${H[@]}" -d '{"source":"cs","code":"HIGH_VALUE_ADDRESS","type":"high_value_address","orderId":"<id>","message":"high_value_address_risk scored 4"}'
```

Try the loose battery pack on air for the hard stop (capture is rejected, reason `LITHIUM_AIR`), and the PO-box pack
for the soft one (captured, compliance hold `LITHIUM_PO_BOX`, playbook `address_fix` asks for a street address):

```bash
curl -s -X POST "$BASE/api/v1/orders" "${H[@]}" -d '{"channel":"web","channelOrderId":"VL-E2-AIR","customerRef":"c1","customer":{"ref":"c1","name":"A","tier":"standard"},
  "lines":[{"lineId":"1","sku":"BAT-PWR-27K","qty":1,"unitPrice":{"amount":89.00,"currency":"USD"}}],
  "addresses":[{"type":"ship_to","name":"A","line1":"1 Main St","city":"Boise","state":"ID","zip":"83702","country":"US"}],"shipMethod":"next_day"}'
# → 4xx, issues[]: LITHIUM_AIR (block). Sample order P01 is the PO-box version: captured with a hold.
```

**Look at:** the Holds card (compliance `EXPORT_FORWARDER_REVIEW`, then `address_risk` from the playbook), the
Compliance panel with the two signature warnings, the Why drawer on `high_value_address_risk`, the `risk_review`
queue. Release the hold with a resolution to see the line move from `awaiting_sourcing` to `released`.

### E3 · Backorder next action from a customer note

`LAP-AERO-14` is sold out with 60 units on a PO (`inventory.yaml`). Three `backorder` orders (B01–B03) carry three
different notes. The line model's `backordered` SLA (P7D) evaluates `backorder_next_action` on breach; the same call,
with the same input, is what the script makes immediately.

```bash
curl -s -X POST "$BASE/api/v1/orders" "${H[@]}" -d '{
  "channel":"web","channelOrderId":"VL-E3-FRIDAY","orderType":"backorder","customerRef":"cust-vl-3026",
  "customer":{"ref":"cust-vl-3026","name":"Tomás Herrera","email":"tomas.h@example.com","tier":"silver","history":{"orders_12m":3,"returns_12m":1,"split_acceptance_rate":0.7}},
  "lines":[{"lineId":"1","sku":"LAP-AERO-14","qty":1,"unitPrice":{"amount":1299.00,"currency":"USD"}}],
  "addresses":[{"type":"ship_to","name":"Tomás Herrera","line1":"500 W 2nd St","city":"Austin","state":"TX","zip":"78701","country":"US"}],
  "tenders":[{"type":"card","token":"tok_visa_ok","amount":{"amount":1380.19,"currency":"USD"}}],
  "shipMethod":"two_day","notes":[{"source":"customer","text":"need it by Friday or cancel"}]}'

curl -s -X POST "$BASE/api/v1/decisions/backorder_next_action/evaluate-now" "${H[@]}" -d '{
  "subjectType":"order_line","subjectId":"<id>:1","mode":"LIVE",
  "input":{"line":{"sku":"LAP-AERO-14","category":"laptop","days_backordered":7,"expected_restock_days":9,"substitute_available":true,"substitute_sku":"LAP-FORGE-16"},
           "order":{"channel":"web","intent":"ship_as_available","notes":"need it by Friday or cancel","other_lines_in_stock":0,"needed_by_days":3},
           "customer":{"tier":"silver"},"network":{"atp_elsewhere":0}}}'
# → L0_RULE deadline_already_missed, value cancel.
# "happy to wait, don't substitute" → rule customer_declined_substitute → wait
# "send whatever is in stock now" with other_lines_in_stock 2 → model → split (T1, act ≥ 0.8)
```

**Look at:** the three order pages side by side (all three lines in `backordered`, promise date from the PO ETA);
the Why drawer per order (which rule fired, or the model's confidence and the `unclear` escape); for B02, cancel the
line with the decision as the reason and watch `void_or_refund` in the timeline. In the studio, the three rules are
the policy: edit the regex in `customer_declined_substitute`, run the impact diff on the last 30 days of real inputs.

### E4 · Warranty triage and disposition

`W01` (headphones, delivered 12 days ago) comes back with "left ear cuts out"; `W02` (phone, 3 days) with "changed
my mind, box unopened"; `W03` (drop-shipped TV) with "screen cracked, box was crushed on arrival".

```bash
curl -s -X POST "$BASE/api/v1/returns" "${H[@]}" -d '{"orderId":"<W01 id>","channel":"web","receiptPresent":true,
  "lines":[{"lineId":"1","qty":1,"reasonCode":"defective","customerReasonText":"left ear cuts out"}]}'
curl -s -X POST "$BASE/api/v1/returns/<rma>/decide"  "${H[@]}"          # return_risk: rule trusted_customer_defect → score 0, instant refund timing
curl -s -X POST "$BASE/api/v1/returns/<rma>/scan"    "${H[@]}"
curl -s -X POST "$BASE/api/v1/returns/<rma>/receive" "${H[@]}" -d '{"nodeId":"RFB-LOU"}'

curl -s -X POST "$BASE/api/v1/decisions/warranty_triage/evaluate-now" "${H[@]}" -d '{
  "subjectType":"return","subjectId":"<rma>","mode":"LIVE",
  "input":{"return":{"customer_reason_text":"left ear cuts out","reason_code":"defective","days_since_delivery":12,"item_condition":"like_new","box_opened":true,"inspection_notes":"intermittent left channel reproduced on bench"},
           "item":{"category":"audio","serial_matches_order":true},"shipment":{"carrier_exception":false,"oversized":false}}}'
# → defective. W02 input (box_opened false, changed_mind) → L0_RULE sealed_box_remorse → buyer_remorse.
#   W03 input (item_condition damaged, shipment.carrier_exception true) → L0_RULE carrier_exception_on_file → transit_damage.

curl -s -X POST "$BASE/api/v1/returns/<rma>/inspect" "${H[@]}" -d '{"condition":"like_new","note":"triage=defective; intermittent left channel reproduced on bench, serial matches order"}'
curl -s -X POST "$BASE/api/v1/returns/<rma>/disposition" "${H[@]}"      # return_disposition: rule defective_rtv → return_to_vendor
curl -s "$BASE/api/v1/returns/<rma>/customer-message" "${H[@]}"         # GR-15 customer-safe text, typed slots only
```

**Look at:** the RMA page timeline (requested → approved → awaiting_receipt → received → inspected), the Why drawers on
`return_risk` (fast lane), `warranty_triage` and `return_disposition` (RTV), the `vendor_ops` queue for the RTV claim
(`rtv_claim` playbook). W02 restocks with no restocking fee (sealed box, rule `unopened_restock`); W03 opens a
`carrier_damage` exception and the TV goes `destroy` or `return_to_vendor` depending on the bench. The refund of a TV
above $1,000 waits in `refund_approvals` (`returns.refund_requires_approval_above_usd`).

### E5 · Drop-ship vendor SLA breach re-promise

`V01` is a 65in OLED drop-shipped by Lumen (`drop_ship` sources `nodeTypes: [vendor]`). `sla.yaml` gives the vendor
48 hours in `released`; the breach runs `vendor_repromise`. The seeder raises the breach as exception X01; live, the
SLA sweeper does.

```bash
curl -s -X POST "$BASE/api/v1/orders" "${H[@]}" -d '{
  "channel":"web","channelOrderId":"VL-E5-1","orderType":"drop_ship","customerRef":"cust-vl-3023",
  "customer":{"ref":"cust-vl-3023","name":"Nathan Price","email":"nathan.p@example.com","tier":"silver","history":{"orders_12m":3,"returns_12m":0,"split_acceptance_rate":0.5}},
  "lines":[{"lineId":"1","sku":"TV-OLED-65","qty":1,"unitPrice":{"amount":1799.00,"currency":"USD"}},{"lineId":"2","sku":"WTY-TV-4Y","qty":1,"unitPrice":{"amount":199.00,"currency":"USD"}}],
  "addresses":[{"type":"ship_to","name":"Nathan Price","line1":"4100 Spruce St","city":"Philadelphia","state":"PA","zip":"19104","country":"US"}],
  "tenders":[{"type":"card","token":"tok_visa_ok","amount":{"amount":2117.88,"currency":"USD"}}],
  "intentChoices":["ship_complete"],"shipMethod":"ground"}'

# 52 h later, no ASN from the vendor
curl -s -X POST "$BASE/api/v1/exceptions" "${H[@]}" -d '{"source":"wms","code":"VENDOR_SHIP_SLA_MISSED","type":"vendor_sla_breach","orderId":"<id>","nodeId":"VND-LUMEN",
  "message":"Lumen Display EDI 856 not received 52 hours after release; vendor ship SLA is 48 hours, revised ship date +5 days","details":{"hoursSinceRelease":52,"vendorRevisedShipDays":5}}'
curl -s "$BASE/api/v1/exceptions/<exception id>" "${H[@]}"              # queue vendor_ops, playbook vendor_repromise, steps repromise → contact_customer → create_task → notify
curl -s "$BASE/api/v1/orders/<id>/view" "${H[@]}"                        # promiseDate moved, delay notice in the timeline
```

**Look at:** the exception page (playbook steps with their status), the order's promise card and timeline (re-promise
and `vendor_delay_notice`), the `vendor_ops` queue, the control tower at-risk list (`sla_breach_risk` scores the order
4: vendor past its SLA). The last playbook step re-sources after a further 24 hours when a DC stocks the SKU: it will
for `TV-LED-43` and `MON-4K-27`, and honestly will not for the vendor-only OLED.

## Make it yours

1. **Network** (`nodes.yaml`): your node ids, coordinates, cutoffs, capacities; keep `hazmat` only on nodes certified
   for loose lithium; mark your vendor nodes `type: vendor` so `drop_ship` can find them.
2. **Catalogue** (`catalog.yaml`): your SKUs with `cogs`, `weightKg`, `lithium`, `hazmat` + `hazmatClass` for loose
   packs, `exportControlled`, `oversized`, `digital`. Keep `category` values aligned with the compliance rules
   (`battery`) and the triage/disposition criteria.
3. **Extension schema**: `PUT /api/v1/extension-schema` with `extension-schema.json`; add the fields your channels send
   (serials and IMEIs are keyed by line id because extensions are validated at order level).
4. **Money and policy** (`tenant-config.yaml`): `sourcing.margin_floor`, `returns.restocking_fee_pct` and conditions,
   `returns.refund_requires_approval_above_usd`, guardrail caps sized to your AOV, `preorder.*` and
   `drop_ship.vendor_ship_sla_hours` (mirror the latter in `sla.yaml`).
5. **Launch allocation**: set `allocation.wave_units`, `protected_units` and `protected_tiers` from your launch plan when
   you call `preorder_allocation`; wire the call into your launch desk tooling (the script shows the input shape) and
   decide whether `reseller_burst` should escalate or cancel.
6. **Compliance** (`rules/compliance.yaml`): confirm with legal whether lithium-to-PO-box is a `hold` (CS fixes the
   address) or a `block`; add your export-control SKUs and `compliance.restricted_zips`.
7. **Returns**: adjust `returns.reason_taxonomy` and the `return_reason` options together; map `return_disposition`
   descriptions to your bench's vocabulary (the keys are fixed: restock, refurbish, liquidate, return_to_vendor, donate,
   destroy); decide which SKUs carry `vendorReturns` for RTV.
8. **Decisions**: read each `criteria` and `rules` block with the owning team; the evaluation gates are the sandbox
   values (0.6 / 0.5) so the heuristic fallback passes — raise them to 0.90 / 0.05 once Jev or an LLM is configured, and
   grow the golden sets from labelled records.
9. **Queues and playbooks**: map `escalateTo` and `skills` to your teams (`vendor_ops`, `launch_desk`, `returns_ops`);
   set the Slack / PagerDuty channels through the API.
10. **Agents**: register only the agents you work with; keep `confirmActions` on tier-1 assistants and the order-value
    caps below the price of a laptop unless the mandate says otherwise.

## Format notes and assumptions

* **Serial numbers** are order extensions keyed by line id (`extensions.serials["1"]`, `extensions.imeis["1"]`): the
  platform validates `order.extensions` against a tenant JSON Schema and has no line-level schema. `line.attributes`
  stays free-form for connectors that want the serial on the line as well.
* **Lithium to a PO box** is a `hold`, not a `block`, so the order exists and CS can fix the address; loose packs on
  air and oversized items on air are `block` (capture rejected). A blocked order never appears in the UI, which is why
  `sample-orders.yaml` carries the PO-box case (P01) and the README shows the air case as a curl.
* **Pre-order allocation** is evaluated per waiting order by the launch desk (`evaluate-now`, `mode: LIVE`), and the
  answer is applied as `event.inventory_available` (a protected unit made available) or `api.cancel_line`. The line
  state model has no `awaiting_release_date` state because this platform version has no release-date timer; pre-orders
  are captured when the launch opens and wait as backorders.
* **`backorder_next_action`** uses the options `wait / substitute / cancel / split` (the demo tenant's version uses
  `wait / resource / cancel_line / offer_substitute`); the `order_line.preorder` transitions and the golden set match
  this vocabulary.
* **Address kind** `freight_forwarder` / `po_box` in `sample-orders.yaml` is passed as `addressType` on the ship-to
  address and reaches the compliance rules as `order.destination.address_type`; "PO Box" in `line1` is always
  detected by the address verifier.
* **Trade-in credit** is a line discount plus `extensions.tradeIn`; there is no negative tender in the capture format.
* No BOPIS order type: the stores ship (`ship_from_store`) but pickup is not part of this reference set.
