# Roadmap

The next two quarters, drawn from the deferred items in `docs/GAP_ANALYSIS.md` and from what prospective adopters
asked for first. Dates are intentions, not promises. Items tagged **help wanted** are scoped so that a contributor can
take them without touching the platform core; the matching GitHub label is `help wanted`, and the issue for each item
links back here.

Open a Discussion if you want something moved up. The fastest way to move an item is to bring an order sample it would
have changed.

## Q4 2026 (October to December)

| Item | Why | Scope | Labels |
|---|---|---|---|
| Hosted demo | People want to click before they clone | A public instance of the demo tenant with hourly reset, read-mostly roles, AI budget capped; the Showcase tour pinned | `help wanted`, `infra` |
| Store associate task app | The task queues and MCP tools exist; associates need a phone UI | Mobile-first React page for pick, pack, BOPIS handover and store-reject; uses existing `/api/v1/tasks` and transitions | `help wanted`, `ui`, `good first issue` for individual screens |
| EDI X12 850/855/856/810 | Wholesale and marketplace partners still send EDI | New `oms-edi` module: trading-partner profiles, X12 parser, 850 to `CaptureRequest`, 855/856/810 from ledger events | `help wanted`, `module` |
| Allocation protection | Channel and tier reservations are a common first ask | `allocation_rule` table consumed by `InventoryService.atp`; studio-editable; what-if support | `core` |
| A/B experiments on decisions | Canary by subject hash exists; experiments need guardrail metrics and auto-stop | Experiment table, assignment, guardrail KPIs, automatic stop, report in analytics | `core` |
| Inventory confidence decision | Hook exists, decision does not | Nightly `inventory_confidence` decision over cycle-count age and rejects; feeds ATP buffers | `help wanted`, `decision` |
| Reference implementations, round two | Three verticals ship at launch; more make the pattern credible | Grocery (perishables, substitutions), furniture (white-glove, two-person delivery), B2B (quotes, consolidation) | `help wanted`, `reference-implementation` |

## Q1 2027 (January to March)

| Item | Why | Scope | Labels |
|---|---|---|---|
| Parallel regions in statecharts | Payment and fulfilment progress independently on one order | Runtime support for `regions`, static checks, migration path; modelled on the existing `payment` aggregate | `core` |
| Visual state-model designer | Business users want to drag, not edit YAML | Drag-to-edit on the existing SVG, writing back to YAML; validation inline | `help wanted`, `ui` |
| SCIM provisioning | OIDC covers authentication; enterprises need provisioning | `/scim/v2` Users and Groups mapped to `app_user`, deprovisioning, group to role mapping | `help wanted`, `security` |
| onX and ACP adapters | More agent protocols are reaching merchants | Adapters on the existing `AgentOrderService` with version negotiation, same mandates and evidence packs | `help wanted`, `agents` |
| Drafts, quotes and consolidation | Needed for B2B and furniture | `quoted` header state with price-lock timer; sweeper that consolidates `awaiting_sourcing` orders sharing customer and address | `core` |
| Anomaly detection with incident summaries | Baselines now have history | z-scores over `kpi_daily`; summaries through the explain copilot; alert routing | `help wanted`, `analytics` |
| Identity resolution for returns | Cross-channel abuse hides behind new e-mails | Phone and address tokens, probabilistic match, reason codes | `core` |
| Temporal adapter | Teams with Temporal want to keep it | `WorkflowEngine` implementation backed by Temporal; definitions already map one to one | `help wanted`, `module` |

## Always open

* More decision providers (`docs/examples/extensions/CustomDecisionProvider.java` is the template).
* More connectors: WMS vendors, carriers, tax engines, PSPs. Each one needs a deterministic mock and a contract test.
* Translations of the customer-facing strings (`es-US` first).
* Golden-set contributions for the reference tenants: labelled cases are the most valuable thing a retailer can give.

## Not planned

* Pricing and promotions: the OMS consumes prices.
* Multi-currency arithmetic: `Money` refuses mixed currencies by design; FX belongs upstream.
* Letting a model publish configuration or move money. This is a guardrail, not a gap.
