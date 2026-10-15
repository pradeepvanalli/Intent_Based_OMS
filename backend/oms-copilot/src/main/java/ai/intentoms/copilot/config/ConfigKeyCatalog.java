package ai.intentoms.copilot.config;

import ai.intentoms.platform.config.ConfigKeys;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Whitelist of tenant configuration keys the copilot may propose (GR-04: free text never becomes a value without a typed
 * parse). Each entry declares the value type, allowed range / enumerations and a description shown to the model and
 * the UI. Keys outside the catalogue are rejected by validation.
 */
public final class ConfigKeyCatalog {
    public enum Type { NUMBER, INTEGER, BOOLEAN, STRING, DURATION, MAP, LIST }

    /** One whitelisted key. {@code min}/{@code max} apply to numbers; {@code allowedValues} to strings and map values. */
    public record Entry(String key, Type type, String description, Double min, Double max, Set<String> allowedValues) {}

    private static final Set<String> POLICIES = Set.of("ship_complete", "ship_as_available", "earliest_partial", "pickup_consolidated");
    private static final Map<String, Entry> ENTRIES = new LinkedHashMap<>();

    static {
        num(ConfigKeys.MARGIN_FLOOR, "Minimum contribution margin ratio a sourcing plan must meet; below it a human approval is required (GR-01)", 0, 0.9);
        intg(ConfigKeys.MAX_SPLIT_PACKAGES, "Maximum packages one order may be split into", 1, 10);
        num(ConfigKeys.SPLIT_PENALTY_USD, "Cost added per extra package when ranking sourcing plans", 0, 500);
        intg(ConfigKeys.CANDIDATE_LIMIT, "Maximum candidate nodes considered per sourcing request", 5, 1000);
        map(ConfigKeys.CHANNEL_DEFAULT_POLICY, "Default fulfilment policy per channel when intent is unclear", POLICIES);
        num("inventory.channel_buffer", "Share of ATP held back per channel (0..0.5)", 0, 0.5);
        bool("inventory.include_inbound", "Count inbound supply (PO/ASN) in ATP");
        num("finance.payment_fee_rate", "Payment processing fee rate used in P&L", 0, 0.2);
        num("finance.returns_reserve_rate", "Share of revenue reserved for returns in P&L", 0, 0.5);
        num("tax.default_rate", "Flat tax rate when no state rate applies", 0, 0.5);
        map("tax.rates", "Tax rate per destination state code", null);
        num(ConfigKeys.REFUND_VELOCITY_CAP_USD_PER_HOUR, "Refund spend allowed per hour before the circuit breaker trips (GR-05)", 0, 1_000_000);
        num(ConfigKeys.APPEASEMENT_VELOCITY_CAP_USD_PER_HOUR, "Appeasement spend allowed per hour (GR-05)", 0, 1_000_000);
        num(ConfigKeys.AI_BUDGET_USD_PER_DAY, "Daily AI spend budget; above it decisions degrade to L0 and the copilot refuses (GR-17)", 0, 100_000);
        bool(ConfigKeys.AI_KILL_SWITCH, "Disable every L1/L2 model call tenant-wide (GR-06)");
        intg("returns.window_days", "Default return window in days", 0, 3650);
        num("returns.restocking_fee_pct", "Restocking fee as a share of the item price", 0, 0.5);
        bool("returns.keep_it_enabled", "Offer keep-it refunds when the return costs more than the recovery");
        list("returns.flagged_customers", "Customer refs flagged for return abuse review");
        map("appeasement.role_limits", "Maximum appeasement in USD per role", null);
        num("agent.default_max_order_value", "Default per-order value limit for agents", 0, 1_000_000);
        num("agent.default_max_refund", "Default refund limit for agents", 0, 1_000_000);
        list("capture.validation_steps", "Ordered capture validation steps", Set.of("schema", "address", "item_price_sanity", "payment_status", "compliance", "agent_provenance"));
        intg("alerts.dedupe_minutes", "Alert suppression window in minutes", 1, 1440);
        dur("orchestration.fulfilment_timeout", "How long the workflow waits for fulfilment before raising an exception");
        dur("orchestration.delivery_timeout", "How long the workflow waits for delivery");
        dur("orchestration.pickup_window", "How long a BOPIS order waits for pickup");
        map("playbooks.spend_limits", "Spend limits for playbook actions: reship_usd, refund_usd, appeasement_usd", null);
        list("compliance.restricted_zips", "Destination ZIP codes (or prefixes) requiring manual review");
        map("compliance.restricted_states", "Category -> list of states where it may not ship", null);
        map("sourcing.order_type_constraints", "Order type -> sourcing constraints (nodeTypes, capabilities, maxDistanceKm, allowBackorder, includeInbound)", null);
        str("alerts.email", "Email address for alerts", null);
    }

