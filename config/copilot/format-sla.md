## SLA definitions (YAML, FR-MON-02)
`slas`: list of `{orderType ("*" = any), entity (order|order_line|return), state, maxAge (ISO-8601 duration),
onBreach (playbook.<existing playbook> | decision.<existing decision>)}`. Optional `expectedNextEventWithin` map
state -> duration drives the stuck-order sweeper.

Example:
```yaml
slas:
  - { orderType: same_day_local, entity: order_line, state: released, maxAge: PT1H, onBreach: playbook.store_backlog }
  - { orderType: "*", entity: order_line, state: packed, maxAge: PT12H, onBreach: playbook.repromise }
expectedNextEventWithin:
  released: PT2H
```
