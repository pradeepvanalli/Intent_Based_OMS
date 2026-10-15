package ai.intentoms.sourcing.plan;

import ai.intentoms.platform.jdbc.Jsonb;
import ai.intentoms.platform.json.Json;
import ai.intentoms.platform.tenant.TenantContext;
import ai.intentoms.sourcing.model.SourcingPlan;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Every plan considered for an order, chosen or not ({@code sourcing_plan}), for replay and analytics (FR-SIM-01, FR-ANL-01). */
@Repository
public class SourcingPlanRepository {
    public record Row(String id, String orderId, String policy, double score, boolean chosen, Instant createdAt, Map<String, Object> plan) {}

    private final JdbcClient jdbc;

    public SourcingPlanRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    public void saveAll(String orderId, List<SourcingPlan> plans, String chosenId, Instant at) {
        String t = TenantContext.require();
        for (SourcingPlan p : plans) {
            jdbc.sql("insert into sourcing_plan(id, tenant_id, order_id, policy, plan, score, chosen, created_at) values (:id, :t, :o, :p, :plan, :s, :c, :at) on conflict (id) do nothing")
                .param("id", p.id()).param("t", t).param("o", orderId).param("p", p.policy()).param("plan", Jsonb.of(p)).param("s", p.score())
                .param("c", p.id().equals(chosenId)).param("at", Timestamp.from(at)).update();
        }
    }

    public List<Row> forOrder(String orderId) {
        return jdbc.sql("select id, order_id, policy, score, chosen, created_at, plan::text as plan from sourcing_plan where tenant_id = :t and order_id = :o order by created_at desc, score desc")
            .param("t", TenantContext.require()).param("o", orderId).query().listOfRows().stream()
            .map(r -> new Row((String) r.get("id"), (String) r.get("order_id"), (String) r.get("policy"), ((Number) r.get("score")).doubleValue(), Boolean.TRUE.equals(r.get("chosen")),
                ((Timestamp) r.get("created_at")).toInstant(), Json.readMap((String) r.get("plan")))).toList();
    }
}
