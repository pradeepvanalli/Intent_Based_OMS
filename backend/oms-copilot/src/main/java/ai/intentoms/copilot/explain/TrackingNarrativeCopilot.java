package ai.intentoms.copilot.explain;

import ai.intentoms.copilot.llm.CopilotAvailability;
import ai.intentoms.order.model.OrderView;
import ai.intentoms.order.tracking.TrackingNarrativeProvider;
import ai.intentoms.platform.error.OmsException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Adds the model-written, customer-safe explanation of the order's fulfilment-intent decision to the public tracking
 * narrative (FR-XAI-04). Only text that passed {@link ExplainService}'s grounding check is used ({@code generated=true});
 * without a model key, or when the explanation fell back to its template, nothing is added and the deterministic
 * tracking template stands alone. The customer audience never sees margins, costs, risk or node ids.
 */
@Component
public class TrackingNarrativeCopilot implements TrackingNarrativeProvider {
    private static final Logger log = LoggerFactory.getLogger(TrackingNarrativeCopilot.class);
    private final ExplainService explain;
    private final CopilotAvailability availability;

    public TrackingNarrativeCopilot(ExplainService explain, CopilotAvailability availability) {
        this.explain = explain;
        this.availability = availability;
    }

    @Override
    public Optional<String> narrative(OrderView view) {
        if (!availability.available() || view.order().intent() == null || view.order().intent().decisionRecordId() == null) return Optional.empty();
        try {
            Explanation e = explain.explain(view.order().intent().decisionRecordId(), "customer");
            return e.generated() && e.text() != null && !e.text().isBlank() ? Optional.of(e.text().trim()) : Optional.empty();
        } catch (OmsException e) {
            log.debug("tracking narrative skipped for {}: {}", view.order().id(), e.getMessage());
            return Optional.empty();
        }
    }
}
