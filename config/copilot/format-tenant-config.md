## Tenant configuration (mapping key -> value)
Only the whitelisted keys listed in the catalogue may be set; each has a type and allowed range. Values are plain
YAML/JSON scalars, lists or maps. Example:
```yaml
sourcing.margin_floor: 0.15
sourcing.split_penalty_usd: 15.0
returns.window_days: 45
sourcing.channel_default_policy: { web: ship_as_available, marketplace: ship_complete }
```
