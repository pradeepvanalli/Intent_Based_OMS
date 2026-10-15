package ai.intentoms.monitoring.pipeline;

import ai.intentoms.monitoring.config.MonitoringProperties;
import ai.intentoms.platform.json.Json;
import ai.intentoms.platform.observability.Metrics;
import ai.intentoms.platform.tenant.TenantContext;
import ai.intentoms.platform.time.TimeSource;
import com.fasterxml.jackson.core.type.TypeReference;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Control-tower pipeline (FR-MON-01): one SQL over {@code orders} joined with the state machine's header state and the
 * time quantity entered it, grouped by state x age bucket x channel x node x order type. Results are cached per
 * tenant+filter for {@code oms.monitoring.pipeline-cache-ms} (3 s) which keeps the view inside the 5 s freshness budget
 * while protecting the database from dashboard polling. {@link #atRisk(int)} serves the at-risk list from
 * {@code order_risk} (FR-MON-02).
 */
@Service
public class PipelineViewService {
    /** Canonical display order of header states; unknown states follow alphabetically. */
    static final List<String> STATE_ORDER = List.of("created", "validated", "sourcing", "released", "picking", "packed", "partially_shipped", "shipped",
        "ready_for_pickup", "partially_delivered", "on_hold", "backordered", "delivered", "picked_up", "cancelled");
    private static final TypeReference<List<String>> STRINGS = new TypeReference<>() {};

    private final JdbcClient jdbc;
    private final TimeSource time;
    private final Metrics metrics;
    private final MonitoringProperties props;
    private final Cache<String, PipelineView> cache;

    public PipelineViewService(JdbcClient jdbc, TimeSource time, Metrics metrics, MonitoringProperties props) {
        this.jdbc = jdbc;
        this.time = time;
        this.metrics = metrics;
        this.props = props;
        this.cache = Caffeine.newBuilder().expireAfterWrite(Duration.ofMillis(props.pipelineCacheMs())).maximumSize(10_000).build();
    }

    /** Pipeline view for the current tenant; served from the short cache when fresh. */
    public PipelineView pipeline(PipelineFilters filters) {
        PipelineFilters f = filters == null ? PipelineFilters.NONE : filters;
        String tenant = TenantContext.require();
        return cache.get(tenant + "|" + f.cacheKey(), k -> metrics.time("oms.monitoring.pipeline", () -> compute(tenant, f)));
    }

    /** Drop cached views (after bulk data changes in tests or admin sweeps). */
    public void invalidate() { cache.invalidateAll(); }

    private PipelineView compute(String tenant, PipelineFilters f) {
        Instant now = time.now();
        StringBuilder where = new StringBuilder(" where o.tenant_id = :t and o.status not in (:terminal)");
        Map<String, Object> params = new HashMap<>();
        params.put("t", tenant);
        params.put("terminal", props.terminalStatuses());
        params.put("now", Timestamp.from(now));
        if (f.channel() != null) { where.append(" and o.channel = :channel"); params.put("channel", f.channel()); }
        if (f.orderType() != null) { where.append(" and o.order_type = :otype"); params.put("otype", f.orderType()); }
        if (f.nodeId() != null) {
            where.append(" and (o.primary_node_id = :node or exists (select 1 from fulfilment_group g where g.tenant_id = o.tenant_id and g.order_id = o.id and g.node_id = :node))");
            params.put("node", f.nodeId());
        }
        String sql = """
            with base as (
              select coalesce(es.state, o.status) as state, o.channel, coalesce(o.primary_node_id, 'unassigned') as node_id, o.order_type,
                     extract(epoch from (:now - coalesce(q.since, es.updated_at, o.placed_at))) as age_s,
                     case when r.level in ('high', 'critical') then 1 else 0 end as at_risk,
                     case when exists (select 1 from hold h where h.tenant_id = o.tenant_id and h.scope_type = 'order' and h.scope_id = o.id
                                        and h.resolved_at is null and (h.expires_at is null or h.expires_at > :now)) then 1 else 0 end as held
              from orders o
              left join entity_state es on es.tenant_id = o.tenant_id and es.entity_type = 'order' and es.entity_id = o.id
              left join entity_state_qty q on q.tenant_id = es.tenant_id and q.entity_type = es.entity_type and q.entity_id = es.entity_id and q.state = es.state
              left join order_risk r on r.tenant_id = o.tenant_id and r.order_id = o.id
            """ + where + """
            )
            select state, """ + AgeBuckets.sqlCase("age_s") + """
             as age_bucket, channel, node_id, order_type, count(*) as n, sum(at_risk) as at_risk, sum(held) as held
            from base group by 1, 2, 3, 4, 5
            """;
        var spec = jdbc.sql(sql);
        for (var e : params.entrySet()) spec = spec.param(e.getKey(), e.getValue());
        List<Map<String, Object>> rows = spec.query().listOfRows();

        Map<String, Map<String, Long>> cellCounts = new HashMap<>();
        Map<String, Long> byChannel = new TreeMap<>();
        Map<String, Long> byNode = new TreeMap<>();
        Map<String, Long> byOrderType = new TreeMap<>();
        long open = 0, atRisk = 0, held = 0;
        for (Map<String, Object> r : rows) {
            long n = ((Number) r.get("n")).longValue();
            String state = (String) r.get("state");
            cellCounts.computeIfAbsent(state, s -> new HashMap<>()).merge((String) r.get("age_bucket"), n, Long::sum);
            byChannel.merge((String) r.get("channel"), n, Long::sum);
            byNode.merge((String) r.get("node_id"), n, Long::sum);
            byOrderType.merge((String) r.get("order_type"), n, Long::sum);
            open += n;
            atRisk += ((Number) r.get("at_risk")).longValue();
            held += ((Number) r.get("held")).longValue();
        }
        List<String> states = new ArrayList<>(cellCounts.keySet());
        states.sort(Comparator.comparingInt(PipelineViewService::stateRank).thenComparing(Comparator.naturalOrder()));
        List<PipelineView.Cell> cells = new ArrayList<>();
        for (String s : states) for (String b : AgeBuckets.ORDER) cells.add(new PipelineView.Cell(s, b, cellCounts.get(s).getOrDefault(b, 0L)));
        return new PipelineView(states, AgeBuckets.ORDER, cells, byChannel, byNode, byOrderType, open, atRisk, held, now);
    }

    private static int stateRank(String s) {
        int i = STATE_ORDER.indexOf(s);
        return i < 0 ? STATE_ORDER.size() : i;
    }

    /** Highest-risk open orders first (FR-MON-02, UI contract item 8 "at-risk"). */
    public List<AtRiskOrder> atRisk(int limit) {
        int n = limit <= 0 || limit > 500 ? 50 : limit;
        Instant now = time.now();
        var rows = jdbc.sql("""
            select r.order_id, o.channel_order_id, o.status, o.order_type, o.channel, o.primary_node_id, r.score, r.level, r.reasons::text as reasons, r.decision_record_id, r.scored_at,
                   o.placed_at, (select min(g.promise_date) from fulfilment_group g where g.tenant_id = o.tenant_id and g.order_id = o.id) as promise_date
            from order_risk r join orders o on o.tenant_id = r.tenant_id and o.id = r.order_id
            where r.tenant_id = :t and o.status not in (:terminal) and r.score >= 1
            order by r.score desc, r.scored_at desc limit :n
            """).param("t", TenantContext.require()).param("terminal", props.terminalStatuses()).param("n", n).query().listOfRows();
        List<AtRiskOrder> out = new ArrayList<>(rows.size());
        for (Map<String, Object> r : rows) {
            Instant placed = ((Timestamp) r.get("placed_at")).toInstant();
            out.add(new AtRiskOrder((String) r.get("order_id"), (String) r.get("channel_order_id"), (String) r.get("status"), (String) r.get("order_type"), (String) r.get("channel"),
                (String) r.get("primary_node_id"), ((Number) r.get("score")).intValue(), (String) r.get("level"), Json.read((String) r.get("reasons"), STRINGS),
                Math.max(0, Duration.between(placed, now).toMinutes()), r.get("promise_date") == null ? null : ((java.sql.Date) r.get("promise_date")).toLocalDate(),
                (String) r.get("decision_record_id"), ((Timestamp) r.get("scored_at")).toInstant()));
        }
        return out;
    }

    /** Convenience for tests and the UI drawer: the cells of one view as a state -> bucket -> count map. */
    public static Map<String, Map<String, Long>> asMatrix(PipelineView v) {
        Map<String, Map<String, Long>> m = new LinkedHashMap<>();
        for (PipelineView.Cell c : v.cells()) m.computeIfAbsent(c.state(), s -> new LinkedHashMap<>()).put(c.ageBucket(), c.count());
        return m;
    }
}
