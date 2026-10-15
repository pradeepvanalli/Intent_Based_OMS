# Lumen & Loom — apparel reference tenant

`config/tenants/apparel/` is the open-source reference implementation of Intent OMS for a direct-to-consumer fashion
brand. It has the same file set as the `demo` and `starter` tenants, but every setting, decision, golden case and
sample order tells one coherent story about selling clothes: sizes, returns, exchanges, drops and boutiques.

Tenant id `apparel` · name **Lumen & Loom** · vertical **Apparel / DTC fashion**.

```bash
# as a seeded sandbox tenant (sample orders, returns, exceptions and inventory.yaml included)
OMS_DEMO_SEED=true OMS_DEMO_TENANTS=demo,apparel make run
# or through the API against a running service
scripts/new-tenant.sh apparel "Lumen & Loom" --from config/tenants/apparel --create-login
# then the five scenarios
scripts/reference/apparel.sh
```

## 1. The business

**Who they are.** Lumen & Loom designs womenswear (dresses, denim, tees, outerwear, knitwear, shoes, accessories) and
sells it on its own site, through a marketplace, in five stores and to about forty boutiques on net terms. Every
style exists in several sizes and colours, so the catalogue is 47 SKUs for 20 garment and accessory styles plus a gift-wrap service (`DRS-MIDI-BLK-S` is the midi
dress, black, size S). Stock sits in one DC (Allentown PA), a west-coast 3PL (Las Vegas) and the stores; everything
that comes back goes to a returns centre in Columbus OH.

**The pain today.** Thirty percent of orders come back, most of it fit. Shoppers "bracket" (order the 28 and the 30
and return one), and today nobody knows until the return label is scanned. A size exchange is a refund plus a new
order, so the sale is lost half the time, and the new size ships from the DC even when the store two miles away has
it. A $21 pair of socks is shipped back at a cost of $10.45 to recover $9.45. Final-sale markdowns generate disputes that
CS answers from memory. A collection drop or an influencer post oversells a SKU in minutes and the oversold shoppers
hear about it days later, in a cancellation e-mail. Wholesale orders go through the same rules as consumer orders and
get split into three parcels.

**What changes with Intent OMS.** The policies become decisions the business owns and edits in the studio:

| Question | Decision | Tier | Who owns it |
|---|---|---|---|
| Is this shopper bracketing? | `bracketing_likelihood` (Score, 5 levels) | T1 | returns-and-fit |
| How does the new size reach her? | `exchange_fulfilment` (Choice) | T2 | returns-and-fit |
| Refund now, on scan, after inspection, or store credit? | `return_risk` (Score + Nouls) with apparel fast lanes | T2 | returns-and-fit |
| Keep it or send it back? | `returns.keep_it_enabled` + three cost keys in `tenant-config.yaml` | — | finance |
| Where does an inspected garment go? | `return_disposition_apparel` (restock / outlet / donate / recycle) | T1 | merchandising |
| What happens to an oversold drop line? | `drop_oversell_action` (Choice) | T1 | merchandising |
| What is the customer asking? | `customer_request_intent` with `exchange` and `policy_dispute` | T1 | customer-service |

plus the eleven decisions every tenant has (fulfilment intent, sourcing score, split acceptability, exception
classify, SLA breach risk, return reason, runtime return disposition, backorder next action, address risk,
appeasement, agent mandate match, injection screen), each with apparel criteria and owners.

## 2. The network

| Node id | What | Where | Capabilities | Notes |
|---|---|---|---|---|
| `DC-PA` | Distribution centre | Allentown PA | ship, gift_wrap | 9 000 units/day, 16:30 cutoff, main stock |
| `3PL-LV` | 3PL partner | Las Vegas NV | ship | West-coast parcels; pick rate 0.93, the node that short-picks (S01) |
| `ST-NYC-SOHO` | Store | New York NY | ship, bopis, sfs, same_day, gift_wrap | |
| `ST-LA-MELROSE` | Store | Los Angeles CA | ship, bopis, sfs, same_day, gift_wrap | The only node holding `DRS-MIDI-BLK-M` (A2) |
| `ST-CHI-GOLD` | Store | Chicago IL | ship, bopis, sfs, same_day | |
| `ST-MIA-DESIGN` | Store | Miami FL | ship, bopis, sfs | |
| `ST-AUS-SOCO` | Store | Austin TX | ship, bopis, sfs | Thin staffing (0.6), highest reject rate |
| `RC-OH` | Returns processing centre | Columbus OH | returns, inspect, refurbish | No `ship` capability and no sellable stock: never a sourcing candidate |

