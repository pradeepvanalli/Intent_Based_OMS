-- D-3 (EX-07): live use of a learned family is as risky as the riskiest live decision it feeds, never below T1.
-- Live-use proposals were always stored as T1, so fraud.signal_risk / fraud.address_reuse_count (payment_risk, T2) and
-- cs.appeasement_level (appeasement, T2) could auto-promote at L3. New proposals get the right tier when drafted and the
-- promotion gate recomputes it (LiveUseTier); this re-tiers the ones already stored. The family -> decision pairs are
-- LiveFamilies' affectedDecisions at the time of this migration; a decision that is not live in a tenant is ignored.
with family_decision(family, decision) as (values
    ('sourcing.node_reject_rate', 'sourcing_node_score'),
    ('sourcing.node_on_time_rate', 'sourcing_node_score'),
    ('sourcing.reject_penalty', 'sourcing_node_score'),
    ('sourcing.node_backlog_breach', 'sourcing_node_score'),
    ('sourcing.node_backlog_breach', 'sla_breach_risk'),
    ('sourcing.policy_bandit', 'sourcing_policy_bandit'),
    ('sourcing.policy_bandit', 'sourcing_node_score'),
    ('sourcing.aged_stock_tiebreak', 'sourcing_node_score'),
    ('promise.lane_transit_days', 'sla_breach_risk'),
    ('promise.lane_transit_days', 'sourcing_node_score'),
    ('promise.lane_calibration', 'sla_breach_risk'),
    ('promise.lane_calibration', 'sourcing_node_score'),
    ('promise.buffer_days', 'sla_breach_risk'),
    ('monitoring.warn_early_threshold', 'sla_breach_risk'),
    ('cs.appeasement_level', 'appeasement'),
    ('inventory.safety_stock', 'sourcing_node_score'),
    ('inventory.reservation_ttl', 'sourcing_node_score'),
    ('inventory.inbound_reliability', 'sourcing_node_score'),
    ('inventory.backorder_conversion', 'backorder_next_action'),
    ('customer.preferences', 'fulfilment_intent'),
    ('fraud.address_reuse_count', 'payment_risk'),
    ('fraud.signal_risk', 'payment_risk')
), needed as (
    select p.tenant_id, p.proposal_id, max(d.tier) as tier
    from proposal p
    join family_decision f on p.target_ref = 'parameter:live:' || f.family
    join decision_definition d on d.tenant_id = p.tenant_id and d.name = f.decision and d.status = 'live'
    where d.tier in ('T0', 'T1', 'T2', 'T3')
    group by p.tenant_id, p.proposal_id
)
update proposal p set tier = n.tier
from needed n
where p.tenant_id = n.tenant_id and p.proposal_id = n.proposal_id and n.tier > p.tier;
