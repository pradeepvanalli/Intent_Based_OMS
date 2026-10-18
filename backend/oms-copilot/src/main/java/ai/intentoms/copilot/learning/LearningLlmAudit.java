package ai.intentoms.copilot.learning;

import ai.intentoms.copilot.llm.CopilotLlmClient;
import ai.intentoms.copilot.llm.JsonSchema;
import ai.intentoms.platform.config.TenantConfigStore;
import ai.intentoms.platform.ids.Ids;
import ai.intentoms.platform.json.Json;
import ai.intentoms.platform.observability.Metrics;
import ai.intentoms.platform.tenant.TenantContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.sql.Timestamp;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;

/**
 * NF-03 and §6.3 for the analyst, author and critic roles.
 * <ul>
 *   <li><b>Budget:</b> a per-tenant monthly cap on self-optimizing LLM spend ({@code learning.llm_budget_usd_per_month};
 *       unset means no cap). Spend is what the copilot client already meters into {@code copilot_usage} for the
 *       {@code learning_*} features this month. Once it is reached the roles skip their call and say so.</li>
 *   <li><b>Audit:</b> every call is written to {@code learning_llm_call} with the SHA-256 of the exact output schema and
 *       prompt template it ran under, the model, the outcome and what it produced, so any hypothesis or drafted change
 *       traces back to the contract that generated it.</li>
 *   <li><b>Author quality:</b> the share of author drafts that the critic called safe and that replay evaluation found
 *       an improvement.</li>
 * </ul>
 */
@Component
public class LearningLlmAudit {
    public static final String BUDGET_KEY = "learning.llm_budget_usd_per_month";

    private final JdbcClient jdbc;
    private final TenantConfigStore config;
    private final CopilotLlmClient llm;
    private final Metrics metrics;

    public LearningLlmAudit(JdbcClient jdbc, TenantConfigStore config, CopilotLlmClient llm, Metrics metrics) {
        this.jdbc = jdbc;
        this.config = config;
        this.llm = llm;
        this.metrics = metrics;
    }

    public OptionalDouble monthlyBudgetUsd() {
        double b = config.getDouble(BUDGET_KEY, -1);
        return b < 0 ? OptionalDouble.empty() : OptionalDouble.of(b);
    }

    public double monthSpendUsd() {
        return jdbc.sql("select coalesce(sum(cost_usd), 0) from copilot_usage where tenant_id = :t and feature like 'learning\\_%' and at >= date_trunc('month', now())")
            .param("t", TenantContext.require()).query(Double.class).single();
    }

    /** False, with the skipped call recorded, when this month's learning LLM spend has reached the tenant's budget. */
    public boolean withinBudget(String role, String feature, String refType, String refId, JsonSchema schema, String promptName) {
        OptionalDouble budget = monthlyBudgetUsd();
        if (budget.isEmpty() || monthSpendUsd() < budget.getAsDouble()) return true;
        record(role, feature, refType, refId, schema, promptName, null, "skipped_budget", null);
        metrics.count("oms.learning.llm.budget_exhausted", "role", role);
        return false;
    }

    public String record(String role, String feature, String refType, String refId, JsonSchema schema, String promptName, String promptText, String outcome, String producedId) {
        String id = Ids.prefixed("llm");
        jdbc.sql("""
            insert into learning_llm_call(call_id, tenant_id, role, feature, ref_type, ref_id, schema_name, schema_hash, prompt_name, prompt_hash, model, outcome, produced_id)
            values (:id, :t, :role, :feature, :rt, :rid, :sn, :sh, :pn, :ph, :model, :outcome, :produced)
            """).param("id", id).param("t", TenantContext.require()).param("role", role).param("feature", feature).param("rt", refType).param("rid", refId)
            .param("sn", schema.name()).param("sh", schemaHash(schema)).param("pn", promptName).param("ph", promptText == null ? null : Json.sha256(promptText))
            .param("model", llm.model()).param("outcome", outcome).param("produced", producedId).update();
        metrics.count("oms.learning.llm.call", "role", role, "outcome", outcome);
        return id;
    }

    /** SHA-256 over the schema's name and canonical JSON: changes whenever the output contract changes. */
    public static String schemaHash(JsonSchema schema) {
        return Json.sha256(Map.of("name", schema.name(), "schema", schema.schema()));
    }

    public Map<String, Object> budgetStatus() {
        Map<String, Object> m = new LinkedHashMap<>();
        OptionalDouble b = monthlyBudgetUsd();
        double spent = monthSpendUsd();
        m.put("monthlyBudgetUsd", b.isPresent() ? b.getAsDouble() : null);
        m.put("spentThisMonthUsd", Math.round(spent * 10_000d) / 10_000d);
        m.put("remainingUsd", b.isPresent() ? Math.round((b.getAsDouble() - spent) * 10_000d) / 10_000d : null);
        m.put("exhausted", b.isPresent() && spent >= b.getAsDouble());
        return m;
    }

    public List<Map<String, Object>> recentCalls(int limit) {
        return jdbc.sql("""
            select call_id, role, feature, ref_type, ref_id, schema_name, schema_hash, prompt_name, prompt_hash, model, outcome, produced_id, created_at
            from learning_llm_call where tenant_id = :t order by created_at desc limit :lim
            """).param("t", TenantContext.require()).param("lim", limit).query().listOfRows();
    }

    /** §6.3 author quality: of the author's drafts in the window, how many the critic called safe and replay found better. */
    public Map<String, Object> authorQuality(int days) {
        var r = jdbc.sql("""
            select count(*) as drafted,
                   count(*) filter (where p.critic_review is not null) as reviewed,
                   count(*) filter (where p.critic_review->>'verdict' = 'LOOKS_SAFE') as critic_passed,
                   count(*) filter (where jsonb_exists(p.evaluation_report, 'liftEstimate')) as evaluated,
                   count(*) filter (where coalesce((p.evaluation_report->'confidenceInterval'->>'lower')::double precision, (p.evaluation_report->>'liftEstimate')::double precision, 0) > 0) as evaluation_passed,
                   count(*) filter (where p.critic_review->>'verdict' = 'LOOKS_SAFE'
                                    and coalesce((p.evaluation_report->'confidenceInterval'->>'lower')::double precision, (p.evaluation_report->>'liftEstimate')::double precision, 0) > 0) as both_passed
            from learning_llm_call c join proposal p on p.tenant_id = c.tenant_id and p.proposal_id = c.produced_id
            where c.tenant_id = :t and c.role = 'author' and c.outcome = 'ok' and c.created_at >= :since
            """).param("t", TenantContext.require()).param("since", Timestamp.from(Instant.now().minus(Duration.ofDays(Math.max(1, days))))).query().singleRow();
        Map<String, Object> m = new LinkedHashMap<>();
        long drafted = ((Number) r.get("drafted")).longValue();
        r.forEach((k, v) -> m.put(toCamel(k), ((Number) v).longValue()));
        m.put("windowDays", days);
        m.put("qualityRate", drafted == 0 ? null : Math.round(((Number) r.get("both_passed")).doubleValue() / drafted * 10_000d) / 10_000d);
        return m;
    }

    private static String toCamel(String snake) {
        StringBuilder b = new StringBuilder();
        boolean up = false;
        for (char ch : snake.toCharArray()) {
            if (ch == '_') { up = true; continue; }
            b.append(up ? Character.toUpperCase(ch) : ch);
            up = false;
        }
        return b.toString();
    }
}
