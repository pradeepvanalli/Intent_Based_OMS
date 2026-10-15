package ai.intentoms.analytics.kpi;

import ai.intentoms.analytics.config.AnalyticsProperties;
import ai.intentoms.platform.tenant.TenantContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.TreeMap;

/**
 * Tenant-scoped SQL behind the section 13 KPIs (FR-ANL-01). Every method reads the source tables directly (orders,
 * fulfilment_group, event, human_task, decision_record, ai_spend, exception, refund, return_rma, node) and buckets by UTC
 * day; optional upstream tables are probed with {@code to_regclass} so the module works when returns or sourcing are absent.
 */
@Repository
public class KpiRepository {
    private static final String DAY_PLACED = "(o.placed_at at time zone 'UTC')::date";
    private final JdbcClient jdbc;
    private final AnalyticsProperties props;

    public KpiRepository(JdbcClient jdbc, AnalyticsProperties props) {
        this.jdbc = jdbc;
        this.props = props;
    }

    /** A labelled live decision: used for decision accuracy and calibration error (section 13 "Decision accuracy", "Calibration error"). */
    public record LabelledRecord(LocalDate day, String decision, double confidence, boolean correct) {}

    // ---- orders ---------------------------------------------------------------------------------------------------

    /** Orders placed per day. */
    public Map<LocalDate, Double> ordersPlaced(KpiRange r) {
        return counts("select " + DAY_PLACED + " as day, count(*) as n from orders o where o.tenant_id = :t and o.placed_at >= :from and o.placed_at < :to group by 1", r);
    }

    /** Orders captured per day (GR-17 denominator). */
    public Map<LocalDate, Double> ordersCaptured(KpiRange r) {
        return counts("select (o.captured_at at time zone 'UTC')::date as day, count(*) as n from orders o where o.tenant_id = :t and o.captured_at >= :from and o.captured_at < :to group by 1", r);
    }

    /**
     * Touchless rate per placed day: orders in a terminal status (denominator) with no COMPLETED human task on the order and no
     * overridden decision record for the order (numerator). Section 13 "Touchless order rate".
     */
    public List<DayPoint> touchless(KpiRange r) {
        return points("""
            select %s as day, count(*) as den,
                   count(*) filter (where not exists (select 1 from human_task h where h.tenant_id = o.tenant_id and h.subject_type = 'order' and h.subject_id = o.id and h.status = 'COMPLETED')
                                      and not exists (select 1 from decision_record d where d.tenant_id = o.tenant_id and d.subject_type = 'order' and d.subject_id = o.id and d.overridden_at is not null)) as num
            from orders o where o.tenant_id = :t and o.placed_at >= :from and o.placed_at < :to and o.status in (:terminal) group by 1
            """.formatted(DAY_PLACED), r, true);
    }

    /** Promise accuracy per delivery day: first header transition to delivered compared with the latest promise date of the order's fulfilment groups. */
    public List<DayPoint> promiseAccuracy(KpiRange r) {
        return points("""
            with d as (select e.aggregate_id as order_id, min(e.occurred_at) as delivered_at from event e
                       where e.tenant_id = :t and e.aggregate_type = 'order' and e.type = 'order.transitioned' and e.payload->>'entityType' = 'order' and e.payload->>'to' = 'delivered'
                         and e.occurred_at >= :from and e.occurred_at < :to group by e.aggregate_id),
                 p as (select g.order_id, max(g.promise_date) as promise from fulfilment_group g where g.tenant_id = :t and g.promise_date is not null group by g.order_id)
            select (d.delivered_at at time zone 'UTC')::date as day, count(*) as den, count(*) filter (where (d.delivered_at at time zone 'UTC')::date <= p.promise) as num
            from d join p on p.order_id = d.order_id group by 1
            """, r, false);
    }

    /** Split-shipment rate per placed day: orders with more than one fulfilment group. */
    public List<DayPoint> splitRate(KpiRange r) {
        return points("""
            select %s as day, count(*) as den,
                   count(*) filter (where (select count(*) from fulfilment_group g where g.tenant_id = o.tenant_id and g.order_id = o.id) > 1) as num
            from orders o where o.tenant_id = :t and o.placed_at >= :from and o.placed_at < :to group by 1
            """.formatted(DAY_PLACED), r, false);
    }