Rate card: `shipping-rates.yaml` (ground, two_day, next_day, same_day inside 40 km, freight for cartons); one extra
parcel costs $9.50 (`sourcing.split_penalty_usd`), the margin floor is 18 %.

## 3. Files

| File | What it configures |
|---|---|
| `tenant-config.yaml` | Metadata (`name`, `vertical`, `description`) and runtime keys: 18 % margin floor, two-parcel limit, returns economics (label $7.95, handling $2.50, recovery 45 %), serial-returner threshold 8, reason taxonomy, per-order-type windows (TBYB 14 days, wholesale 0), guardrails, tax nexus |
| `order-types.yaml` | `standard_ship`, `bopis`, `ship_from_store`, `try_before_you_buy` (own state models, 7-day trial), `exchange` (stores first), `wholesale` (b2b, net terms, no returns), `pre_order` (inbound stock, `drop_oversell_action`) |
| `state-models/` | Order header and line for standard ship, BOPIS and try-before-you-buy (no capture on ship, `delivered` is the trial state, `kept` captures), return, shipment |
| `decisions/` | 18 decisions: the 14 standard ones adapted plus `bracketing_likelihood`, `return_disposition_apparel`, `exchange_fulfilment`, `drop_oversell_action` |
| `golden-sets/` | 7 labelled sets (100 cases): fulfilment intent, exception classify, return risk, bracketing (24), disposition (14), exchange (14), drop oversell (12) |
| `rules/compliance.yaml` | Fur and exotic-skin state bans, international customs hold, down-fill declaration for Canada, no limited drops to freight forwarders, signature on high-value garments, oversized cartons not to PO boxes, restricted ZIPs |
| `catalog.yaml` | 47 SKUs: 20 styles × sizes × colours with cogs, weight, `finalSale` on three markdowns, `drop` on the bomber and the slip dress, `highValue`, `leather`, `containsDown`, a gift-wrap service SKU |
| `nodes.yaml`, `shipping-rates.yaml` | The network above and the rate card |
| `playbooks.yaml` | `short_pick_3pl`, `carrier_lost` (reship within $200), `trial_period_ended`, `drop_oversell`, `exchange_hold` plus the standard set |
| `sla.yaml` | Per order type; `try_before_you_buy` adds the 7-day trial clock on `delivered`, `pre_order` evaluates `drop_oversell_action` after a day in `backordered` |
| `exception-taxonomy.yaml` | Adds `carrier_lost`, `drop_oversell`, `trial_capture_due`; queues `store_ops`, `merch_planning`, `returns_ops` next to the standard ones |
| `agents/agents.yaml` | A marketplace shopping agent, the brand's stylist assistant (tier 1, confirms every order), the boutique ordering portal, the internal ops copilot |
| `inventory.yaml` | 259 positions: the scenario baseline (see the file header) |
| `sample-orders.yaml` | 43 orders, 4 returns, 2 exceptions (format: `docs/SAMPLE_ORDERS_FORMAT.md`) |

Sample orders by story: `A01`/`A02` bracketing with and without the note · `A03` gift wrap with a message · `A04`
final-sale markdown, then a return attempt · `A05`/`A06` try before you buy · `D01`–`D15` the influencer drop (15 orders
in twelve minutes, 10 units) · `W01` wholesale on NET30 · `O-E01`/`E01`, `O-E02`/`E02` size and colour exchanges ·
`Q01` quality complaint ("seam came apart after one wash") · `K01` $21 socks keep-it · `B01`/`B02` BOPIS · `F01`/`F02`
ship from store · `P01` pre-order · `S01` 3PL short pick · `S02` carrier lost · `X01` declined card · `M01`/`M02`
marketplace and POS · `G01` stylist-agent order · `N01`–`N04` ordinary volume.

