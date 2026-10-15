# Starter tenant

A clean, neutral configuration set a retailer copies and adapts. It has the same structure as the `demo` tenant (which
`scripts/bootstrap.sh` seeds with sample orders) but with placeholder nodes and SKUs, production evaluation gates on
the decisions, and comments on every setting. Load it with:

```bash
scripts/new-tenant.sh acme "Acme Outdoors"         # copies this folder to config/tenants/acme and loads it via the API
```

or let the seeder load a copy exactly as it loads the demo: `OMS_DEMO_TENANT=acme OMS_DEMO_SEED=true` at start-up
(sandboxes only: it also creates sample orders and deterministic inventory).

## Files

| File | What it configures | Loaded by |
|---|---|---|
| `tenant-config.yaml` | Runtime settings: margin floor, split policy, buffers, fees, tax rates, guardrails, AI budget, return policy, timeouts, alert channels. Every key is commented; secrets are commented out and set through the API | `PUT /api/v1/admin/config/{key}` (only keys not already set) |
| `order-types.yaml` | Seven order types (`standard_ship`, `bopis`, `ship_from_store`, `same_day_local`, `drop_ship`, `pre_order`, `backorder`): state models, workflow, decision set, channel default policy, return window, sourcing constraints | `PUT /api/v1/order-types/{name}` |
| `state-models/` | Six statecharts: order header and line for standard ship and BOPIS, return, shipment. Triggers and actions match the shipped workflows and webhooks | `POST /api/v1/state-models/publish` |
| `decisions/` | Fourteen decisions (fulfilment intent, sourcing node score, split acceptability, exception classify, SLA breach risk, customer request intent, return risk/reason/disposition, agent mandate match, injection screen, address risk, appeasement, backorder next action) with tier-appropriate bands and 0.90/0.05 evaluation gates | `POST /api/v1/decisions/{name}/drafts` then publish |
| `golden-sets/` | Labelled cases for `fulfilment_intent`, `exception_classify`, `return_risk` | `PUT /api/v1/golden-sets/{name}` |
| `rules/compliance.yaml` | L0 compliance rules: hazmat by air, alcohol and tobacco state bans, adult signature, age verification, lithium to PO boxes, export control, restricted ZIPs | `PUT /api/v1/compliance/rules` |
| `catalog.yaml` | Ten generic SKUs exercising every attribute (hazmat, age-restricted, lithium, oversized, final sale, digital) | `PUT /api/v1/catalog/{sku}` |
| `nodes.yaml` | Two DCs and two stores as placeholders, with health and cost profiles | `PUT /api/v1/nodes/{id}` |
| `shipping-rates.yaml` | Rate card by service, distance zone and weight band; split penalty; on-time probability by transit days | `PUT /api/v1/shipping-rates` |
| `playbooks.yaml` | Remediation playbooks (re-source, re-promise, address fix, payment retry, stuck diagnosis, reviews) gated by confidence and spend | `PUT /api/v1/playbooks/{name}` (`spendLimits`: seeder only) |
| `sla.yaml` | SLA per state and order type, `expectedNextEventWithin` for the stuck-order sweeper | `PUT /api/v1/sla-definitions` |
| `exception-taxonomy.yaml` | Exception types → severity, queue, playbook, SLA; task queues with escalation and skills | queues: `PUT /api/v1/queues/{name}`; types: seeder only |
| `agents/agents.yaml` | Agent registry placeholders (one external UCP shopper, one internal MCP ops agent) | `POST /api/v1/agents` (secrets shown once) |
| `inventory.yaml` | Initial on-hand positions for the placeholder network (starter only; the seeder generates its own) | `POST /api/v1/inventory/feed` |

Formats and every key: `docs/CONFIGURATION.md`, `docs/DECISIONS_GUIDE.md`, `docs/STATE_MODELS_GUIDE.md`.

## What to change first

1. **Network** (`nodes.yaml`): real node ids, coordinates, cutoffs, capacities, capabilities. Sourcing distance,
   transit and same-day eligibility come from these. Keep ids stable; webhooks address nodes by id
   (`/webhooks/wms/{nodeId}`).
2. **Catalogue** (`catalog.yaml`): your SKUs with `cogs` (the P&L and margin floor depend on it), `weightKg` (rate
   bands) and the compliance flags. Or set `capture.allow_unknown_sku: true` and feed the catalogue later.
3. **Rate card** (`shipping-rates.yaml`) and `sourcing.split_penalty_usd`: your negotiated costs drive every plan
   score and the profitability floor.
4. **Money and policy** in `tenant-config.yaml`: `sourcing.margin_floor`, `tax.rates`, `returns.window_days`,
   `returns.refund_requires_approval_above_usd`, `guardrail.*` caps, `ai.budget_usd_per_day`,
   `appeasement.role_limits`.
5. **Order types**: delete what you do not sell; set `channelDefaults` to how each channel ships today.
6. **Compliance rules**: review the state lists against your legal guidance; add `compliance.restricted_states` and
   `compliance.restricted_zips` as tenant keys so they can change without a rule edit.
7. **Decisions**: read each `criteria` and `rules` block with the team that owns the policy; adjust `owner`; add
   golden sets for the decisions that have none (eleven of the fourteen). Bands are tier defaults; keep T2 for
   anything that moves money.
8. **Queues and escalation** (`exception-taxonomy.yaml` `queues`, `playbooks.yaml`): map `escalateTo` and `skills` to
   your teams; every queue referenced by a playbook, SLA or decision escalation must exist.
9. **Secrets** through the API, not this folder: `webhooks.inbound_secret`, `shopify.webhook_secret`, alert webhooks.
10. **Agents**: register only the agents you work with; keep `confirmActions` on low-trust tiers.

## Publishing decisions from this template

The decisions declare production evaluation gates (`minAccuracyInActBand: 0.90`, `maxCalibrationError: 0.05`). The
normal publish path requires those gates to pass and the tier's approvals (one for T0/T1, two distinct people for
T2/T3, never the submitter). The deterministic `heuristic` fallback is designed not to reach that accuracy, so without
Jev or an LLM configured the gate fails. Options:

* `scripts/new-tenant.sh ... --publish emergency` (default): versions go live through the emergency path
  (`config.admin`), are flagged `decision.definition.emergency_published` and due a 48 h retro review. Right for a
  bootstrap: it leaves an honest audit trail.
* `--publish draft`: load everything as drafts and run the studio lifecycle per decision (lint → impact → evaluate →
  submit → approve → canary → live) once a provider is configured and golden sets exist.
* `--publish approve --approvers alice:pw,bob:pw`: the full path, which needs the evaluation to pass.

## The prompt-based alternative

Instead of editing YAML, describe the business to the copilot (`POST /api/v1/copilot/bootstrap/preview` to see the
blueprint, `POST /api/v1/copilot/bootstrap` with `stage: true` to create the drafts; or the copilot panel in the UI):
"We sell outdoor gear online and in six stores, ship from two DCs, offer in-store pickup, 30-day returns except on
clearance, no alcohol." It proposes a configuration shaped like this folder: order types, state models, decisions
with tier-appropriate bands, compliance rules, playbooks, SLAs and tenant settings, delivered as drafts for review in
the studio. Later policy changes work the same way ("Gold customers returning under $250 with a receipt get an
instant refund" becomes a draft `return_risk` version with a new L0 rule, lint, impact diff and evaluation). The
copilot never publishes; a person does, through the same approvals. Ready-to-paste prompts and what each should
produce are in `docs/examples/prompts/`.
