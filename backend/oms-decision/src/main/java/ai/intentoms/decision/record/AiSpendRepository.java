package ai.intentoms.decision.record;

import ai.intentoms.platform.tenant.TenantContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** Daily AI spend per decision and provider (GR-17 budgets; AI cost per order KPI). */
@Repository
public class AiSpendRepository {
    private final JdbcClient jdbc;

    public AiSpendRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    public void add(LocalDate day, String decision, String provider, int calls, double costUsd, int inputTokens) {
        jdbc.sql("insert into ai_spend(tenant_id, day, decision, provider, calls, cost_usd, input_tokens) values (:t, :d, :dec, :p, :c, :cost, :tok) " +
                "on conflict (tenant_id, day, decision, provider) do update set calls = ai_spend.calls + excluded.calls, cost_usd = ai_spend.cost_usd + excluded.cost_usd, input_tokens = ai_spend.input_tokens + excluded.input_tokens")
            .param("t", TenantContext.require()).param("d", day).param("dec", decision).param("p", provider).param("c", calls).param("cost", costUsd).param("tok", inputTokens).update();
    }

    public double totalForDay(LocalDate day) {
        return jdbc.sql("select coalesce(sum(cost_usd), 0) from ai_spend where tenant_id = :t and day = :d").param("t", TenantContext.require()).param("d", day).query(Double.class).single();
    }

    public List<Map<String, Object>> byDecision(LocalDate from, LocalDate to) {
        return jdbc.sql("select decision, provider, sum(calls) as calls, sum(cost_usd) as cost_usd, sum(input_tokens) as input_tokens from ai_spend where tenant_id = :t and day between :f and :to group by decision, provider order by cost_usd desc")
            .param("t", TenantContext.require()).param("f", from).param("to", to).query().listOfRows();
    }
}
