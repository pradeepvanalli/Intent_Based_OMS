package ai.intentoms.copilot.ops;

import ai.intentoms.copilot.ops.OpsQueryTemplate.Param;
import ai.intentoms.copilot.ops.OpsQueryTemplate.ParamType;
import ai.intentoms.platform.error.ValidationException;
import ai.intentoms.platform.tenant.TenantContext;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The whitelist of ops queries (FR-MON-08) and the typed parameter binding that stands between the model and the
 * database: unknown templates and malformed parameters are rejected, limits are capped at 500, and every query is
 * tenant-scoped. Also offers a keyword matcher so the endpoint keeps working without a model key.
 */
@Component
public class OpsQueryCatalog {
    public static final int MAX_LIMIT = 500;
    public static final int MAX_HOURS = 720;
    private static final Pattern IDENT = Pattern.compile("^[A-Za-z0-9_.:-]{1,64}$");
    private static final Pattern SNAKE = Pattern.compile("^[a-z][a-z0-9_]{0,63}$");
    private static final Pattern US_STATE = Pattern.compile("^[A-Z]{2}$");
    private static final Map<String, String> STATE_NAMES = Map.ofEntries(Map.entry("texas", "TX"), Map.entry("california", "CA"), Map.entry("new york", "NY"), Map.entry("new jersey", "NJ"),
        Map.entry("florida", "FL"), Map.entry("illinois", "IL"), Map.entry("washington", "WA"), Map.entry("georgia", "GA"), Map.entry("nevada", "NV"), Map.entry("arizona", "AZ"),
        Map.entry("ohio", "OH"), Map.entry("pennsylvania", "PA"), Map.entry("massachusetts", "MA"), Map.entry("colorado", "CO"), Map.entry("oregon", "OR"), Map.entry("michigan", "MI"));

    private final Map<String, OpsQueryTemplate> templates = new LinkedHashMap<>();

