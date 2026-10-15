package ai.intentoms.finance.compliance;

import ai.intentoms.finance.config.FinanceConfigKeys;
import ai.intentoms.platform.cel.CelEngine;
import ai.intentoms.platform.config.TenantConfigStore;
import ai.intentoms.platform.observability.Metrics;
import ai.intentoms.platform.tenant.TenantContext;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * CEL-driven {@link ComplianceService} (FR-FIN-03, FR-CAP-05). For each active rule, in priority order, the
 * expression is evaluated once per line ({@code scope: line}) or once per order ({@code scope: order}) with:
 * <ul>
 *   <li>{@code line}: {@code line_id}, {@code sku}, {@code qty}, every attribute flattened (plus the well-known
 *       attributes defaulted) and the raw {@code attributes} map;</li>
 *   <li>{@code order}: {@code destination} {state, zip, zip5, country, address_type, is_po_box}, {@code customer}
 *       {age_verified, age}, {@code ship_method}, {@code ship_method_is_air};</li>
 *   <li>{@code tenant}: {@link TenantConfigStore#celTenantMap()} plus {@code compliance_restricted_zips} and
 *       {@code compliance_restricted_states} defaulted to empty so rules can always reference them.</li>
 * </ul>
 * An expression that fails to evaluate is treated as a {@code hold} violation with reason {@code RULE_ERROR}
 * rather than silently passing: a broken compliance rule must never let a shipment through (GR-01).
 * Rules are cached for 5 s per tenant, matching the config-store freshness budget.
 */
@Service
public class DefaultComplianceService implements ComplianceService {
    private static final Logger log = LoggerFactory.getLogger(DefaultComplianceService.class);
    private static final Map<String, Object> ATTRIBUTE_DEFAULTS = Map.of(
        "hazmat", false, "age_restricted", false, "export_controlled", false, "lithium_battery", false, "category", "", "min_age", 0L);

    private final ComplianceRuleRepository repo;
    private final TenantConfigStore config;
    private final CelEngine cel;
    private final Metrics metrics;
    private final Cache<String, List<ComplianceRule>> cache = Caffeine.newBuilder().expireAfterWrite(Duration.ofSeconds(5)).maximumSize(1_000).build();

    public DefaultComplianceService(ComplianceRuleRepository repo, TenantConfigStore config, CelEngine cel, Metrics metrics) {
        this.repo = repo;
        this.config = config;
        this.cel = cel;
        this.metrics = metrics;
    }

    @Override
    public List<ComplianceRule> rules() {
        return cache.get(TenantContext.require(), t -> {
            List<ComplianceRule> out = new ArrayList<>(repo.active());
            for (Object o : config.getList(FinanceConfigKeys.COMPLIANCE_RULES)) {
                if (o instanceof Map<?, ?> m) {
                    try {
                        ComplianceRule r = ComplianceRuleLoader.fromMap(m);
                        if (r.active()) out.add(r);
                    } catch (RuntimeException e) {
                        log.warn("Ignoring malformed tenant_config compliance rule {}: {}", m.get("id"), e.getMessage());
                    }
                }
            }
            out.sort((a, b) -> a.priority() != b.priority() ? Integer.compare(a.priority(), b.priority()) : a.id().compareTo(b.id()));
            return List.copyOf(out);
        });
    }

    /** Drops the per-tenant rule cache (after a load / edit). */
    public void invalidate() { cache.invalidateAll(); }

    @Override
    public List<ComplianceViolation> check(ComplianceInput in) {
        return metrics.time("oms.finance.compliance.check", () -> {
            List<ComplianceRule> rules = rules();
            Map<String, Object> order = orderRoot(in);
            Map<String, Object> tenant = tenantRoot();
            List<ComplianceViolation> out = new ArrayList<>();
            List<ComplianceLine> lines = in.lines() == null ? List.of() : in.lines();
            for (ComplianceRule rule : rules) {
                if (rule.orderScoped()) {
                    evaluate(rule, Map.of(), null, order, tenant, in, null).ifPresent(out::add);
                } else {
                    for (ComplianceLine line : lines) {
                        evaluate(rule, lineRoot(line), line.lineId(), order, tenant, in, line).ifPresent(out::add);
                    }
                }
            }
            if (!out.isEmpty()) metrics.count("oms.finance.compliance.violations", out.size());
            return out;
        });
    }

    private java.util.Optional<ComplianceViolation> evaluate(ComplianceRule rule, Map<String, Object> line, String lineId, Map<String, Object> order,
                                                             Map<String, Object> tenant, ComplianceInput in, ComplianceLine cl) {
        Map<String, Object> vars = new HashMap<>();
        vars.put("line", line);
        vars.put("order", order);
        vars.put("tenant", tenant);
        try {
            if (!cel.evalBool(rule.when(), vars)) return java.util.Optional.empty();
            return java.util.Optional.of(new ComplianceViolation(rule.id(), rule.action(), rule.reasonCode(), lineId, render(rule, in, cl), rule.severity()));
        } catch (RuntimeException e) {
            log.error("Compliance rule {} failed to evaluate: {}", rule.id(), e.getMessage());
            metrics.count("oms.finance.compliance.rule_error", "rule", rule.id());
            return java.util.Optional.of(new ComplianceViolation(rule.id(), "hold", "RULE_ERROR", lineId,
                "Compliance rule '" + rule.name() + "' could not be evaluated: " + e.getMessage(), "error"));
        }
    }

    static Map<String, Object> lineRoot(ComplianceLine l) {
        Map<String, Object> m = new LinkedHashMap<>(ATTRIBUTE_DEFAULTS);
        Map<String, Object> attrs = l.attributes() == null ? Map.of() : l.attributes();
        attrs.forEach((k, v) -> { if (v != null) m.put(k, celValue(v)); });
        m.put("line_id", l.lineId() == null ? "" : l.lineId());
        m.put("sku", l.sku() == null ? "" : l.sku());
        m.put("qty", (long) l.qty());
        m.put("attributes", attrs);
        return m;
    }

    /** CEL integers are 64-bit and floats are doubles; JSON-sourced attributes arrive as Integer / Float. */
    static Object celValue(Object v) {
        if (v instanceof Integer || v instanceof Short || v instanceof Byte) return ((Number) v).longValue();
        if (v instanceof Float f) return f.doubleValue();
        if (v instanceof java.math.BigDecimal b) return b.doubleValue();
        if (v instanceof java.math.BigInteger b) return b.longValue();
        if (v instanceof Map<?, ?> mm) {
            Map<String, Object> out = new LinkedHashMap<>();
            mm.forEach((k, val) -> out.put(String.valueOf(k), celValue(val)));
            return out;
        }
        if (v instanceof List<?> l) return l.stream().map(DefaultComplianceService::celValue).toList();
        return v;
    }

    static Map<String, Object> orderRoot(ComplianceInput in) {
        ComplianceDestination d = in.destination();
        Map<String, Object> dest = new LinkedHashMap<>();
        String zip = d == null || d.zip() == null ? "" : d.zip().trim();
        String addressType = d == null || d.addressType() == null ? "" : d.addressType().trim().toLowerCase(Locale.ROOT);
        dest.put("state", d == null || d.state() == null ? "" : d.state().trim().toUpperCase(Locale.ROOT));
        dest.put("zip", zip);
        dest.put("zip5", zip.length() >= 5 ? zip.substring(0, 5) : zip);
        dest.put("country", d == null || d.country() == null ? "" : d.country().trim().toUpperCase(Locale.ROOT));
        dest.put("address_type", addressType);
        dest.put("is_po_box", "po_box".equals(addressType));
        ComplianceCustomer c = in.customer() == null ? ComplianceCustomer.unverified() : in.customer();
        Map<String, Object> customer = new LinkedHashMap<>();
        customer.put("age_verified", c.ageVerified());
        customer.put("age", c.age() == null ? 0L : c.age().longValue());
        String ship = in.shipMethod() == null ? "" : in.shipMethod().trim().toLowerCase(Locale.ROOT);
        Map<String, Object> order = new LinkedHashMap<>();
        order.put("destination", dest);
        order.put("customer", customer);
        order.put("ship_method", ship);
        order.put("ship_method_is_air", ship.contains("air") || ship.contains("overnight") || ship.contains("express") || ship.contains("next_day"));
        order.put("line_count", (long) (in.lines() == null ? 0 : in.lines().size()));
        return order;
    }

    private Map<String, Object> tenantRoot() {
        Map<String, Object> tenant = new LinkedHashMap<>(config.celTenantMap());
        tenant.put("compliance_restricted_zips", config.getList(FinanceConfigKeys.COMPLIANCE_RESTRICTED_ZIPS));
        tenant.put("compliance_restricted_states", config.getMap(FinanceConfigKeys.COMPLIANCE_RESTRICTED_STATES));
        return tenant;
    }

    static String render(ComplianceRule rule, ComplianceInput in, ComplianceLine line) {
        String template = rule.message() == null || rule.message().isBlank() ? rule.name() : rule.message();
        ComplianceDestination d = in.destination();
        return template
            .replace("{sku}", line == null || line.sku() == null ? "" : line.sku())
            .replace("{lineId}", line == null || line.lineId() == null ? "" : line.lineId())
            .replace("{state}", d == null || d.state() == null ? "" : d.state())
            .replace("{zip}", d == null || d.zip() == null ? "" : d.zip())
            .replace("{country}", d == null || d.country() == null ? "" : d.country())
            .replace("{shipMethod}", in.shipMethod() == null ? "" : in.shipMethod());
    }
}
