package ai.intentoms.copilot.llm;

import ai.intentoms.decision.record.AiSpendRepository;
import ai.intentoms.platform.config.ConfigKeys;
import ai.intentoms.platform.config.TenantConfigStore;
import ai.intentoms.platform.time.TimeSource;
import org.springframework.stereotype.Component;

import java.time.ZoneOffset;
import java.util.OptionalDouble;

/**
 * Reads {@code ai.budget_usd_per_day} from the tenant configuration and adds the decision fabric's spend
 * ({@code ai_spend}) to the copilot's own ledger so both share one daily budget (GR-17).
 */
@Component
public class TenantBudgetPolicy implements BudgetPolicy {
    private final TenantConfigStore config;
    private final AiSpendRepository decisionSpend;
    private final UsageLedger ledger;
    private final TimeSource time;

    public TenantBudgetPolicy(TenantConfigStore config, AiSpendRepository decisionSpend, UsageLedger ledger, TimeSource time) {
        this.config = config;
        this.decisionSpend = decisionSpend;
        this.ledger = ledger;
        this.time = time;
    }

    @Override
    public OptionalDouble dailyBudgetUsd() {
        double b = config.getDouble(ConfigKeys.AI_BUDGET_USD_PER_DAY, -1);
        return b < 0 ? OptionalDouble.empty() : OptionalDouble.of(b);
    }

    @Override
    public double usedTodayUsd() {
        return decisionSpend.totalForDay(time.now().atZone(ZoneOffset.UTC).toLocalDate()) + ledger.usedTodayUsd();
    }
}