    /** Cost to serve per placed day: sum of fulfilment-group estimated cost over orders that have at least one group. */
    public List<DayPoint> costToServe(KpiRange r) {
        return points("""
            select %s as day, count(distinct o.id) as den, coalesce(sum(g.estimated_cost), 0) as num
            from orders o join fulfilment_group g on g.tenant_id = o.tenant_id and g.order_id = o.id
            where o.tenant_id = :t and o.placed_at >= :from and o.placed_at < :to group by 1
            """.formatted(DAY_PLACED), r, false);
    }

    /** Margin-floor compliance per placed day: below-floor orders (denominator) carrying an approval task id in the margin snapshot (numerator). GR-01 / FR-SRC-04. */
    public List<DayPoint> marginFloorCompliance(KpiRange r) {
        return points("""
            select %s as day, count(*) as den, count(*) filter (where o.margin->>'approvalTaskId' is not null) as num
            from orders o where o.tenant_id = :t and o.placed_at >= :from and o.placed_at < :to and o.below_floor group by 1
            """.formatted(DAY_PLACED), r, false);
    }

    /** Store rejections per day: {@code order.transitioned} events fired by trigger {@code event.store_rejected} (FR-SRC-07). */
    public Map<LocalDate, Double> storeRejects(KpiRange r) {
        return counts("""
            select (e.occurred_at at time zone 'UTC')::date as day, count(*) as n from event e
            where e.tenant_id = :t and e.type = 'order.transitioned' and e.payload->>'trigger' = 'event.store_rejected' and e.occurred_at >= :from and e.occurred_at < :to group by 1
            """, r);
    }

    /** Fulfilment groups created per day at store nodes (all nodes when the sourcing node table is absent). */
    public Map<LocalDate, Double> storeFulfilmentGroups(KpiRange r) {
        if (tableExists("node")) {
            return counts("""
                select (g.created_at at time zone 'UTC')::date as day, count(*) as n from fulfilment_group g
                join node n on n.tenant_id = g.tenant_id and n.id = g.node_id and n.type = 'store'
                where g.tenant_id = :t and g.created_at >= :from and g.created_at < :to group by 1
                """, r);
        }
        return counts("select (g.created_at at time zone 'UTC')::date as day, count(*) as n from fulfilment_group g where g.tenant_id = :t and g.created_at >= :from and g.created_at < :to group by 1", r);
    }

    // ---- exceptions ----------------------------------------------------------------------------------------------

    /** Per resolution day: median minutes open (as median x count / count) of exceptions resolved that day. */
    public List<DayPoint> exceptionResolutionDaily(KpiRange r) {
        return medianPoints("""
            select (x.resolved_at at time zone 'UTC')::date as day, count(*) as n,
                   percentile_cont(0.5) within group (order by extract(epoch from (x.resolved_at - x.created_at)) / 60.0) as med
            from exception x where x.tenant_id = :t and x.status = 'RESOLVED' and x.resolved_at >= :from and x.resolved_at < :to group by 1
            """, r);
    }

    /** Exact median minutes open of exceptions resolved in the range. */
    public OptionalDouble exceptionResolutionMedian(KpiRange r) {
        return median("""
            select percentile_cont(0.5) within group (order by extract(epoch from (x.resolved_at - x.created_at)) / 60.0) as med
            from exception x where x.tenant_id = :t and x.status = 'RESOLVED' and x.resolved_at >= :from and x.resolved_at < :to
            """, r);
    }

    public long exceptionsOpen() {
        return jdbc.sql("select count(*) from exception where tenant_id = :t and status in ('OPEN', 'IN_PROGRESS')").param("t", TenantContext.require()).query(Long.class).single();
    }

    public long openOrders() {
        return jdbc.sql("select count(*) from orders where tenant_id = :t and status not in (:terminal)")
            .param("t", TenantContext.require()).param("terminal", props.terminalStatuses()).query(Long.class).single();
    }

    // ---- returns -------------------------------------------------------------------------------------------------

