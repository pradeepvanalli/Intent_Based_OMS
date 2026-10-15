package ai.intentoms.copilot.llm;

import java.util.OptionalDouble;

/** Daily AI budget of the current tenant (GR-17). Empty means no limit configured. */
public interface BudgetPolicy {
    OptionalDouble dailyBudgetUsd();

    /** Total AI spend today across decisions and copilot calls. */
    double usedTodayUsd();

    default OptionalDouble remainingUsd() {
        OptionalDouble b = dailyBudgetUsd();
        return b.isEmpty() ? OptionalDouble.empty() : OptionalDouble.of(b.getAsDouble() - usedTodayUsd());
    }
}
