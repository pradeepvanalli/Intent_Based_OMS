package ai.intentoms.order.model;

import java.util.List;
import java.util.Map;

/**
 * A configurable order type (FR-CAP-03): binds a header and line state model, the workflow that drives the order and
 * the decision set the workflow may invoke. {@code channelDefaults} gives the fulfilment policy used when intent is
 * unclear (FR-CAP-06); {@code sourcing} carries type-specific sourcing constraints read by the sourcing module
 * (nodeTypes, capabilities, maxDistanceKm, allowBackorder, includeInbound).
 */
public record OrderType(
    String name,
    String description,
    String headerStateModel,
    String lineStateModel,
    String workflowType,
    List<String> decisions,
    Map<String, String> channelDefaults,
    Integer returnWindowDays,
    Map<String, Object> sourcing
) {
    public OrderType {
        decisions = decisions == null ? List.of() : List.copyOf(decisions);
        channelDefaults = channelDefaults == null ? Map.of() : Map.copyOf(channelDefaults);
        sourcing = sourcing == null ? Map.of() : Map.copyOf(sourcing);
    }

    /** Channel default policy, or null when the type has none for the channel. */
    public String channelDefault(String channel) { return channel == null ? null : channelDefaults.get(channel); }

    public boolean usesDecision(String decisionName) { return decisions.contains(decisionName); }
}