    private ConfigKeyCatalog() {}

    private static void num(String k, String d, double min, double max) { ENTRIES.put(k, new Entry(k, Type.NUMBER, d, min, max, Set.of())); }
    private static void intg(String k, String d, double min, double max) { ENTRIES.put(k, new Entry(k, Type.INTEGER, d, min, max, Set.of())); }
    private static void bool(String k, String d) { ENTRIES.put(k, new Entry(k, Type.BOOLEAN, d, null, null, Set.of())); }
    private static void str(String k, String d, Set<String> allowed) { ENTRIES.put(k, new Entry(k, Type.STRING, d, null, null, allowed == null ? Set.of() : allowed)); }
    private static void dur(String k, String d) { ENTRIES.put(k, new Entry(k, Type.DURATION, d, null, null, Set.of())); }
    private static void map(String k, String d, Set<String> allowedValues) { ENTRIES.put(k, new Entry(k, Type.MAP, d, null, null, allowedValues == null ? Set.of() : allowedValues)); }
    private static void list(String k, String d) { ENTRIES.put(k, new Entry(k, Type.LIST, d, null, null, Set.of())); }
    private static void list(String k, String d, Set<String> allowed) { ENTRIES.put(k, new Entry(k, Type.LIST, d, null, null, allowed)); }

    public static Map<String, Entry> entries() { return java.util.Collections.unmodifiableMap(ENTRIES); }
    public static boolean allowed(String key) { return ENTRIES.containsKey(key); }

    /** Validates one key/value; returns an empty list when acceptable. */
    public static List<String> validate(String key, Object value) {
        List<String> issues = new ArrayList<>();
        Entry e = ENTRIES.get(key);
        if (e == null) { issues.add("key '" + key + "' is not in the tenant configuration whitelist"); return issues; }
        if (value == null) { issues.add("key '" + key + "' has no value"); return issues; }
        switch (e.type()) {
            case NUMBER, INTEGER -> {
                Double d = asNumber(value);
                if (d == null) issues.add("key '" + key + "' must be a number");
                else {
                    if (e.type() == Type.INTEGER && d != Math.rint(d)) issues.add("key '" + key + "' must be an integer");
                    if (e.min() != null && d < e.min()) issues.add("key '" + key + "' must be >= " + e.min());
                    if (e.max() != null && d > e.max()) issues.add("key '" + key + "' must be <= " + e.max());
                }
            }
            case BOOLEAN -> { if (!(value instanceof Boolean) && !("true".equalsIgnoreCase(value.toString()) || "false".equalsIgnoreCase(value.toString()))) issues.add("key '" + key + "' must be true or false"); }
            case STRING -> { if (!(value instanceof String s)) issues.add("key '" + key + "' must be a string"); else if (!e.allowedValues().isEmpty() && !e.allowedValues().contains(s)) issues.add("key '" + key + "' must be one of " + e.allowedValues()); }
            case DURATION -> { try { java.time.Duration.parse(value.toString()); } catch (Exception ex) { issues.add("key '" + key + "' must be an ISO-8601 duration such as PT4H or P5D"); } }
            case MAP -> {
                if (!(value instanceof Map<?, ?> m)) issues.add("key '" + key + "' must be a mapping");
                else if (!e.allowedValues().isEmpty()) m.forEach((k, v) -> { if (!e.allowedValues().contains(String.valueOf(v))) issues.add("key '" + key + "': value for '" + k + "' must be one of " + e.allowedValues()); });
            }
            case LIST -> {
                if (!(value instanceof List<?> l)) issues.add("key '" + key + "' must be a list");
                else if (!e.allowedValues().isEmpty()) l.forEach(v -> { if (!e.allowedValues().contains(String.valueOf(v))) issues.add("key '" + key + "': '" + v + "' must be one of " + e.allowedValues()); });
            }
        }
        return issues;
    }

    private static Double asNumber(Object v) {
        if (v instanceof Number n) return n.doubleValue();
        try { return Double.parseDouble(v.toString().trim()); } catch (NumberFormatException e) { return null; }
    }
}