    public OpsQueryCatalog() {
        Param channel = new Param("channel", ParamType.CHANNEL, false, " and o.channel = :channel", "Sales channel (web, marketplace, pos, agent, b2b)");
        Param orderType = new Param("orderType", ParamType.ORDER_TYPE, false, " and o.order_type = :orderType", "Order type name (standard_ship, bopis, ship_from_store, ...)");
        Param node = new Param("nodeId", ParamType.NODE, false, " and o.primary_node_id = :nodeId", "Fulfilment node id (DC-NJ, STORE-ATX-01, ...)");
        Param state = new Param("state", ParamType.STATE, false, " and o.status = :state", "Order status / state id (released, shipped, sourcing, ...)");
        Param destState = new Param("destState", ParamType.US_STATE, false, " and exists (select 1 from order_address ad where ad.tenant_id = o.tenant_id and ad.order_id = o.id and ad.type = 'ship_to' and ad.address->>'state' = :destState)", "Destination US state code (TX, CA)");
        Param hours = new Param("hours", ParamType.HOURS, false, " and o.placed_at >= now() - make_interval(hours => :hours)", "Look-back window in hours since the order was placed (default 24, max 720)");
        Param limit = new Param("limit", ParamType.LIMIT, false, null, "Maximum rows (default 50, max 500)");
        Param sku = new Param("sku", ParamType.SKU, false, " and l.sku = :sku", "SKU code");
        Param customer = new Param("customerRef", ParamType.CUSTOMER_REF, false, " and o.customer_ref = :customerRef", "Customer reference");
        Param excType = new Param("exceptionType", ParamType.EXCEPTION_TYPE, false, " and e.type = :exceptionType", "Exception type (address, inventory_short, carrier_delay, payment, store_reject, system, stuck, ...)");
        Param decision = new Param("decisionName", ParamType.DECISION, false, " and d.decision = :decisionName", "Decision name (fulfilment_intent, return_risk, ...)");
        Param threshold = new Param("threshold", ParamType.THRESHOLD, false, null, "Numeric threshold (units) for low-stock queries, default 10");
        String open = " and o.status not in ('delivered','cancelled','picked_up','refunded')";

        add("orders_by_status", "Open orders counted by status with the oldest age, optionally filtered by channel, order type, node, destination state and window",
            "select o.status, count(*) as orders, round(extract(epoch from (now() - min(o.placed_at)))/60) as oldest_age_minutes from orders o where o.tenant_id = :tenant" + open + "{filters} group by o.status order by orders desc",
            List.of(channel, orderType, node, destState, hours), List.of("by status", "status", "pipeline", "open orders", "count"), "How many ship-from-store orders are open in Texas today?");
        add("orders_by_state_node_age", "Open orders by status and node with average minutes since the last update",
            "select o.status, o.primary_node_id as node_id, count(*) as orders, round(avg(extract(epoch from (now() - o.updated_at)))/60) as avg_age_minutes from orders o where o.tenant_id = :tenant" + open + "{filters} group by o.status, o.primary_node_id order by orders desc limit :limit",
            List.of(state, node, orderType, channel, destState, hours, limit), List.of("by node", "per node", "age", "node", "where are"), "Which nodes hold the most released orders and how old are they?");
        add("at_risk_orders", "Orders whose SLA breach risk score is 3 or higher, with risk reasons",
            "select o.id as order_id, o.channel, o.order_type, o.status, o.primary_node_id as node_id, r.score as risk_score, r.level as risk_level, r.reasons::text as reasons, o.placed_at from orders o join order_risk r on r.tenant_id = o.tenant_id and r.order_id = o.id where o.tenant_id = :tenant and r.score >= 3" + open + "{filters} order by r.score desc, o.placed_at limit :limit",
            List.of(channel, orderType, node, destState, hours, limit), List.of("at risk", "risk", "breach", "late", "danger"), "How many ship-from-store orders in Texas are at risk today?");
        add("exceptions_by_type", "Exceptions grouped by type, severity and queue with open counts",
            "select e.type, e.severity, e.queue, count(*) as total, count(*) filter (where e.status = 'OPEN') as open from exception e where e.tenant_id = :tenant and e.created_at >= now() - make_interval(hours => :hours){filters} group by e.type, e.severity, e.queue order by total desc",
            List.of(new Param("hours", ParamType.HOURS, false, null, "Look-back window in hours (default 24)"), new Param("exceptionType", ParamType.EXCEPTION_TYPE, false, " and e.type = :exceptionType", "Exception type")),
            List.of("exceptions", "exception", "errors", "failures", "problems"), "What exception types did we see this week?");
        add("open_exceptions", "Open exceptions with their order, queue and SLA due time",
            "select e.id, e.type, e.severity, e.queue, e.order_id, e.node_id, e.message, e.sla_due_at, e.created_at from exception e where e.tenant_id = :tenant and e.status = 'OPEN'{filters} order by e.created_at limit :limit",
            List.of(excType, new Param("nodeId", ParamType.NODE, false, " and e.node_id = :nodeId", "Node id"), limit), List.of("open exceptions", "unresolved", "inbox", "queue"), "Show the open inventory_short exceptions");
        add("split_rate_by_channel", "Share of orders split into more than one package per channel",
            "select o.channel, count(*) as orders, count(*) filter (where g.packages > 1) as split_orders, round(100.0 * count(*) filter (where g.packages > 1) / greatest(count(*), 1), 1) as split_rate_pct, round(avg(g.packages), 2) as avg_packages from orders o join (select order_id, count(*) as packages from fulfilment_group where tenant_id = :tenant and status not in ('rejected','cancelled') group by order_id) g on g.order_id = o.id where o.tenant_id = :tenant{filters} group by o.channel order by orders desc",
            List.of(orderType, hours), List.of("split", "packages", "shipments per order"), "What is the split rate by channel this week?");
        add("margin_floor_breaches", "Orders whose sourcing plan fell below the margin floor, with margin ratio and approval state",
            "select o.id as order_id, o.channel, o.order_type, o.status, o.grand_total, o.margin_ratio, (o.margin->>'floor')::double precision as floor, coalesce((o.margin->>'approvalPresent')::boolean, false) as approved, o.placed_at from orders o where o.tenant_id = :tenant and o.below_floor = true{filters} order by o.placed_at desc limit :limit",
            List.of(channel, orderType, node, hours, limit), List.of("margin", "floor", "below floor", "unprofitable", "approval"), "Which orders breached the margin floor today?");
        add("returns_by_timing", "Returns grouped by refund timing with counts and refund value",
            "select coalesce(r.refund_timing, 'undecided') as refund_timing, count(*) as returns, round(coalesce(sum((r.refund->>'amount')::numeric), 0), 2) as refund_value, round(avg(r.risk_score)::numeric, 2) as avg_risk_score from return_rma r where r.tenant_id = :tenant and r.created_at >= now() - make_interval(hours => :hours){filters} group by r.refund_timing order by returns desc",
            List.of(new Param("hours", ParamType.HOURS, false, null, "Look-back window in hours (default 168)"), new Param("channel", ParamType.CHANNEL, false, " and r.channel = :channel", "Return channel (web, store, agent, cs)")),
            List.of("returns", "refund", "rma", "instant", "store credit"), "How many returns got instant refunds this week?");
        add("decision_band_mix", "Decision volume by band and action per decision",
            "select d.decision, d.band, d.action, count(*) as decisions, round(avg(d.confidence)::numeric, 3) as avg_confidence, count(*) filter (where d.overridden_at is not null) as overrides from decision_record d where d.tenant_id = :tenant and d.mode = 'LIVE' and d.created_at >= now() - make_interval(hours => :hours){filters} group by d.decision, d.band, d.action order by d.decision, decisions desc",
            List.of(new Param("hours", ParamType.HOURS, false, null, "Look-back window in hours (default 168)"), decision), List.of("decision", "band", "escalat", "confidence", "override", "act"), "How often did return_risk escalate this week?");
        add("promise_misses", "Open orders whose promised delivery date has passed",
            "select o.id as order_id, o.channel, o.order_type, o.status, g.node_id, g.promise_date, g.on_time_probability, o.placed_at from orders o join fulfilment_group g on g.tenant_id = o.tenant_id and g.order_id = o.id and g.status not in ('rejected','cancelled') where o.tenant_id = :tenant and g.promise_date < current_date" + open + "{filters} order by g.promise_date limit :limit",
            List.of(channel, orderType, node, destState, limit), List.of("promise", "missed", "late delivery", "overdue", "past due"), "Which orders have missed their promise date?");
        add("backordered_lines", "Order lines currently backordered",
            "select o.id as order_id, l.line_id, l.sku, l.qty, o.channel, o.order_type, o.placed_at from order_line l join orders o on o.id = l.order_id and o.tenant_id = l.tenant_id where l.tenant_id = :tenant and l.status = 'backordered'{filters} order by o.placed_at limit :limit",
            List.of(sku, channel, orderType, limit), List.of("backorder", "backordered", "out of stock", "waiting for stock"), "Which lines are backordered?");
        add("agent_orders", "Orders placed by AI agents with their status and value",
            "select o.id as order_id, o.provenance->>'agentId' as agent_id, o.provenance->>'protocol' as protocol, o.status, o.grand_total, o.channel, o.placed_at from orders o where o.tenant_id = :tenant and o.agent_order = true{filters} order by o.placed_at desc limit :limit",
            List.of(state, destState, hours, limit), List.of("agent", "agents", "bot", "mandate", "ucp", "mcp"), "How many agent orders came in today?");
        add("stuck_orders", "Open orders with no update for longer than the given number of hours",
            "select o.id as order_id, o.channel, o.order_type, o.status, o.primary_node_id as node_id, round(extract(epoch from (now() - o.updated_at))/60) as idle_minutes, o.placed_at from orders o where o.tenant_id = :tenant" + open + " and o.updated_at < now() - make_interval(hours => :hours){filters} order by o.updated_at limit :limit",
            List.of(new Param("hours", ParamType.HOURS, false, null, "Idle threshold in hours (default 24)"), state, node, orderType, channel, limit), List.of("stuck", "idle", "no update", "not moving", "stale"), "Which orders have been stuck for more than 12 hours?");
        add("revenue_by_channel", "Order count and gross revenue by channel",
            "select o.channel, count(*) as orders, round(coalesce(sum(o.grand_total), 0), 2) as revenue, round(coalesce(avg(o.grand_total), 0), 2) as avg_order_value from orders o where o.tenant_id = :tenant and o.status <> 'cancelled'{filters} group by o.channel order by revenue desc",
            List.of(orderType, destState, hours), List.of("revenue", "sales", "gmv", "order value", "aov"), "What was revenue by channel this week?");
        add("inventory_low_stock", "SKU/node positions whose on-hand quantity is below a threshold",
            "select p.sku, p.node_id, sum(p.qty) as on_hand from inventory_position p where p.tenant_id = :tenant and p.supply_type = 'on_hand' and p.demand_type = ''{filters} group by p.sku, p.node_id having sum(p.qty) < :threshold order by on_hand limit :limit",
            List.of(new Param("sku", ParamType.SKU, false, " and p.sku = :sku", "SKU code"), new Param("nodeId", ParamType.NODE, false, " and p.node_id = :nodeId", "Node id"), threshold, limit),
            List.of("inventory", "stock", "low stock", "atp", "on hand"), "Which SKUs are low on stock at DC-NJ?");
        add("order_lookup", "Find orders by id, channel order id or customer reference",
            "select o.id as order_id, o.channel, o.channel_order_id, o.order_type, o.status, o.customer_ref, o.grand_total, o.primary_node_id as node_id, o.placed_at from orders o where o.tenant_id = :tenant{filters} order by o.placed_at desc limit :limit",
            List.of(customer, new Param("orderId", ParamType.CUSTOMER_REF, false, " and (o.id = :orderId or o.channel_order_id = :orderId)", "Order id or channel order id"), state, limit),
            List.of("find order", "lookup", "customer", "order id", "orders for"), "Show the orders for customer cust-jane");

        // XP-06: the self-optimizing layer's tables.
        add("learning_proposals_by_status", "Learning proposals counted by status with the oldest one in each",
            "select p.status, count(*) as proposals, min(p.created_at) as oldest, count(distinct p.target_ref) as targets from proposal p where p.tenant_id = :tenant and p.created_at >= now() - make_interval(hours => :hours) group by p.status order by proposals desc",
            List.of(new Param("hours", ParamType.HOURS, false, null, "Look-back window in hours (default 720)")), List.of("proposal", "proposals", "pending change", "learning queue", "awaiting review", "waiting for review", "drafted change"), "How many learning proposals are waiting for review?");
        add("learning_recent_promotions", "Changes the learning layer promoted or rolled back, newest first",
            "select p.proposal_id, p.target_ref, p.tier, p.status, coalesce(p.approved_by, 'automatic') as approved_by, p.promoted_at, p.rolled_back_at, p.rolled_back_reason from proposal p where p.tenant_id = :tenant and (p.promoted_at >= now() - make_interval(hours => :hours) or p.rolled_back_at >= now() - make_interval(hours => :hours)) order by coalesce(p.rolled_back_at, p.promoted_at) desc limit :limit",
            List.of(new Param("hours", ParamType.HOURS, false, null, "Look-back window in hours (default 720)"), limit), List.of("promoted", "promotion", "promote", "rolled back", "rollback", "roll back", "what changed", "learning changed", "learning promote", "went live"), "What did learning promote this month?");
        add("learner_status", "Every learner with its autonomy level, on/off switch, pause reason and last run",
            "select l.learner_key, l.autonomy_level, l.enabled, l.paused_reason, l.last_run_at, l.max_promotions_per_week from learner l where l.tenant_id = :tenant order by l.learner_key",
            List.of(), List.of("learner", "learners", "autonomy", "paused", "kill switch", "switched off"), "Which learners are paused?");
        add("episode_outcome_coverage", "Episodes per decision with how many have an outcome, are mature, and their mean reward",
            "select e.decision_name, count(*) as episodes, count(*) filter (where exists (select 1 from outcome o where o.tenant_id = e.tenant_id and o.episode_id = e.episode_id)) as with_outcome, count(*) filter (where e.status = 'MATURE') as mature, round(avg(e.reward)::numeric, 4) as mean_reward from episode e where e.tenant_id = :tenant and e.created_at >= now() - make_interval(hours => :hours){filters} group by e.decision_name order by episodes desc",
            List.of(new Param("hours", ParamType.HOURS, false, null, "Look-back window in hours (default 720)"), new Param("decisionName", ParamType.DECISION, false, " and e.decision_name = :decisionName", "Decision name")),
            List.of("episode", "episodes", "coverage", "reward", "outcomes", "outcome coverage", "outcome", "coverage per decision", "missing outcomes"), "What is the outcome coverage per decision?");
        add("learned_facts_by_key", "Active learned facts counted by subject type and fact key, with the latest update",
            "select f.subject_type, f.fact_key, count(*) as active_facts, max(f.created_at) as last_updated, round(avg(f.sample_size)::numeric, 1) as avg_sample_size from learned_fact f where f.tenant_id = :tenant and f.valid_to is null group by f.subject_type, f.fact_key order by active_facts desc",
            List.of(), List.of("learned fact", "facts", "learned", "posterior", "node reliability", "learned value"), "Which learned facts do we have?");
    }

