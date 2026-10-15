## Compliance rules (YAML, FR-FIN-03 / FR-CAP-05)
`rules`: list of `{id (snake_case), name, scope (line|order), when (CEL), action (block|hold|require_signature),
reason_code (UPPER_SNAKE), severity (error|warning), message (may use {sku} {state} {zip} {shipMethod} {country}), priority (int, lower first)}`.
Roots: `line.*` (hazmat, category, age_restricted, lithium_battery, export_controlled, min_age, sku, qty),
`order.destination.{state, zip, zip5, country, address_type, is_po_box}`, `order.customer.{age_verified, age}`,
`order.ship_method`, `order.ship_method_is_air`, `tenant.*`.

Example:
```yaml
rules:
  - id: knives_no_minors
    name: Blades require a verified adult
    scope: line
    when: line.category == "blade" && !order.customer.age_verified
    action: hold
    reason_code: BLADE_AGE_VERIFICATION
    severity: error
    message: "{sku} is a blade; the customer must verify age before release"
    priority: 35
```
