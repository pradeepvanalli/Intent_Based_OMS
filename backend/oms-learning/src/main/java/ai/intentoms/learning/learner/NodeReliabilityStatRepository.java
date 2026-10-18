package ai.intentoms.learning.learner;

import ai.intentoms.platform.tenant.TenantContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/** Beta-binomial sufficient statistics per node x sku_class x day_part (FS-02). Incremental: one row update per
 * observed delivery/reject, no full-history replay needed to get today's posterior mean. */
@Repository
public class NodeReliabilityStatRepository {
    private final JdbcClient jdbc;
    public NodeReliabilityStatRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    @Transactional
    public void recordOnTime(String nodeId, String skuClass, String dayPart, boolean onTime) {
        upsert(nodeId, skuClass, dayPart);
        String col = onTime ? "on_time_alpha" : "on_time_beta";
        jdbc.sql("update node_reliability_stat set " + col + " = " + col + " + 1, sample_size = sample_size + 1, updated_at = now() " +
            "where tenant_id = :t and node_id = :n and sku_class = :sc and day_part = :dp")
            .param("t", TenantContext.require()).param("n", nodeId).param("sc", skuClass).param("dp", dayPart).update();
    }

    @Transactional
    public void recordReject(String nodeId, String skuClass, String dayPart, boolean rejected) {
        upsert(nodeId, skuClass, dayPart);
        String col = rejected ? "reject_alpha" : "reject_beta";
        jdbc.sql("update node_reliability_stat set " + col + " = " + col + " + 1, updated_at = now() where tenant_id = :t and node_id = :n and sku_class = :sc and day_part = :dp")
            .param("t", TenantContext.require()).param("n", nodeId).param("sc", skuClass).param("dp", dayPart).update();
    }

    private void upsert(String nodeId, String skuClass, String dayPart) {
        jdbc.sql("insert into node_reliability_stat(tenant_id, node_id, sku_class, day_part) values (:t, :n, :sc, :dp) on conflict (tenant_id, node_id, sku_class, day_part) do nothing")
            .param("t", TenantContext.require()).param("n", nodeId).param("sc", skuClass).param("dp", dayPart).update();
    }

    public record Posterior(double onTimeMean, double rejectMean, int sampleSize) {}

    public Posterior posterior(String nodeId, String skuClass, String dayPart) {
        String t = TenantContext.require();
        var row = jdbc.sql("select on_time_alpha, on_time_beta, reject_alpha, reject_beta, sample_size from node_reliability_stat where tenant_id = :t and node_id = :n and sku_class = :sc and day_part = :dp")
            .param("t", t).param("n", nodeId).param("sc", skuClass).param("dp", dayPart).query().listOfRows().stream().findFirst();
        if (row.isEmpty()) return new Posterior(0.9, 0.05, 0); // Beta(1,1) prior mean is 0.5; start optimistic instead so a brand-new node isn't shunned
        Map<String, Object> r = row.get();
        double onA = (Double) r.get("on_time_alpha"), onB = (Double) r.get("on_time_beta"), rjA = (Double) r.get("reject_alpha"), rjB = (Double) r.get("reject_beta");
        return new Posterior(onA / (onA + onB), rjA / (rjA + rjB), ((Number) r.get("sample_size")).intValue());
    }

    public List<String> nodesWithStats() {
        String t = TenantContext.require();
        return jdbc.sql("select distinct node_id from node_reliability_stat where tenant_id = :t").param("t", t).query(String.class).list();
    }
}
