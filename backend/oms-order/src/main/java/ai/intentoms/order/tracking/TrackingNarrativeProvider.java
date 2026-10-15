package ai.intentoms.order.tracking;

import ai.intentoms.order.model.OrderView;

import java.util.Optional;

/**
 * SPI through which the copilot module contributes a model-written, customer-safe paragraph to the public tracking
 * narrative when an LLM is configured. The default (registered by {@code OrderAutoConfiguration}) contributes nothing,
 * so the deterministic template in {@link TrackingService} is the whole narrative.
 */
public interface TrackingNarrativeProvider {
    /** A customer-safe paragraph for this order, or empty when no model is available or the text failed grounding. */
    Optional<String> narrative(OrderView view);

    static TrackingNarrativeProvider none() { return view -> Optional.empty(); }
}
