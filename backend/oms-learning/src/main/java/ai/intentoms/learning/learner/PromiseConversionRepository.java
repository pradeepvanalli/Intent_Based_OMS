package ai.intentoms.learning.learner;

import ai.intentoms.platform.tenant.TenantContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/** Table {@code promise_conversion} (CX-02): storefront promise impressions and whether the shopper bought. */
@Repository
public class PromiseConversionRepository {
    private final JdbcClient jdbc;
    public PromiseConversionRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    /** Idempotent on the impression id: a retried post changes nothing. Returns true when stored. */
    public boolean record(String impressionId, String segment, String orderId, int promisedDays, int bufferDays, boolean converted, Instant at) {
        return jdbc.sql("""
            insert into promise_conversion(tenant_id, impression_id, segment, order_id, promised_days, buffer_days, converted, observed_at)
            values (:t, :id, :seg, :o, :pd, :bd, :c, :at) on conflict (tenant_id, impression_id) do nothing
            """).param("t", TenantContext.require()).param("id", impressionId).param("seg", segment).param("o", orderId).param("pd", promisedDays)
            .param("bd", bufferDays).param("c", converted).param("at", Timestamp.from(at)).update() == 1;
    }

    /** Per segment and buffer: impressions, conversions, converted orders with a delivery outcome, and how many were on time. */
    public record BufferStats(String segment, int bufferDays, long impressions, long conversions, long delivered, long onTime) {}

    public List<BufferStats> stats(Instant since) {
        return jdbc.sql("""
            select pc.segment, pc.buffer_days, count(*) as n, count(*) filter (where pc.converted) as conv,
                   count(*) filter (where pc.converted and d.on_time is not null) as delivered, count(*) filter (where pc.converted and d.on_time) as on_time
            from promise_conversion pc
            left join lateral (select bool_and(na.delivered_on_time) as on_time from node_assignment na
                               where na.tenant_id = pc.tenant_id and na.order_id = pc.order_id and na.delivered_at is not null) d on true
            where pc.tenant_id = :t and pc.observed_at >= :since
            group by pc.segment, pc.buffer_days order by pc.segment, pc.buffer_days
            """).param("t", TenantContext.require()).param("since", Timestamp.from(since)).query().listOfRows().stream()
            .map(r -> new BufferStats((String) r.get("segment"), ((Number) r.get("buffer_days")).intValue(), ((Number) r.get("n")).longValue(),
                ((Number) r.get("conv")).longValue(), ((Number) r.get("delivered")).longValue(), ((Number) r.get("on_time")).longValue())).toList();
    }
}
