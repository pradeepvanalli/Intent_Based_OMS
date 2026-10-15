package ai.intentoms.sourcing.candidate;

import ai.intentoms.platform.tenant.TenantContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * Units already released to each node today (sum of fulfilment-group allocations created since the start of the day,
 * excluding rejected/cancelled groups). Subtracted from {@code dailyCapacity} in candidate generation (FR-SRC-01).
 */
@Repository
public class NodeLoadRepository {
    private final JdbcClient jdbc;

    public NodeLoadRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    public Map<String, Integer> unitsReleasedSince(Instant since) {
        var rows = jdbc.sql("""
            select g.node_id, coalesce(sum((a->>'qty')::int), 0) as units
            from fulfilment_group g, jsonb_array_elements(g.allocations) a
            where g.tenant_id = :t and g.created_at >= :since and g.status not in ('rejected', 'cancelled') and g.node_id is not null
            group by g.node_id
            """).param("t", TenantContext.require()).param("since", Timestamp.from(since)).query().listOfRows();
        Map<String, Integer> out = new HashMap<>();
        for (var r : rows) out.put((String) r.get("node_id"), ((Number) r.get("units")).intValue());
        return out;
    }
}