## 4. Five scenarios

`scripts/reference/apparel.sh` runs all five against `http://localhost:8080` (tenant `apparel`, login `admin`/`admin`);
the curl calls below are what it does. Headers for every call:

```bash
BASE=http://localhost:8080
TOKEN=$(curl -s -X POST $BASE/api/v1/auth/login -H 'Content-Type: application/json' \
  -d '{"tenantId":"apparel","userId":"admin","password":"admin"}' | python3 -c 'import sys,json; print(json.load(sys.stdin)["token"])')
H=(-H "Authorization: Bearer $TOKEN" -H "X-Tenant-Id: apparel" -H "Content-Type: application/json")
```

### A1 · Bracketing detected at capture

Two orders of the same style in two sizes: one with the note *"Ordering the 28 and the 30, will return the one that
doesn't fit"*, one silent.

```bash
curl -s -X POST $BASE/api/v1/orders "${H[@]}" -d '{"channel":"web","channelOrderId":"A1-NOTE-1","orderType":"standard_ship",
 "customerRef":"cust-ana","customer":{"ref":"cust-ana","name":"Ana Oliveira","email":"ana@example.com","tier":"gold"},
 "lines":[{"lineId":"1","sku":"JNS-STR-IND-28","qty":1,"unitPrice":{"amount":118.00,"currency":"USD"}},
          {"lineId":"2","sku":"JNS-STR-IND-30","qty":1,"unitPrice":{"amount":118.00,"currency":"USD"}}],
 "addresses":[{"type":"ship_to","line1":"145 7th Ave","city":"Brooklyn","state":"NY","zip":"11215","country":"US"}],
 "tenders":[{"type":"card","token":"tok_visa_ok","amount":{"amount":271.40,"currency":"USD"}}],
 "notes":[{"source":"customer","text":"Ordering the 28 and the 30, will return the one that does not fit."}],
 "intentChoices":["ship_complete"],"shipMethod":"ground"}'
# evaluate the decision on the order's facts (the capture workflow stores the note as data; GR-08)
curl -s -X POST $BASE/api/v1/decisions/bracketing_likelihood/evaluate-now "${H[@]}" -d '{"subjectType":"order","subjectId":"<orderId>","mode":"live",
 "input":{"order":{"order_type":"standard_ship","channel":"web","notes":"Ordering the 28 and the 30, will return the one that does not fit.","line_count":2,
   "lines":[{"style":"JNS-STR","size":"28","colour":"indigo","qty":1},{"style":"JNS-STR","size":"30","colour":"indigo","qty":1}],
   "same_style_adjacent_size_pairs":1,"same_style_same_size_colour_pairs":0},
  "customer":{"tier":"gold","history":{"orders_12m":7,"returns_12m":2,"bracketing_returns_12m":1}}}}'
```

**Look at:** `/orders/<id>` → *Decisions* → *Why*. The noted order resolves at layer `L0_RULE` (`explicit_return_note`,
reason code `explicit_return_intent_in_note`, value 4). The silent order is scored by the model from the line pattern
(level 2, "possible", reason code `adjacent_sizes_same_style`); a gift order with two different styles short-circuits
on `no_repeated_style`. Talk track: the signal exists at capture, so the DC can pack a return label and the returns
centre pre-allocates the `bracketing` reason, which is a trusted fast lane in `return_risk`.

### A2 · Size exchange from the nearest store

The S was delivered to Santa Monica; the M exists only at LA Melrose, 15 km away.