    private void add(String name, String description, String sql, List<Param> params, List<String> keywords, String example) {
        templates.put(name, new OpsQueryTemplate(name, description, sql, params, keywords, example));
    }

    public List<OpsQueryTemplate> all() { return new ArrayList<>(templates.values()); }
    public List<String> names() { return new ArrayList<>(templates.keySet()); }
    public Optional<OpsQueryTemplate> find(String name) { return Optional.ofNullable(templates.get(name)); }

    /** Binds and validates parameters for a template; throws {@link ValidationException} on anything outside the whitelist. */
    public BoundQuery bind(String templateName, Map<String, Object> raw) {
        OpsQueryTemplate t = find(templateName).orElseThrow(() -> new ValidationException("Unknown ops query template '" + templateName + "'", Map.of("known", names())));
        Map<String, Object> bound = new LinkedHashMap<>();
        bound.put("tenant", TenantContext.require());
        StringBuilder filters = new StringBuilder();
        for (Param p : t.params()) {
            Object v = raw == null ? null : raw.get(p.name());
            if (v == null || (v instanceof String s && s.isBlank())) {
                if (p.required()) throw new ValidationException("Parameter '" + p.name() + "' is required for " + templateName);
                if (p.type() == ParamType.HOURS) bound.put(p.name(), defaultHours(templateName));
                else if (p.type() == ParamType.LIMIT) bound.put(p.name(), 50);
                else if (p.type() == ParamType.THRESHOLD) bound.put(p.name(), 10);
                continue;
            }
            Object typed = coerce(p, v);
            bound.put(p.name(), typed);
            if (p.clause() != null) filters.append(p.clause());
        }
        if (t.sql().contains(":limit") && !bound.containsKey("limit")) bound.put("limit", 50);
        if (t.sql().contains(":hours") && !bound.containsKey("hours")) bound.put("hours", defaultHours(templateName));
        if (t.sql().contains(":threshold") && !bound.containsKey("threshold")) bound.put("threshold", 10);
        String sql = t.sql().replace("{filters}", filters.toString());
        return new BoundQuery(t, sql, bound);
    }

