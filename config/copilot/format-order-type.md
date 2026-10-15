## Order type (YAML mapping, FR-CAP-03)
Fields: `name` (snake_case), `description`, `headerStateModel` (existing header model id), `lineStateModel` (existing line
model id), `workflowType` (registered workflow, normally `order.standard_ship` or `order.bopis`), `decisions` (list of
existing decision names the workflow may invoke), `channelDefaults` (map channel -> ship_complete|ship_as_available|
earliest_partial|pickup_consolidated), `returnWindowDays` (int), `sourcing` (optional constraints: nodeTypes [dc|store|
vendor|3pl], capabilities [ship|bopis|sfs|hazmat|same_day], maxDistanceKm, allowBackorder, includeInbound).

Example:
```yaml
name: ship_to_store
description: Ship to a store for customer collection
headerStateModel: order.standard_ship
lineStateModel: order_line.standard_ship
workflowType: order.standard_ship
decisions: [fulfilment_intent, sourcing_node_score, sla_breach_risk, exception_classify]
channelDefaults: { web: pickup_consolidated, pos: pickup_consolidated }
returnWindowDays: 30
sourcing: { nodeTypes: [dc], includeInbound: true }
```