```bash
curl -s -X POST $BASE/api/v1/returns "${H[@]}" -d '{"orderId":"<originalOrderId>","channel":"web","receiptPresent":true,
 "lines":[{"lineId":"1","qty":1,"reasonCode":"wrong_size","customerReasonText":"The S is snug across the shoulders, I need the M"}]}'
curl -s -X POST $BASE/api/v1/returns/<rma>/decide "${H[@]}"
curl -s -X POST $BASE/api/v1/decisions/exchange_fulfilment/evaluate-now "${H[@]}" -d '{"subjectType":"return","subjectId":"<rma>","mode":"live",
 "input":{"exchange":{"original_sku":"DRS-MIDI-BLK-S","new_sku":"DRS-MIDI-BLK-M","reason":"wrong_size","value_usd":148.0,"days_since_delivery":1,
   "nearest_store_km":15,"nearest_store_atp":3,"dc_atp":0,"network_atp":3,"same_price":true,"customer_prefers_refund":false},
  "order":{"channel":"web","destination_state":"CA"},"customer":{"tier":"gold","history":{"returns_12m":2,"not_received_claims":0,"flags":[]}}}}'
curl -s -X POST $BASE/api/v1/orders "${H[@]}" -d '{"channel":"web","channelOrderId":"A2-EXCH-1","orderType":"exchange","shipMethod":"same_day",
 "customerRef":"cust-camila","customer":{"ref":"cust-camila","name":"Camila Reyes","email":"camila@example.com","tier":"gold"},
 "lines":[{"lineId":"1","sku":"DRS-MIDI-BLK-M","qty":1,"unitPrice":{"amount":148.00,"currency":"USD"}}],
 "addresses":[{"type":"ship_to","line1":"1299 Ocean Ave","city":"Santa Monica","state":"CA","zip":"90401","country":"US"}],
 "tenders":[{"type":"card","token":"tok_visa_ok","amount":{"amount":170.20,"currency":"USD"}}],"intentChoices":["ship_complete"],
 "extensions":{"exchangeForOrderId":"<originalOrderId>","exchangeForRma":"<rma>","exchangeOriginalSku":"DRS-MIDI-BLK-S","exchangeType":"size_swap","settlement":"void_on_return_receipt"}}'
curl -s $BASE/api/v1/orders/<exchangeOrderId>/view "${H[@]}"
```

**Look at:** `/returns` → the RMA shows the `size_return_fast_lane` rule and an instant refund timing; the exchange
decision answers `ship_from_store_same_day` (ordinary history, store inside the same-day radius); `/orders/<exchange>`
→ *Plan* sources from `ST-LA-MELROSE`, promise today. With a flagged customer or a $298 trench the same decision
answers `hold_until_return_received` and the `exchange_hold` playbook holds the release. Talk track: an exchange keeps
the sale; the T2 band means goods only leave before the return is back when the model is at least 90 % sure.

### A3 · Keep-it vs return margin decision

```bash
curl -s -X POST $BASE/api/v1/returns "${H[@]}" -d '{"orderId":"<socksOrderId>","channel":"web","receiptPresent":true,
 "lines":[{"lineId":"1","qty":1,"reasonCode":"changed_mind","customerReasonText":"Ordered the wrong colour of socks"}]}'
curl -s -X POST $BASE/api/v1/returns/<rma>/decide "${H[@]}"
curl -s $BASE/api/v1/returns/<rma>/customer-message "${H[@]}"
```

**Look at:** the `decide` response: `keepIt.offered: true`, return cost $10.45 (`returns.return_shipping_cost_usd` +
`returns.return_handling_cost_usd`) against a recovery of $9.45 (45 % of $21), refund issued, no label. The same call
on a $128 dress returned for size shows the `size_return_fast_lane` rule, an instant refund and a label to Columbus.
`/returns` shows both with reason-code chips; the customer message (GR-15) fills typed slots only. Talk track: the
keep-it threshold is three tenant keys the finance team edits at runtime, and the fast lane is a rule the returns team
owns in the studio.

### A4 · Drop oversell handled by policy

Feed ten units of `JKT-BMB-ECR-M` to `DC-PA`, place fifteen orders within a minute, let the workflow allocate.