    private static int defaultHours(String template) {
        if (template.startsWith("learning_") || template.equals("episode_outcome_coverage")) return 720;
        return template.equals("returns_by_timing") || template.equals("decision_band_mix") ? 168 : 24;
    }

    static Object coerce(Param p, Object v) {
        String s = String.valueOf(v).trim();
        switch (p.type()) {
            case HOURS -> { int h = asInt(p, v); if (h < 1 || h > MAX_HOURS) throw new ValidationException("Parameter 'hours' must be 1.." + MAX_HOURS); return h; }
            case LIMIT -> { int l = asInt(p, v); if (l < 1) throw new ValidationException("Parameter 'limit' must be >= 1"); return Math.min(l, MAX_LIMIT); }
            case THRESHOLD -> { int th = asInt(p, v); if (th < 0 || th > 1_000_000) throw new ValidationException("Parameter 'threshold' must be 0..1000000"); return th; }
            case US_STATE -> { String u = s.toUpperCase(Locale.ROOT); if (!US_STATE.matcher(u).matches()) throw new ValidationException("Parameter 'destState' must be a two-letter US state code"); return u; }
            case STATE, ORDER_TYPE, CHANNEL, EXCEPTION_TYPE, DECISION -> { String lower = s.toLowerCase(Locale.ROOT); if (!SNAKE.matcher(lower).matches()) throw new ValidationException("Parameter '" + p.name() + "' must be a snake_case identifier, got '" + s + "'"); return lower; }
            case NODE, SKU, CUSTOMER_REF -> { if (!IDENT.matcher(s).matches()) throw new ValidationException("Parameter '" + p.name() + "' contains characters outside [A-Za-z0-9_.:-]: '" + s + "'"); return s; }
        }
        throw new ValidationException("Unsupported parameter type " + p.type());
    }

