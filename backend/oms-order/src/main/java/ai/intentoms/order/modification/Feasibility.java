package ai.intentoms.order.modification;

import ai.intentoms.finance.model.Money;

import java.time.LocalDate;
import java.util.List;

/**
 * Result of {@code OrderModificationService.check} (FR-CS-02): whether the change is possible given the current line
 * quantity statuses, why not, what it costs the customer ({@code costDelta}: negative = refund), whether an approval is
 * needed, whether a carrier intercept would be attempted, the lines affected and (when sourcing knows) the new promise.
 */
public record Feasibility(boolean allowed, List<String> reasons, Money costDelta, boolean requiresApproval, Boolean carrierIntercept, NewPromise newPromise, List<String> affectedLines) {
    public record NewPromise(LocalDate date, double onTimeProbability) {}

    public Feasibility {
        reasons = reasons == null ? List.of() : List.copyOf(reasons);
        affectedLines = affectedLines == null ? List.of() : List.copyOf(affectedLines);
    }

    public static Feasibility denied(String reason, List<String> affected) { return new Feasibility(false, List.of(reason), null, false, null, null, affected); }
}
