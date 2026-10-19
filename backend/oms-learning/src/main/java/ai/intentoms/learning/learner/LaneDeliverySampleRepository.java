package ai.intentoms.learning.learner;

import ai.intentoms.platform.tenant.TenantContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;

/** Raw per-delivery lane samples (FS-03, CX-01), one per (lane, order, node). Aggregated on read, newest samples first. */
@Repository
public class LaneDeliverySampleRepository {
    private final JdbcClient jdbc;
    public LaneDeliverySampleRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    public void record(String laneKey, double transitDays, boolean onTime) { record(laneKey, transitDays, onTime, null, null, null, null); }

    public boolean record(String laneKey, double transitDays, boolean onTime, Double statedOnTime, String orderId, String nodeId) {
        return record(laneKey, transitDays, onTime, statedOnTime, null, orderId, nodeId);
    }

    /** Returns false when this order and node were already sampled for the lane. {@code baseOnTime} is the probability before calibration. */
    public boolean record(String laneKey, double transitDays, boolean onTime, Double statedOnTime, Double baseOnTime, String orderId, String nodeId) {
        return jdbc.sql("""
            insert into lane_delivery_sample(tenant_id, lane_key, transit_days, on_time, stated_on_time, base_on_time, order_id, node_id)
            values (:t, :k, :d, :ot, :st, :base, :o, :n)
            on conflict (tenant_id, lane_key, order_id, node_id) where order_id is not null do nothing
            """).param("t", TenantContext.require()).param("k", laneKey).param("d", transitDays).param("ot", onTime).param("st", statedOnTime)
            .param("base", baseOnTime).param("o", orderId).param("n", nodeId).update() == 1;
    }

    /** One delivery on a lane: on time or not, the probability the order was told and the one before lane calibration. */
    public record Sample(boolean onTime, Double stated, Double base) {}

    /** CX-01: a lane's most recent deliveries, oldest first, so a report can learn on the earlier ones and measure on the later. */
    public List<Sample> recentSamples(String laneKey, int limit) {
        List<Sample> newestFirst = jdbc.sql("""
            select on_time, stated_on_time, base_on_time from lane_delivery_sample where tenant_id = :t and lane_key = :k order by observed_at desc limit :lim
            """).param("t", TenantContext.require()).param("k", laneKey).param("lim", limit)
            .query((rs, i) -> new Sample(rs.getBoolean("on_time"), (Double) rs.getObject("stated_on_time"), (Double) rs.getObject("base_on_time"))).list();
        List<Sample> out = new java.util.ArrayList<>(newestFirst);
        java.util.Collections.reverse(out);
        return out;
    }

    /** Most recent transit-day samples for a lane, newest first, capped so this stays O(1)-ish even on a busy lane. */
    public List<Double> recentTransitDays(String laneKey, int limit) {
        String t = TenantContext.require();
        return jdbc.sql("select transit_days from lane_delivery_sample where tenant_id = :t and lane_key = :k order by observed_at desc limit :lim")
            .param("t", t).param("k", laneKey).param("lim", limit).query(Double.class).list();
    }

    /**
     * {@code statedSamples}/{@code meanStated}: the samples that carried the on-time probability the order was given;
     * {@code baseSamples}/{@code meanBase}/{@code onTimeOfBase}: those that also carried it before lane calibration.
     */
    public record OnTimeStats(long total, long onTimeCount, long statedSamples, double meanStated, long onTimeOfStated, long baseSamples, double meanBase, long onTimeOfBase) {
        public OnTimeStats(long total, long onTimeCount) { this(total, onTimeCount, 0, 0, 0, 0, 0, 0); }
        public OnTimeStats(long total, long onTimeCount, long statedSamples, double meanStated, long onTimeOfStated) {
            this(total, onTimeCount, statedSamples, meanStated, onTimeOfStated, 0, 0, 0);
        }
    }

    public OnTimeStats onTimeStats(String laneKey, int sampleLimit) {
        var r = jdbc.sql("""
            select count(*) as n, count(*) filter (where on_time) as ot, count(stated_on_time) as ns, coalesce(avg(stated_on_time), 0) as ms,
                   count(*) filter (where on_time and stated_on_time is not null) as ots,
                   count(base_on_time) as nb, coalesce(avg(base_on_time), 0) as mb, count(*) filter (where on_time and base_on_time is not null) as otb
            from (select on_time, stated_on_time, base_on_time from lane_delivery_sample where tenant_id = :t and lane_key = :k order by observed_at desc limit :lim) s
            """).param("t", TenantContext.require()).param("k", laneKey).param("lim", sampleLimit).query().singleRow();
        return new OnTimeStats(((Number) r.get("n")).longValue(), ((Number) r.get("ot")).longValue(), ((Number) r.get("ns")).longValue(),
            ((Number) r.get("ms")).doubleValue(), ((Number) r.get("ots")).longValue(), ((Number) r.get("nb")).longValue(), ((Number) r.get("mb")).doubleValue(),
            ((Number) r.get("otb")).longValue());
    }

    public List<String> lanesWithSamples() {
        String t = TenantContext.require();
        return jdbc.sql("select distinct lane_key from lane_delivery_sample where tenant_id = :t").param("t", t).query(String.class).list();
    }
}
