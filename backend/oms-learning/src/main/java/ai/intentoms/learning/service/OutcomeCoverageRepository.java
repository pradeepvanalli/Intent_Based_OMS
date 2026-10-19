package ai.intentoms.learning.service;

import ai.intentoms.platform.tenant.TenantContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.TreeMap;

/** OC-07: how many of a decision's mature episodes have an outcome of each type. */
@Repository
public class OutcomeCoverageRepository {
    private final JdbcClient jdbc;

    public OutcomeCoverageRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    /** Episodes created in [from, to) and, per outcome type, how many of them have at least one outcome of that type. */
    public record ByType(long eligible, Map<String, Long> covered) {
        public double share(String type) { return eligible == 0 ? 0 : covered.getOrDefault(type, 0L) / (double) eligible; }
    }

    public ByType byType(String decision, Instant from, Instant to) {
        String t = TenantContext.require();
        long eligible = jdbc.sql("select count(*) from episode where tenant_id = :t and decision_name = :d and created_at >= :f and created_at < :to")
            .param("t", t).param("d", decision).param("f", Timestamp.from(from)).param("to", Timestamp.from(to)).query(Long.class).single();
        Map<String, Long> covered = new TreeMap<>();
        jdbc.sql("""
            select o.type, count(distinct e.episode_id) as n from episode e join outcome o on o.tenant_id = e.tenant_id and o.episode_id = e.episode_id
            where e.tenant_id = :t and e.decision_name = :d and e.created_at >= :f and e.created_at < :to
            group by o.type
            """).param("t", t).param("d", decision).param("f", Timestamp.from(from)).param("to", Timestamp.from(to))
            .query().listOfRows().forEach(r -> covered.put(String.valueOf(r.get("type")), ((Number) r.get("n")).longValue()));
        return new ByType(eligible, covered);
    }
}