    private static int asInt(Param p, Object v) {
        if (v instanceof Number n) return n.intValue();
        try { return Integer.parseInt(String.valueOf(v).trim()); } catch (NumberFormatException e) { throw new ValidationException("Parameter '" + p.name() + "' must be an integer"); }
    }

    /** Deterministic template choice from keywords; used when no model key is configured. */
    public BoundQuery match(String question) {
        String q = question == null ? "" : question.toLowerCase(Locale.ROOT);
        OpsQueryTemplate best = null;
        int bestScore = 0;
        for (OpsQueryTemplate t : templates.values()) {
            int score = 0;
            for (String k : t.keywords()) if (q.contains(k)) score += k.length();
            if (score > bestScore) { best = t; bestScore = score; }
        }
        if (best == null) best = templates.get("orders_by_status");
        Map<String, Object> params = new LinkedHashMap<>();
        if (q.contains("today")) params.put("hours", 24);
        else if (q.contains("this week") || q.contains("last 7") || q.contains("past week") || q.contains("week")) params.put("hours", 168);
        else if (q.contains("this month") || q.contains("last 30")) params.put("hours", 720);
        java.util.regex.Matcher hm = Pattern.compile("(\\d{1,3})\\s*hours?").matcher(q);
        if (hm.find()) params.put("hours", Integer.parseInt(hm.group(1)));
        for (var e : STATE_NAMES.entrySet()) if (q.contains(e.getKey())) params.put("destState", e.getValue());
        for (String ch : List.of("marketplace", "web", "pos", "agent", "b2b")) if (q.contains(ch + " orders") || q.contains(ch + " channel")) params.put("channel", ch);
        for (String ot : List.of("ship_from_store", "ship-from-store", "bopis", "same_day", "same-day", "drop_ship", "pre_order", "backorder")) if (q.contains(ot)) params.put("orderType", ot.replace('-', '_').equals("same_day") ? "same_day_local" : ot.replace('-', '_'));
        java.util.regex.Matcher nm = Pattern.compile("\\b([A-Z]{2,6}-[A-Z0-9-]{2,12})\\b").matcher(question == null ? "" : question);
        if (nm.find()) params.put("nodeId", nm.group(1));
        for (String st : List.of("released", "picking", "packed", "shipped", "sourcing", "backordered", "awaiting_sourcing", "created", "on_hold")) if (q.contains(st)) params.put("state", st);
        return bind(best.name(), params);
    }
}
