## CEL expressions
All guards, rules, pre/post checks and `when:` conditions are Common Expression Language (CEL) booleans. Roots are
untyped maps; use `has(x.y)` before reading optional fields. String comparison with `==`, lists with `in`,
`list.exists(e, cond)`, `size(list)`, `string.startsWith()`, `matches()`. Numbers compare heterogeneously (int vs double ok).

Roots per context:
- Decision L0 `rules[].when`, `preChecks`: the decision input under `input` (e.g. `input.order.total`,
  `input.customer.tier == 'gold'`, `'hazmat' in input.order.flags`). `tenant` holds tenant config with dots replaced by
  underscores (`tenant.margin_floor`). `postChecks` additionally see `answers`, `chosen_plan` and the input roots directly
  (`chosen_plan.margin >= tenant.margin_floor`, `order.has_hazmat`).
- State-model transition `guards`: `plan` (plan.margin, plan.result), `approval` (approval.present), `order`
  (order.holds list of {type, blocks}, order.channel, order.total, order.agent_order, order.destination_state when
  provided by the caller), `tenant`, `ctx`, `state`, `now`. Example: `"plan.margin >= tenant.margin_floor || approval.present"`.
- State-model `rollup[].when`: `state` = map stateId -> quantity, `ctx.total`, `ctx.states_with_qty` (list).
- Playbook step `when`: `exception` (exception.age_minutes, exception.type, exception.severity), `order`, `decision`.
- Compliance `when`: `line` (line.hazmat, line.category, line.age_restricted, line.lithium_battery, line.export_controlled,
  line.min_age, line.sku), `order.destination.{state, zip, zip5, country, address_type, is_po_box}`,
  `order.customer.{age_verified, age}`, `order.ship_method`, `order.ship_method_is_air`, `order.line_count`, `tenant`.
Reserved: `return` is a CEL keyword and cannot be a root in guards; decision inputs reach it as `input.return.*`.
