package ai.intentoms.copilot.api;

import ai.intentoms.copilot.config.PromptTemplates;
import ai.intentoms.copilot.llm.BudgetPolicy;
import ai.intentoms.copilot.llm.CopilotAvailability;
import ai.intentoms.copilot.llm.UsageLedger;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/** {@code GET /copilot/status}: model, availability and today's budget usage (GR-17). */
@Service
public class CopilotStatusService {
    private final CopilotAvailability availability;
    private final BudgetPolicy budget;
    private final UsageLedger ledger;
    private final PromptTemplates templates;

    public CopilotStatusService(CopilotAvailability availability, BudgetPolicy budget, UsageLedger ledger, PromptTemplates templates) {
        this.availability = availability;
        this.budget = budget;
        this.ledger = ledger;
        this.templates = templates;
    }

    public Map<String, Object> status() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("available", availability.available());
        m.put("model", availability.model());
        m.put("baseUrl", availability.baseUrl());
        m.put("budgetUsdPerDay", budget.dailyBudgetUsd().isPresent() ? budget.dailyBudgetUsd().getAsDouble() : null);
        m.put("spentTodayUsd", budget.usedTodayUsd());
        m.put("features", java.util.List.of("config", "bootstrap", "ops", "explain", "cs", "digest"));
        m.put("copilotUsedTodayUsd", ledger.usedTodayUsd());
        m.put("copilotCallsToday", ledger.callsToday());
        m.put("remainingUsd", budget.remainingUsd().isPresent() ? budget.remainingUsd().getAsDouble() : null);
        m.put("budgetExceeded", budget.remainingUsd().isPresent() && budget.remainingUsd().getAsDouble() <= 0);
        m.put("promptTemplates", templates.source());
        return m;
    }
}
