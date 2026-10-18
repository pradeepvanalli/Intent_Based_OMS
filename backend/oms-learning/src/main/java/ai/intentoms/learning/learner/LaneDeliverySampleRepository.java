package ai.intentoms.learning.learner;

import ai.intentoms.platform.tenant.TenantContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;

/** Raw per-delivery lane samples (FS-03, CX-01). Aggregated on read; see V1601 for the lane_key granularity note. */
@Repository
public class LaneDeliverySampleRepository {
    private final JdbcClient jdbc;
    public LaneDeliverySampleRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    public void record(String laneKey, double transitDays, boolean onTime) {
        jdbc.sql("insert into lane_delivery_sample(tenant_id, lane_key, transit_days, on_time) values (:t, :k, :d, :ot)")
            .param("t", TenantContext.require()).param("k", laneKey).param("d", transitDays).param("ot", onTime).update();
    }

    /** Most recent transit-day samples for a lane, newest first, capped so this stays O(1)-ish even on a busy lane. */
    public List<Double> recentTransitDays(String laneKey, int limit) {
        String t = TenantContext.require();
        return jdbc.sql("select transit_days from lane_delivery_sample where tenant_id = :t and lane_key = :k order by observed_at desc limit :lim")
            .param("t", t).param("k", laneKey).param("lim", limit).query(Double.class).list();
    }

    public record OnTimeStats(long total, long onTimeCount) {}

    public OnTimeStats onTimeStats(String laneKey, int sampleLimit) {
        String t = TenantContext.require();
        var rows = jdbc.sql("select on_time from (select on_time from lane_delivery_sample where tenant_id = :t and lane_key = :k order by observed_at desc limit :lim) s")
            .param("t", t).param("k", laneKey).param("lim", sampleLimit).query(Boolean.class).list();
        return new OnTimeStats(rows.size(), rows.stream().filter(Boolean::booleanValue).count());
    }

    public List<String> lanesWithSamples() {
        String t = TenantContext.require();
        return jdbc.sql("select distinct lane_key from lane_delivery_sample where tenant_id = :t").param("t", t).query(String.class).list();
    }
}
