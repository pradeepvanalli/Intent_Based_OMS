# Reference implementations

Three complete tenants, each a different retail vertical, each with five scripted scenarios. They exist so that a team
evaluating Intent OMS can start from something that looks like their business rather than from the generic demo. Each
tenant lives under `config/tenants/<tenant>/` with its own `README.md` describing every file and the policies that
make it different.

| Tenant | Business | Script | Configuration |
|---|---|---|---|
| `retail` | Maple & Main General Store | `scripts/reference/retail.sh` | `config/tenants/retail/README.md` |
| `electronics` | Voltline Electronics | `scripts/reference/electronics.sh` | `config/tenants/electronics/README.md` |
| `apparel` | Lumen & Loom | `scripts/reference/apparel.sh` | `config/tenants/apparel/README.md` |

The seeded `demo` tenant remains the one `scripts/bootstrap.sh` loads first; its seven scenarios are in
`docs/DEMO_USE_CASES.md`.

## Retail: Maple & Main General Store

General merchandise sold online and through a handful of stores. Buy online, pick up in store and curbside are a large
share of orders. The catalogue includes hazmat (aerosols, lithium) and oversized items (grills, furniture) that cannot
go through every carrier or every node. Marketplace orders carry lower margin, so the profitability floor matters more
than anywhere else. Peak season doubles volume and the stores run out of pick capacity before the DCs do.

| # | Scenario | What it shows |
|---|---|---|
| R1 | Store-reject reroute | A store rejects a pick (damaged stock); the order re-sources to the next best node within its promise, the customer is told once, and the store's reject rate feeds the node score |
| R2 | Hazmat and oversized routing | Compliance rules keep aerosols off air and lithium off PO boxes; oversized items route to the one DC with LTL; the plan shows why cheaper options were excluded |
| R3 | Margin-floor marketplace order | A marketplace order with a thin margin falls below the floor after shipping; finance sees the economics and the alternatives, approves with a reason, and the KPI records it |
| R4 | Holiday surge capacity | Store pick capacity is exhausted; sourcing shifts to DCs automatically, the promise widens honestly, and the control tower shows the capacity exception before SLAs break |
| R5 | Curbside promise | A curbside order promises a ready-by time from store capacity, holds the stock, notifies on ready, and handles the no-show with a typed hold and a playbook |

## Electronics: Voltline Electronics

Consumer electronics with product launches, high ticket values and a drop-ship long tail. Launch day brings thousands
of pre-orders against a known first allocation. High-value orders to new addresses are the main fraud vector.
Backorders are normal and customers write in asking what to do. Warranty claims need triage before an RMA. Drop-ship
vendors have SLAs that they miss.

| # | Scenario | What it shows |
|---|---|---|
| E1 | Launch-day pre-order allocation | Pre-orders captured against a `pre_order` order type; the first allocation is distributed by policy (order time, tier, channel) with a typed tie-break; the rest get an honest re-promise |
| E2 | High-value address risk hold | `address_risk` scores a $2,400 order to a fresh address; T2 band puts it on a verification hold with a risk task and a customer-safe message |
| E3 | Backorder next action from a customer note | "Just send what you have and cancel the rest" becomes a `backorder_next_action` decision: partial ship, cancel the remainder, refund, all from the note |
| E4 | Warranty triage and disposition | A warranty claim is classified, routed to repair, replace or refund, and the returned unit gets a disposition with reason codes |
| E5 | Drop-ship vendor SLA breach re-promise | A vendor misses its ship SLA; the breach is detected by the timer, the order is re-promised or re-sourced by playbook, and the vendor's node score drops |

## Apparel: Lumen & Loom

Fashion sold online with a few stores. Customers bracket: they order three sizes and return two. Exchanges are more
common than refunds. Returns on low-value items cost more than the item. Drops oversell because demand spikes faster
than inventory feeds. Final-sale items generate disputes that CS has to answer with the policy in hand.

| # | Scenario | What it shows |
|---|---|---|
| A1 | Bracketing detected at capture | Three sizes of one style in one basket trigger a typed classification; the order is captured, the return expectation is recorded, and the sourcing plan avoids a split that would make the return worse |
| A2 | Size exchange from the nearest store | An exchange request is served from the nearest store with the size in stock; the outbound ships before the inbound arrives, under a limit and a typed risk score |
| A3 | Keep-it vs return margin decision | A $14 top return costs more to recover than it is worth; the decision returns keep-it with the economics on the trace |
| A4 | Drop oversell handled by policy | A drop oversells by 40 units; policy decides who gets stock (order time, tier), who gets a backorder and who gets a cancel with an appeasement, all with customer-safe messages |
| A5 | Final-sale dispute answered with a grounded CS draft | A customer disputes a final-sale refusal; the CS draft cites the policy and the order facts only, and the agent sends it with one click |

## Running a reference implementation

```bash
scripts/bootstrap.sh                      # or make try; the service must be running on :8080
scripts/reference/retail.sh               # loads config/tenants/retail (idempotent), then runs R1 to R5
scripts/reference/electronics.sh --only E2  # one scenario
scripts/reference/apparel.sh --load-only  # load the tenant without running scenarios
```

Each script creates the tenant and its logins if they do not exist, loads the configuration through the API (the same
calls `scripts/new-tenant.sh` makes), seeds the nodes, catalogue and inventory the scenarios need, runs the scenarios,
and prints what to open in the console. Logins follow the demo convention: tenant `retail`, user `admin`, password
`admin`, plus `ops`, `cs`, `risk`, `finance` and `strategy`.

Without an AI key every scenario still runs; the typed decisions fall back to rules and defaults and you will see more
tasks in the queues. With `OPENROUTER_API_KEY` the free-text parts (customer notes, disputes) are decided by the model.

## Making your own

Two paths.

**Copy a tenant.** Pick the closest vertical, copy its folder to `config/tenants/<yours>/`, change `nodes.yaml`,
`catalog.yaml`, `shipping-rates.yaml` and `tenant-config.yaml` first, then load it:

```bash
cp -r config/tenants/apparel config/tenants/acme
scripts/new-tenant.sh acme "Acme Outfitters"
```

`config/tenants/starter/README.md` explains every file in load order and what to change first.

**Describe your business.** Open `/copilot/bootstrap` (or `POST /api/v1/copilot/bootstrap`) and write a paragraph:
channels, nodes, pickup options, return policy, what must never happen. The copilot proposes order types, state models,
decisions with tier-appropriate bands, compliance rules, playbooks, SLAs and settings as a change set. Review it, stage
it, and publish through the studio like any other change. Prompts that work well are in
`docs/examples/prompts/bootstrap_tenant.md`.

Either way, the first thing to add after loading is a golden set for the decisions that matter to you. Labelled cases
are what make the gates meaningful.

## Contributing a vertical

The pattern is documented in `CONTRIBUTING.md` under "Reference implementation contribution guide": a tenant folder, a
`README.md`, five scenarios as a script under `scripts/reference/`, and golden sets for every decision the scenarios
touch. Grocery, furniture and B2B are the ones most often asked for.
