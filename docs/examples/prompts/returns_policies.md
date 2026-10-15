# Returns policies

Each prompt produces a draft of the `return_risk` decision (or a tenant setting) with lint, a 30-day impact diff and
an evaluation report. Review in Policy Studio → return_risk → latest draft.

### Trusted fast lane

> Gold and platinum customers returning an item under $250 with a receipt should get an instant refund without a
> risk score.

Expected: new L0 rule ahead of the model, e.g.
```yaml
- id: loyalty_fast_lane
  when: "(input.customer.tier == 'gold' || input.customer.tier == 'platinum') && input.return.receipt == true && input.return.value_usd <= 250"
  then: 0
  reason: loyalty_fast_lane
  priority: 35
```
Impact report: how many of the last 30 days' returns would flip to `instant`. Reviewer: confirm `customer.tier` is in
`state.include` or the input (the copilot adds it to the input contract note), and that the rule sits *after*
`flagged_customer` (priority 30) so a flagged gold customer is still caught.

### Store credit for repeat "never arrived" claims

> Anyone with two or more "item not received" claims in the last year gets store credit only, never a card refund.

Expected: the existing `flagged_customer` rule already covers `not_received_claims >= 2` with score 4 → the copilot
says so, shows the rule, and proposes no change (or a wording tweak to the rule reason). If the tenant's rule is
missing, it drafts it.

### Final sale exceptions

> Final-sale items can still be returned if they arrived defective.

Expected: the `final_sale` rule's `when` becomes
`input.order.final_sale == true && input.return.reason != 'defective'`, with a note that `return.reason` is normalised
by the `return_reason` decision, so free-text "broken on arrival" maps to `defective` first.

### Keep-it threshold

> Stop offering "keep it" refunds on anything over $40.

Expected: not a decision change. The copilot explains that keep-it is decided by economics in the returns module
(`returns.return_shipping_cost_usd`, `returns.return_handling_cost_usd`, `returns.recovery_rate`) and that there is no
value cap key today; it proposes the closest available change (`returns.keep_it_enabled: false` or adjusting
`returns.recovery_rate`) and flags the missing key as a feature request. Reviewer: decide whether to accept the
approximation.

### High-value human review

> Any return over $1,000 must be reviewed by a person regardless of the score.

Expected: L0 rule `when: "input.return.value_usd > 1000"`, `then: escalate`, `reason: high_value_review`, priority
before the fast lanes; plus a note that refunds above `returns.refund_requires_approval_above_usd` (default 500)
already need approval at refund time, so the two controls overlap and the reviewer may choose one.

### Tighten autonomy

> The return risk model has been too generous; only act when it is really sure.

Expected: the copilot refuses to change the band threshold itself (GR-14: thresholds are human-only) and instead
explains the current T2 bands (`act >= 0.9`), shows the last 30 days' band mix and override rate, and offers a draft
that sharpens the Score rubric wording. The reviewer changes the band in the studio if still wanted.
