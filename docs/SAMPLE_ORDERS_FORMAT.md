# `sample-orders.yaml` — data-driven sample orders for a tenant

Every tenant folder under `config/tenants/<tenant>/` may carry a `sample-orders.yaml`. When the seeder runs for that
tenant (`OMS_DEMO_SEED=true`, `OMS_DEMO_TENANTS=demo,retail,electronics,apparel`) it captures these orders through the
normal capture pipeline, drives the requested lifecycle through the mock WMS / carrier webhooks, then creates the
listed returns and exceptions. The `demo` tenant keeps its Java sample set as a fallback when the file is absent.

Rules: keys are stable (re-running the seeder never duplicates); `channelOrderId` must be unique per tenant; SKUs must
exist in the tenant `catalog.yaml`; node ids must exist in `nodes.yaml`; every order type must exist in
`order-types.yaml`. Amounts are USD decimals. Lifecycle steps run only when the order reached the state that allows
them (a held order is not shipped).

```yaml
orders:
  - key: D01                               # required, unique within the file; referenced by returns / exceptions
    channel: web                           # web | marketplace | pos | agent (any channel the tenant accepts)
    channelOrderId: RET-WEB-1001           # required, unique
    orderType: standard_ship               # default standard_ship; bopis orders also set pickupNodeId
    shipMethod: ground                     # ground | two_day | next_day | same_day | pickup
    placedAt: 2026-09-28T14:05:00Z         # optional; default now
    customer:
      ref: cust-1001                       # required
      name: Dana Whitfield
      email: dana@example.com              # optional (POS walk-ins omit it)
      phone: "+1 312 555 0101"             # optional
      tier: gold                           # standard | silver | gold | any tenant tier
      loyaltyVerified: true                # default true
      age: 34                              # optional; omit to leave age unverified (compliance holds)
      attributes:                          # free-form; the demo uses these three
        split_acceptance_rate: 0.2
        returns_12m: 1
        orders_12m: 4
    lines:
      - { id: "1", sku: HOOD-GRY-M, qty: 2, unitPrice: 68.00 }
      - { id: "2", sku: SNK-RUN-10, qty: 1, unitPrice: 145.00, discount: 10.00, attributes: { urgent: true } }   # attributes optional: per-line overrides (urgent, markdown_risk, …)
    shipTo:                                # required unless pickupNodeId is set
      name: Dana Whitfield                 # optional
      line1: 233 S Wacker Dr
      line2: Suite 800                     # optional
      city: Chicago
      state: IL
      zip: "60606"
      country: US                          # default US
      kind: residential                    # residential | commercial | po_box | freight_forwarder
    pickupNodeId: ST-CHI-01                # BOPIS only: adds the pickup address, sets extensions.pickupNodeId and
                                           # intentChoices [pickup_consolidated] unless overridden
    notes:                                 # free-text customer notes → the fulfilment_intent decision
      - This is a birthday gift, please send everything together in one box.
    intentChoices: [ship_as_available]     # explicit choices the channel captured
    tender:                                # default: card / tok_visa_ok / 110 % of merchandise total
      type: card
      token: tok_visa_ok                   # tok_visa_decline → payment declined
      amount: 309.10
    extensions: {}                         # tenant extension fields (validated against the extension schema)
    provenance:                            # agent orders only
      agentId: agent-shopper-1
      mandateId: mandate-77
      instructionText: Buy the grey hoodie in M, ship to the Chicago office, keep it under $200.
                                           # required: capture blocks an agent-channel order without it (the buyer's
                                           # instruction is kept as evidence, never merged into a prompt)
    lifecycle:                             # all optional, default false
      ship: true                           # WMS "shipped" for every active fulfilment group
      partialShip: true                    # ship only the first group
      deliver: true                        # carrier "delivered" after ship
      deliveredDaysAgo: 45                 # back-date the delivery (return-window scenarios)
      bopisPickup: true                    # staged_for_pickup → picked_up
      storeReject: true                    # first store group rejects before shipping (re-source scenario)

returns:
  - order: D03                             # key from orders[]
    lineId: "1"
    qty: 1
    reason: changed_mind                   # a reason code of the tenant's return policy
    text: Changed my mind
    receiptPresent: true                   # default true
    backdateDeliveryDays: 0                # default 0

exceptions:
  - order: R01
    source: cs                             # cs | carrier | wms | payment | system
    type: address                          # a type from exception-taxonomy.yaml (severity, queue and playbook come from there)
    code: address_unverifiable             # optional raw signal code recorded on the exception; defaults to the type
    text: Customer reports the apartment number is missing from the address
    playbook: null                         # optional playbook override
```

The loader lives in `oms-orchestration` (`seed/SampleOrdersFile.java`). Validation errors name the order key and field
(`sample-orders.yaml: order D01: lines[1].sku is required; return[0] (order D09): order 'D09' is not an order key`), and
the seeder cross-checks skus, pickup nodes, order types and exception types against the tenant folder before capturing
anything, so a typo fails the tenant's seed loudly instead of producing half a scenario. `pickupNodeId` without `shipTo`
takes the store's city / state / ZIP from `nodes.yaml`. `age` present ⇒ the customer counts as age-verified;
`loyaltyVerified` is stored in `customer.history.loyalty_verified`. `lifecycle.deliveredDaysAgo` implies `deliver`.