```bash
curl -s -X POST $BASE/api/v1/inventory/feed "${H[@]}" -d '{"source":"drop-04","positions":[{"sku":"JKT-BMB-ECR-M","nodeId":"DC-PA","supplyType":"on_hand","qty":10}]}'
for i in $(seq 1 15); do curl -s -X POST $BASE/api/v1/orders "${H[@]}" -d "{\"channel\":\"web\",\"channelOrderId\":\"A4-DROP-$i\",\"orderType\":\"standard_ship\",
 \"customerRef\":\"cust-drop-$i\",\"customer\":{\"ref\":\"cust-drop-$i\",\"name\":\"Drop $i\",\"email\":\"drop$i@example.com\",\"tier\":\"standard\"},
 \"lines\":[{\"lineId\":\"1\",\"sku\":\"JKT-BMB-ECR-M\",\"qty\":1,\"unitPrice\":{\"amount\":325.00,\"currency\":\"USD\"}}],
 \"addresses\":[{\"type\":\"ship_to\",\"line1\":\"1600 Larimer St\",\"city\":\"Denver\",\"state\":\"CO\",\"zip\":\"80202\",\"country\":\"US\"}],
 \"tenders\":[{\"type\":\"card\",\"token\":\"tok_visa_ok\",\"amount\":{\"amount\":373.75,\"currency\":\"USD\"}}],\"intentChoices\":[\"ship_complete\"],\"shipMethod\":\"ground\"}"; done
curl -s "$BASE/api/v1/orders?q=A4-DROP&limit=50" "${H[@]}"
curl -s -X POST $BASE/api/v1/decisions/drop_oversell_action/evaluate-now "${H[@]}" -d '{"subjectType":"order","subjectId":"<backorderedOrderId>","mode":"live",
 "input":{"drop":{"sku":"JKT-BMB-ECR-M","oversold_units":5,"restock_planned":true,"restock_eta_days":12,"substitute_available":false,"substitute_sku":"","demand_spike_source":"influencer"},
  "order":{"channel":"web","notes":"","placed_minutes_after_drop":9,"value_usd":325.0},"customer":{"tier":"standard","history":{"orders_12m":2}}}}'
```

**Look at:** `/orders?q=A4-DROP`: ten orders `released` at `DC-PA`, five in `sourcing` with the line `backordered`;
`/control-tower` lists the `inventory_short` exceptions with the `short_pick_3pl`/`resource` playbook runs. The
decision: a restock inside two weeks resolves by rule (`restock_within_two_weeks` → `backorder_with_eta`); a shopper
who wrote "if it's sold out please just cancel" with a 45-day restock is the model's call (`cancel_and_apologise`);
no restock and no other colour resolves by rule to `waitlist`. For `pre_order` lines the `backordered` SLA in
`sla.yaml` evaluates the same decision after a day without anyone asking. Talk track: a sell-out is a policy, and
the policy is editable before the next drop, not after the complaints.

### A5 · Final-sale dispute answered with a grounded CS draft

```bash
curl -s -X POST $BASE/api/v1/returns "${H[@]}" -d '{"orderId":"<finalSaleOrderId>","channel":"web","receiptPresent":true,
 "lines":[{"lineId":"1","qty":1,"reasonCode":"changed_mind","customerReasonText":"I did not see it was final sale, the colour does not suit me"}]}'
curl -s -X POST $BASE/api/v1/returns/<rma>/decide "${H[@]}"
curl -s $BASE/api/v1/returns/<rma>/customer-message "${H[@]}"
curl -s -X POST $BASE/api/v1/copilot/cs/draft "${H[@]}" -d '{"orderId":"<finalSaleOrderId>","message":"I did not see it was final sale, the colour does not suit me and I want to return the floral dress."}'
```

**Look at:** `decide` returns `eligible: false`, reasons `[final_sale]`, a `return_denial` task in `risk_review`
(T3: the rule recommends, a person applies the denial); *Why* on `return_risk` shows layer `L0_RULE`, rule
`final_sale`, no model called. The CS draft classifies the message as `policy_dispute` and writes the reply from the
order's facts (the line was sold as final sale at $59, the beanie on the same order is returnable until day 30);
`factsUsed` lists exactly what it was allowed to say. Talk track: the policy is a rule, the apology is grounded, and
nothing in the reply came from the model's memory.

## 5. Make it yours

1. **Network** (`nodes.yaml`): your node ids, coordinates, cutoffs, capacities; keep ids stable, webhooks address nodes
   by id (`/webhooks/wms/{nodeId}`). Decide which stores get `same_day` and `gift_wrap`.
2. **Catalogue** (`catalog.yaml`): one SKU per variant with `style`, `size`, `colour`, `season`, `cogs`, `weightKg`;
   `finalSale: true` on markdowns, `drop: <id>` on limited releases, `highValue` where you want a signature. The
   bracketing features (`same_style_adjacent_size_pairs`) are computed from `style` and `size`.
