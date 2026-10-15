package ai.intentoms.copilot.change;

import java.util.Map;

/**
 * Documented input shapes of the starter decisions (MODULE_NOTES "Demo decision input shapes"). Given to the model so
 * generated L0 rules and golden cases use real paths, and used to sanity-check rule roots.
 */
public final class DecisionInputShapes {
    private DecisionInputShapes() {}

    public static final Map<String, String> SHAPES = Map.ofEntries(
        Map.entry("fulfilment_intent", "order{channel, notes, intent_choices[], has_hazmat, total, destination_state, agent_order}, customer{tier, history{split_acceptance_rate}}, chosen_plan{margin, packages} (postChecks only) → ship_complete|ship_as_available|earliest_partial|pickup_consolidated|unclear"),
        Map.entry("sourcing_node_score", "order{destination_state, ship_method, line_count}, candidates[]{id, type, backlog_units, reject_rate, staffing, minutes_to_cutoff, distance_km, cost_usd} → ranking"),
        Map.entry("split_acceptability", "order{channel, notes, intent}, customer{tier, history{split_acceptance_rate, split_complaints}}, plan_options[]{packages, eta, cost_delta} → boolean"),
        Map.entry("exception_classify", "exception{source, code, message}, order{channel, status} → address|inventory_short|carrier_delay|payment|store_reject|system|other|unclear"),
        Map.entry("sla_breach_risk", "order{state, hours_in_state, promise_hours_remaining, ship_method}, node{backlog_units, reject_rate, minutes_to_cutoff}, carrier{on_time_rate_7d} → score 0-4"),
        Map.entry("customer_request_intent", "request{channel, text}, order{status, days_since_placed} → cancel_order|cancel_line|change_address|change_item|wismo|return|other"),
        Map.entry("return_risk", "return{reason, days_since_delivery, receipt (boolean), item_condition, value_usd}, order{channel, final_sale}, customer{tier, history{returns_12m, not_received_claims, flags[]}}, policy{window_days} → deny_out_of_window|deny_final_sale|score 0-4 (0 = instant refund fast lane)"),
        Map.entry("return_reason", "return{customer_reason_text, reason_code, item_condition}, item{category}"),
        Map.entry("return_disposition", "return{reason, item_condition, inspection_notes}, item{category, cost_usd, resale_value_usd, vendor_return_eligible, hazmat} → restock|refurbish|liquidate|return_to_vendor|donate|destroy"),
        Map.entry("agent_mandate_match", "mandate{instruction_text, spend_limit, scope, expires_at}, order{total, items_summary, ship_to_country, ship_to_state}, agent{trust_tier} (T3, never acts)"),
        Map.entry("injection_screen", "text{source, content} → boolean"),
        Map.entry("address_risk", "address{verification_status, type, unit_present, po_box, freight_forwarder_match, prior_failed_deliveries, state}, order{total, ship_method, agent_order, channel} → boolean"),
        Map.entry("appeasement", "case{issue, severity, customer_sentiment}, order{total, shipping_paid}, customer{tier, history{appeasements_12m, orders_12m}}, request{credit_usd}, actor{limit_usd}"),
        Map.entry("backorder_next_action", "line{sku, days_backordered, expected_restock_days, substitute_available}, order{channel, intent}, customer{tier}, network{atp_elsewhere}"));

    public static String describe(String decision) { return SHAPES.getOrDefault(decision, "input paths as listed in state.include"); }
}
