package ai.intentoms.learning.learner;

import ai.intentoms.platform.tenant.TenantContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/** Beta-binomial sufficient statistics per node x sku_class x day_part (FS-02). Incremental: one row update per
 * observed pick/delivery/reject, no full-history replay needed to get today's posterior mean. With a half-life, every
 * update first fades the evidence already held by 0.5^(days since the last update / half-life), so the posterior
 * follows recent behaviour and a node recovers from an old run of rejects (FS-06). */
@Repository
public class NodeReliabilityStatRepository {
    private final JdbcClient jdbc;
    public NodeReliabilityStatRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    @Transactional
    public void recordOnTime(String nodeId, String skuClass, String dayPart, boolean onTime) { recordOnTime(nodeId, skuClass, dayPart, onTime, 0); }

    @Transactional
    public void recordOnTime(String nodeId, String skuClass, String dayPart, boolean onTime, double halfLifeDays) {
        upsert(nodeId, skuClass, dayPart);
        decay(nodeId, skuClass, dayPart, halfLifeDays);
        String col = onTime ? "on_time_alpha" : "on_time_beta";
        jdbc.sql("update node_reliability_stat set " + col + " = " + col + " + 1, sample_size = sample_size + 1, updated_at = now() " +
            "where tenant_id = :t and node_id = :n and sku_class = :sc and day_part = :dp")
            .param("t", TenantContext.require()).param("n", nodeId).param("sc", skuClass).param("dp", dayPart).update();
    }

    @Transactional
    public void recordReject(String nodeId, String skuClass, String dayPart, boolean rejected) { recordReject(nodeId, skuClass, dayPart, rejected, 0); }

    @Transactional
    public void recordReject(String nodeId, String skuClass, String dayPart, boolean rejected, double halfLifeDays) {
        upsert(nodeId, skuClass, dayPart);
        decay(nodeId, skuClass, dayPart, halfLifeDays);
        String col = rejected ? "reject_alpha" : "reject_beta";
        jdbc.sql("update node_reliability_stat set " + col + " = " + col + " + 1, updated_at = now() where tenant_id = :t and node_id = :n and sku_class = :sc and day_part = :dp")
            .param("t", TenantContext.require()).param("n", nodeId).param("sc", skuClass).param("dp", dayPart).update();
    }

    /** Fades the evidence (counts above the Beta(1,1) prior) by the time since the row was last updated; 0 = no decay. */
    private void decay(String nodeId, String skuClass, String dayPart, double halfLifeDays) {
        if (halfLifeDays <= 0) return;
        jdbc.sql("""
            update node_reliability_stat set
              on_time_alpha = 1 + (on_time_alpha - 1) * f.k, on_time_beta = 1 + (on_time_beta - 1) * f.k,
              reject_alpha = 1 + (reject_alpha - 1) * f.k, reject_beta = 1 + (reject_beta - 1) * f.k
            from (select power(0.5, greatest(0, extract(epoch from (now() - updated_at))) / 86400.0 / :hl) as k
                  from node_reliability_stat where tenant_id = :t and node_id = :n and sku_class = :sc and day_part = :dp) f
            where tenant_id = :t and node_id = :n and sku_class = :sc and day_part = :dp and f.k < 0.999999
            """).param("hl", halfLifeDays).param("t", TenantContext.require()).param("n", nodeId).param("sc", skuClass).param("dp", dayPart).update();
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

    /**
     * Posterior pooled over rows: every day part and SKU class of the node when both filters are null, one SKU class
     * across its day parts when only {@code skuClass} is set. Each row carries a Beta(1,1) prior, removed before pooling.
     */
    public Posterior pooled(String nodeId, String skuClass) {
        var row = jdbc.sql("""
            select coalesce(sum(on_time_alpha - 1), 0) as oa, coalesce(sum(on_time_beta - 1), 0) as ob, coalesce(sum(reject_alpha - 1), 0) as ra,
                   coalesce(sum(reject_beta - 1), 0) as rb, coalesce(sum(sample_size), 0) as n
            from node_reliability_stat where tenant_id = :t and node_id = :n and (cast(:sc as text) is null or sku_class = :sc)
            """).param("t", TenantContext.require()).param("n", nodeId).param("sc", skuClass).query().singleRow();
        double oa = num(row.get("oa")) + 1, ob = num(row.get("ob")) + 1, ra = num(row.get("ra")) + 1, rb = num(row.get("rb")) + 1;
        int n = (int) num(row.get("n"));
        if (n == 0 && oa + ob == 2) return new Posterior(0.9, 0.05, 0);
        return new Posterior(oa / (oa + ob), ra / (ra + rb), n);
    }

    /** FS-06: the fleet-wide reject rate, the yardstick a node's own reject rate is compared with. */
    public double fleetRejectMean() {
        var row = jdbc.sql("select coalesce(sum(reject_alpha - 1), 0) as ra, coalesce(sum(reject_beta - 1), 0) as rb from node_reliability_stat where tenant_id = :t")
            .param("t", TenantContext.require()).query().singleRow();
        double ra = num(row.get("ra")) + 1, rb = num(row.get("rb")) + 1;
        return ra / (ra + rb);
    }

    private static double num(Object o) { return o instanceof Number n ? n.doubleValue() : 0.0; }

    public List<String> nodesWithStats() {
        String t = TenantContext.require();
        return jdbc.sql("select distinct node_id from node_reliability_stat where tenant_id = :t").param("t", t).query(String.class).list();
    }
}