    /**
     * Return abuse loss per refund day: succeeded refunds on returns (denominator, USD) and the part of them whose RMA was
     * scored high/severe/critical risk (numerator, USD). Empty when the returns module's table is not installed.
     */
    public List<DayPoint> returnAbuseLoss(KpiRange r) {
        if (!tableExists("return_rma") || !tableExists("refund")) return List.of();
        return points("""
            select (f.created_at at time zone 'UTC')::date as day, coalesce(sum(f.amount), 0) as den,
                   coalesce(sum(f.amount) filter (where rma.risk_level in ('high', 'severe', 'critical')), 0) as num
            from refund f join return_rma rma on rma.tenant_id = f.tenant_id and rma.rma_id = f.return_id
            where f.tenant_id = :t and f.status = 'SUCCEEDED' and f.created_at >= :from and f.created_at < :to group by 1
            """, r, false);
    }

    // ---- customer contacts ---------------------------------------------------------------------------------------

    /** WISMO contacts per day: {@code cs.wismo} ledger events plus live {@code customer_request_intent} decisions whose value is {@code wismo}. */
    public Map<LocalDate, Double> wismoContacts(KpiRange r) {
        return counts("""
            select day, sum(n) as n from (
              select (e.occurred_at at time zone 'UTC')::date as day, count(*) as n from event e
              where e.tenant_id = :t and e.type = 'cs.wismo' and e.occurred_at >= :from and e.occurred_at < :to group by 1
              union all
              select (d.created_at at time zone 'UTC')::date as day, count(*) as n from decision_record d
              where d.tenant_id = :t and d.decision = 'customer_request_intent' and d.mode = 'LIVE' and d.value = to_jsonb('wismo'::text)
                and d.created_at >= :from and d.created_at < :to group by 1
            ) x group by day
            """, r);
    }

    // ---- policy lead time ----------------------------------------------------------------------------------------

    private static final String POLICY_LEAD_CTE = """
        with d as (select e.payload->>'decision' as decision, e.payload->>'version' as version, min(e.occurred_at) as drafted_at, min(e.payload->>'tier') as tier
                   from event e where e.tenant_id = :t and e.aggregate_type = 'config' and e.type = 'decision.definition.drafted' group by 1, 2),
             p as (select e.payload->>'decision' as decision, e.payload->>'version' as version, min(e.occurred_at) as published_at
                   from event e where e.tenant_id = :t and e.aggregate_type = 'config' and e.type in ('decision.definition.published', 'decision.definition.emergency_published')
                     and e.occurred_at >= :from and e.occurred_at < :to group by 1, 2)
        """;

    /** Per publish day: median hours from draft to publish of T0/T1 decision versions (section 13 "Policy change lead time"). */
    public List<DayPoint> policyLeadTimeDaily(KpiRange r) {
        return medianPoints(POLICY_LEAD_CTE + """
            select (p.published_at at time zone 'UTC')::date as day, count(*) as n,
                   percentile_cont(0.5) within group (order by extract(epoch from (p.published_at - d.drafted_at)) / 3600.0) as med
            from p join d on d.decision = p.decision and d.version = p.version where coalesce(d.tier, 'T0') in ('T0', 'T1') group by 1
            """, r);
    }

    public OptionalDouble policyLeadTimeMedian(KpiRange r) {
        return median(POLICY_LEAD_CTE + """
            select percentile_cont(0.5) within group (order by extract(epoch from (p.published_at - d.drafted_at)) / 3600.0) as med
            from p join d on d.decision = p.decision and d.version = p.version where coalesce(d.tier, 'T0') in ('T0', 'T1')
            """, r);
    }

    // ---- decisions -----------------------------------------------------------------------------------------------

    /** Labelled live decision records in the range: label {@code correct}, label {@code outcome} (compared with the value) or an override (counts as wrong). FR-DEC-10, GR-13. */
    public List<LabelledRecord> labelledRecords(KpiRange r) {
        var rows = jdbc.sql("""
            select (d.created_at at time zone 'UTC')::date as day, d.decision, d.confidence,
                   case when d.overridden_at is not null then false
                        when jsonb_exists(d.labels, 'correct') then coalesce(d.labels->'correct'->>'value', d.labels->>'correct') = 'true'
                        else coalesce(d.labels->'outcome'->'value', d.labels->'outcome') = d.value end as correct
            from decision_record d
            where d.tenant_id = :t and d.mode = 'LIVE' and d.created_at >= :from and d.created_at < :to
              and (jsonb_exists(d.labels, 'correct') or jsonb_exists(d.labels, 'outcome') or d.overridden_at is not null)
            """).param("t", TenantContext.require()).param("from", ts(r.startInclusive())).param("to", ts(r.endExclusive())).query().listOfRows();
        List<LabelledRecord> out = new ArrayList<>(rows.size());
        for (var row : rows) out.add(new LabelledRecord(day(row.get("day")), (String) row.get("decision"), num(row.get("confidence")), Boolean.TRUE.equals(row.get("correct"))));
        return out;
    }

