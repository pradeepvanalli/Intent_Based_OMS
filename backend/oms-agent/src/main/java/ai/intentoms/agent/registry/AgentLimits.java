package ai.intentoms.agent.registry;

import ai.intentoms.finance.model.Money;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Per-agent limits (FR-AGT-04): the tools the agent may call, the largest order it may place, the largest refund it may
 * trigger, its request rate and the tools that need a human confirmation before they take effect. Serialises with both the
 * numeric USD fields and the UI's {@code maxOrderValue}/{@code maxRefund} {@code Money} shape; accepts either on input.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AgentLimits(List<String> allowedActions, BigDecimal maxOrderValueUsd, BigDecimal maxRefundUsd, Integer ratePerMinute, List<String> confirmActions) {

    public AgentLimits {
        allowedActions = allowedActions == null ? List.of() : allowedActions.stream().filter(a -> a != null && !a.isBlank()).map(a -> a.trim().toLowerCase(Locale.ROOT)).distinct().toList();
        confirmActions = confirmActions == null ? List.of() : confirmActions.stream().filter(a -> a != null && !a.isBlank()).map(a -> a.trim().toLowerCase(Locale.ROOT)).distinct().toList();
        if (ratePerMinute != null && ratePerMinute <= 0) ratePerMinute = null;
    }

    @JsonCreator
    public static AgentLimits fromJson(@JsonProperty("allowedActions") List<String> allowedActions,
                                       @JsonProperty("maxOrderValueUsd") BigDecimal maxOrderValueUsd,
                                       @JsonProperty("maxOrderValue") Map<String, Object> maxOrderValue,
                                       @JsonProperty("maxRefundUsd") BigDecimal maxRefundUsd,
                                       @JsonProperty("maxRefund") Map<String, Object> maxRefund,
                                       @JsonProperty("ratePerMinute") Integer ratePerMinute,
                                       @JsonProperty("confirmActions") List<String> confirmActions) {
        return new AgentLimits(allowedActions, maxOrderValueUsd != null ? maxOrderValueUsd : amount(maxOrderValue),
            maxRefundUsd != null ? maxRefundUsd : amount(maxRefund), ratePerMinute, confirmActions);
    }

    private static BigDecimal amount(Map<String, Object> money) {
        if (money == null) return null;
        Object a = money.get("amount");
        if (a instanceof Number n) return new BigDecimal(n.toString());
        if (a instanceof String s && !s.isBlank()) return new BigDecimal(s.trim());
        return null;
    }

    @JsonProperty("maxOrderValue")
    public Money maxOrderValue() { return maxOrderValueUsd == null ? null : Money.of(maxOrderValueUsd, "USD"); }

    @JsonProperty("maxRefund")
    public Money maxRefund() { return maxRefundUsd == null ? null : Money.of(maxRefundUsd, "USD"); }

    public boolean allows(String tool) { return tool != null && allowedActions.contains(tool.toLowerCase(Locale.ROOT)); }

    public boolean requiresConfirmation(String tool) { return tool != null && confirmActions.contains(tool.toLowerCase(Locale.ROOT)); }

    /** True when {@code total} is above the agent's maximum order value (a null or zero limit means "no orders at all" only when order_create is allowed). */
    public boolean exceedsOrderValue(Money total) {
        if (total == null || maxOrderValueUsd == null) return false;
        return "USD".equals(total.currency()) && total.amount().compareTo(maxOrderValueUsd) > 0;
    }

    public AgentLimits merge(AgentLimits patch) {
        if (patch == null) return this;
        return new AgentLimits(patch.allowedActions().isEmpty() ? allowedActions : patch.allowedActions(),
            patch.maxOrderValueUsd() != null ? patch.maxOrderValueUsd() : maxOrderValueUsd,
            patch.maxRefundUsd() != null ? patch.maxRefundUsd() : maxRefundUsd,
            patch.ratePerMinute() != null ? patch.ratePerMinute() : ratePerMinute,
            patch.confirmActions() == null ? confirmActions : patch.confirmActions());
    }
}
