package ai.intentoms.monitoring.playbook;

import ai.intentoms.monitoring.exception.ExceptionRecord;
import ai.intentoms.order.model.FulfilmentGroup;
import ai.intentoms.order.model.Order;
import ai.intentoms.platform.config.TenantConfigStore;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Builds the {@code sla_breach_risk} input shape ({@code order{state, hours_in_state, promise_hours_remaining, ship_method},
 * node{...}, carrier{on_time_rate_7d}}) from an order for playbook gates that declare {@code requiresDecision: sla_breach_risk}
 * (FR-MON-04, e.g. the {@code repromise} playbook). Node signals are zeros here; the sweep scorer has the richer view.
 */
final class BreachRiskInput {
    private static final String DECISION = "sla_breach_risk";

    private BreachRiskInput() {}

    static boolean matches(String decisionName) { return DECISION.equals(decisionName); }

    static Map<String, Object> build(Optional<Order> order, ExceptionRecord exc, Instant now, TenantConfigStore config) {
        Map<String, Object> o = new LinkedHashMap<>();
        if (order.isPresent()) {
            Order ord = order.get();
            o.put("state", ord.status());
            Instant since = ord.updatedAt() == null ? ord.placedAt() : ord.updatedAt();
            o.put("hours_in_state", since == null ? 0.0 : Math.round(Math.max(0, Duration.between(since, now).toMinutes()) / 6.0) / 10.0);
            ord.fulfilmentGroups().stream().map(FulfilmentGroup::promiseDate).filter(d -> d != null).min(LocalDate::compareTo)
                .ifPresent(d -> o.put("promise_hours_remaining", Math.round(Duration.between(now, d.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant()).toMinutes() / 6.0) / 10.0));
            o.put("ship_method", ord.shipMethod() == null ? "standard" : ord.shipMethod());
        } else {
            o.put("state", exc.details().getOrDefault("state", "unknown"));
            o.put("hours_in_state", 0.0);
            o.put("ship_method", "standard");
        }
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("backlog_units", 0);
        node.put("reject_rate", 0.0);
        node.put("minutes_to_cutoff", 0);
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("order", o);
        input.put("node", node);
        input.put("carrier", Map.of("on_time_rate_7d", config.getDouble("carrier.on_time_rate_7d", 0.94)));
        return input;
    }
}