    /** Override rate per day: autonomous (act band, live) records (denominator) later overridden (numerator). GR-13. */
    public List<DayPoint> overrides(KpiRange r) {
        return points("""
            select (d.created_at at time zone 'UTC')::date as day, count(*) as den, count(*) filter (where d.overridden_at is not null) as num
            from decision_record d where d.tenant_id = :t and d.mode = 'LIVE' and d.action = 'act' and d.created_at >= :from and d.created_at < :to group by 1
            """, r, false);
    }

    /** Provider spend per day from {@code ai_spend} (GR-17). */
    public Map<LocalDate, Double> aiSpend(KpiRange r) {
        var rows = jdbc.sql("select s.day, sum(s.cost_usd) as n from ai_spend s where s.tenant_id = :t and s.day >= :from and s.day <= :to group by s.day")
            .param("t", TenantContext.require()).param("from", r.from()).param("to", r.to()).query().listOfRows();
        Map<LocalDate, Double> out = new TreeMap<>();
        for (var row : rows) out.put(day(row.get("day")), num(row.get("n")));
        return out;
    }

    /** Tenants with orders or decisions since {@code since}; drives the nightly roll-up across tenants. */
    public List<String> tenantsWithActivity(Instant since) {
        return jdbc.sql("""
            select distinct tenant_id from orders where placed_at >= :s
            union select distinct tenant_id from decision_record where created_at >= :s
            union select distinct tenant_id from exception where created_at >= :s
            """).param("s", ts(since)).query(String.class).list();
    }

    // ---- helpers -------------------------------------------------------------------------------------------------

    private List<DayPoint> points(String sql, KpiRange r, boolean withTerminal) {
        var spec = jdbc.sql(sql).param("t", TenantContext.require()).param("from", ts(r.startInclusive())).param("to", ts(r.endExclusive()));
        if (withTerminal) spec = spec.param("terminal", props.terminalStatuses());
        List<DayPoint> out = new ArrayList<>();
        for (var row : spec.query().listOfRows()) out.add(new DayPoint(day(row.get("day")), num(row.get("num")), num(row.get("den"))));
        return out;
    }

    private List<DayPoint> medianPoints(String sql, KpiRange r) {
        List<DayPoint> out = new ArrayList<>();
        for (var row : jdbc.sql(sql).param("t", TenantContext.require()).param("from", ts(r.startInclusive())).param("to", ts(r.endExclusive())).query().listOfRows()) {
            double n = num(row.get("n"));
            out.add(new DayPoint(day(row.get("day")), num(row.get("med")) * n, n));
        }
        return out;
    }

    private OptionalDouble median(String sql, KpiRange r) {
        var rows = jdbc.sql(sql).param("t", TenantContext.require()).param("from", ts(r.startInclusive())).param("to", ts(r.endExclusive())).query().listOfRows();
        if (rows.isEmpty() || rows.get(0).get("med") == null) return OptionalDouble.empty();
        return OptionalDouble.of(num(rows.get(0).get("med")));
    }

    private Map<LocalDate, Double> counts(String sql, KpiRange r) {
        Map<LocalDate, Double> out = new LinkedHashMap<>();
        for (var row : jdbc.sql(sql).param("t", TenantContext.require()).param("from", ts(r.startInclusive())).param("to", ts(r.endExclusive())).query().listOfRows()) {
            out.put(day(row.get("day")), num(row.get("n")));
        }
        return out;
    }

    private boolean tableExists(String table) {
        return Boolean.TRUE.equals(jdbc.sql("select to_regclass(:n) is not null").param("n", table).query(Boolean.class).single());
    }

    private static Timestamp ts(Instant i) { return Timestamp.from(i); }

    private static LocalDate day(Object o) {
        if (o instanceof java.sql.Date d) return d.toLocalDate();
        if (o instanceof LocalDate d) return d;
        return LocalDate.parse(String.valueOf(o));
    }

    private static double num(Object o) { return o instanceof Number n ? n.doubleValue() : 0.0; }
}
