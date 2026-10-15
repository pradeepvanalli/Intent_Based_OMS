package ai.intentoms.copilot.llm;

import ai.intentoms.platform.tenant.TenantContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** {@code copilot_usage} rows: tenant, feature, model, tokens, cost, latency (GR-17). */
@Repository
public class JdbcUsageLedger implements UsageLedger {
    private final JdbcClient jdbc;

    public JdbcUsageLedger(JdbcClient jdbc) { this.jdbc = jdbc; }

    @Override
    public void record(LlmUsage u) {
        jdbc.sql("insert into copilot_usage(tenant_id, feature, model, input_tokens, output_tokens, cost_usd, latency_ms, status) values (:t, :f, :m, :i, :o, :c, :l, :s)")
            .param("t", TenantContext.require()).param("f", u.feature()).param("m", u.model()).param("i", u.inputTokens()).param("o", u.outputTokens())
            .param("c", u.costUsd()).param("l", u.latencyMs()).param("s", u.status()).update();
    }

    @Override
    public double usedTodayUsd() {
        return jdbc.sql("select coalesce(sum(cost_usd), 0) from copilot_usage where tenant_id = :t and at >= date_trunc('day', now())")
            .param("t", TenantContext.require()).query(Double.class).single();
    }

    @Override
    public long callsToday() {
        return jdbc.sql("select count(*) from copilot_usage where tenant_id = :t and at >= date_trunc('day', now())")
            .param("t", TenantContext.require()).query(Long.class).single();
    }
}