3. **Returns economics** (`tenant-config.yaml`): `returns.return_shipping_cost_usd`, `return_handling_cost_usd`,
   `recovery_rate`, `serial_returner_threshold`, `refundable_conditions`, per-type `returns.window_days.<type>`.
4. **Fast lanes**: read `return_risk` rules with the returns team; `size_return_fast_lane` ($300, five returns) and
   `trusted_customer` are the two numbers that move the most money.
5. **Exchange policy**: `exchange_fulfilment` is T2 on purpose; lower the value threshold in its rubric only with a
   golden set that proves the model's calibration. Add a tenant key for the same-day radius if 40 km is not yours.
6. **Drops**: before each drop set the restock plan the decision reads (`restock_planned`, `restock_eta_days`,
   `substitute_sku`) and review `drop_oversell_action`'s rules; the `pre_order` SLA is one day.
7. **Try before you buy**: the line model captures on `api.confirm_keep` or `timer.trial_expired`; schedule the timer
   from your orchestration (there is no built-in trial timer yet) or run the `trial_period_ended` playbook's finance
   task as the manual step. Check the PSP authorisation lifetime (mock: 7 days) against `trialDays`.
8. **Wholesale**: `tender.type: net_terms` skips the payment check; the 0-day return window sends every claim to the
   account manager. Add `freight` to your rate card if cartons ship LTL.
9. **Extension schema**: the sample orders use `giftWrap`, `giftMessage`, `hidePrices`, `trialDays`,
   `exchangeForChannelOrderId`, `exchangeOriginalSku`, `exchangeType`, `settlement`, `poNumber`, `paymentTerms`. Lock
   them down with `PUT /api/v1/extension-schema` (JSON Schema, `additionalProperties: false`) once your channels agree.
10. **Queues and people** (`exception-taxonomy.yaml`): map `store_ops`, `merch_planning`, `returns_ops` to real teams;
    every queue a playbook, SLA or the returns module names must exist.
11. **Publishing decisions**: the evaluation gates are production values (0.90 / 0.05). The seeder and
    `scripts/new-tenant.sh --publish emergency` publish without them (48 h retro review); run the studio lifecycle
    (lint → impact → evaluate → approve → canary → live) once Jev or an LLM is configured.
12. **Secrets** through the API, never in this folder: `webhooks.inbound_secret`, `shopify.webhook_secret`, alert
    webhooks, the agents' generated keys.

## 6. Assumptions and known gaps

* **Decision invocation.** The order types list `bracketing_likelihood`, `exchange_fulfilment` and
  `drop_oversell_action` in their `decisions:` sets, which is what the studio and the order type contract read. The
  capture and return workflows invoke decisions by name in code (`fulfilment_intent`, `return_risk`,
  `return_disposition`, `backorder_next_action` …), so the three new decisions are evaluated through
  `POST /api/v1/decisions/{name}/evaluate-now` (what the script does), by the `pre_order` SLA (`drop_oversell_action`)
  and by the `drop_oversell` playbook; wiring them into the workflows is a code change.
* **Disposition vocabulary.** The returns module stores `Disposition.ALL` (restock, refurbish, liquidate,
  return_to_vendor, donate, destroy). `return_disposition` keeps that vocabulary with apparel criteria;
  `return_disposition_apparel` is the merchandising-facing twin (outlet = liquidate, recycle = destroy).
* **Score levels.** The runtime indexes rubric levels from 0, so the five-level bracketing rubric evaluates to 0–4.
* **Trial timer.** `timer.trial_expired` has no scheduler yet (see checklist item 7); `delivered` carries the SLA that
  opens the finance task.
* **Exchange settlement.** An exchange order carries a card authorisation for the new size and the extension
  `settlement: void_on_return_receipt`; voiding it when the original is received is a playbook or finance step, not
  automatic.
* **Inventory baseline.** `inventory.yaml` is the scenario baseline; the script re-feeds `DRS-MIDI-BLK-M` and
  `JKT-BMB-ECR-M` before A2 and A4 so it can be re-run.
