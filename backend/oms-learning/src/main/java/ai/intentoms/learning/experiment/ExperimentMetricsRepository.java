package ai.intentoms.learning.experiment;

import ai.intentoms.platform.json.Json;
import ai.intentoms.platform.tenant.TenantContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Read-only measurements behind experiments, guardrails and lift, straight from {@code decision_record} and
 * {@code episode}. Rows written by the demo reference-data seeder ({@code actor = 'system:demo-seed'}) never count
 * toward a guardrail, so seeded history cannot roll back a real change.
 */
@Repository
public class ExperimentMetricsRepository {
    public static final String DEMO_SEED_ACTOR = "system:demo-seed";

    private final JdbcClient jdbc;
    public ExperimentMetricsRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    /** Rewards of matured or provisional episodes whose anchor decision ran in this experiment arm. */
    public List<Double> armRewards(String decision, String experimentId, String arm, Instant since) {
        return jdbc.sql("""
            select e.reward from episode e
            join decision_record d on d.tenant_id = e.tenant_id and d.id = e.decision_id
            where e.tenant_id = :t and e.decision_name = :dec and e.reward is not null and d.mode = 'LIVE' and d.created_at >= :since
              and d.details->'experiment'->>'experimentId' = :exp and d.details->'experiment'->>'arm' = :arm
            """).param("t", TenantContext.require()).param("dec", decision).param("since", Timestamp.from(since)).param("exp", experimentId).param("arm", arm)
            .query(Double.class).list();
    }

    /** Rewards of the control group: same decision and window, on the baseline version, not in any experiment arm. */
    public List<Double> controlRewards(String decision, Integer baselineVersion, Instant since) {
        return jdbc.sql("""
            select e.reward from episode e
            join decision_record d on d.tenant_id = e.tenant_id and d.id = e.decision_id
            where e.tenant_id = :t and e.decision_name = :dec and e.reward is not null and d.mode = 'LIVE' and d.created_at >= :since
              and (cast(:v as int) is null or d.version = cast(:v as int)) and d.details->'experiment' is null
            """).param("t", TenantContext.require()).param("dec", decision).param("since", Timestamp.from(since)).param("v", baselineVersion)
            .query(Double.class).list();
    }

    /** EX-02: a shadow version's records paired with the live record for the same input and subject. */
    public Map<String, Object> shadowComparison(String decision, int shadowVersion, Instant since) {
        var row = jdbc.sql("""
            with s as (
              select s.id, s.value, s.band, s.requires_human, s.latency_ms, s.input_hash, s.subject_id, s.created_at,
                     exists (select 1 from jsonb_array_elements(case when jsonb_typeof(s.details->'postChecks') = 'array' then s.details->'postChecks' else '[]'::jsonb end) pc
                             where (pc->>'passed')::boolean is false) as post_failed
              from decision_record s
              where s.tenant_id = :t and s.decision = :dec and s.version = :v and s.mode = 'SHADOW' and s.created_at >= :since
            ), p as (
              select s.*, l.value as live_value, l.band as live_band, l.requires_human as live_human, l.latency_ms as live_latency, l.id as live_id
              from s
              join lateral (
                select l.* from decision_record l
                where l.tenant_id = :t and l.decision = :dec and l.mode = 'LIVE' and l.input_hash = s.input_hash
                  and l.subject_id is not distinct from s.subject_id and l.created_at between s.created_at - interval '5 minutes' and s.created_at + interval '5 minutes'
                order by abs(extract(epoch from l.created_at - s.created_at)) limit 1
              ) l on true
            )
            select (select count(*) from s) as shadow_records,
                   count(*) as paired,
                   count(*) filter (where p.value is not distinct from p.live_value) as agreed,
                   count(*) filter (where p.band = 'ESCALATE' or p.requires_human) as shadow_escalations,
                   count(*) filter (where p.live_band = 'ESCALATE' or p.live_human) as live_escalations,
                   count(*) filter (where p.post_failed) as post_check_failures,
                   coalesce(percentile_cont(0.95) within group (order by p.latency_ms), 0) as shadow_p95,
                   coalesce(percentile_cont(0.95) within group (order by p.live_latency), 0) as live_p95
            from p
            """).param("t", TenantContext.require()).param("dec", decision).param("v", shadowVersion).param("since", Timestamp.from(since))
            .query().singleRow();
        Map<String, Object> out = new LinkedHashMap<>();
        row.forEach((k, v) -> out.put(k, v instanceof Number n && !(v instanceof Double) ? n.longValue() : v));
        return out;
    }

    /** EX-02 for a {@code parameter:live:<family>} shadow: decisions whose learned value existed but was not served. */
    public Map<String, Object> learnedValueShadow(Instant since) {
        var row = jdbc.sql("""
            select count(distinct d.id) as decisions_affected,
                   count(*) as facts_held_back,
                   coalesce(avg(abs((f->>'learned')::double precision - coalesce((f->>'served')::double precision, (f->>'learned')::double precision))), 0) as mean_shift
            from decision_record d, jsonb_array_elements(case when jsonb_typeof(d.details->'learnedFacts') = 'array' then d.details->'learnedFacts' else '[]'::jsonb end) f
            where d.tenant_id = :t and d.mode = 'LIVE' and d.created_at >= :since and f->>'source' = 'STATIC' and jsonb_exists(f, 'learned')
            """).param("t", TenantContext.require()).param("since", Timestamp.from(since)).query().singleRow();
        return new LinkedHashMap<>(row);
    }

    public record Guardrails(long decisions, double overrideRate, double latencyP95, long fraudLabelled, double falsePositiveRate, long episodes, double contactRate) {
        public Map<String, Object> asMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("decisions", decisions);
            m.put("overrideRate", overrideRate);
            m.put("latencyP95Ms", latencyP95);
            m.put("fraudLabelled", fraudLabelled);
            m.put("falsePositiveRate", falsePositiveRate);
            m.put("episodes", episodes);
            m.put("contactRate", contactRate);
            return m;
        }
    }

    /** EX-05 guardrail metrics for a decision (optionally one version) over a window, excluding seeded rows. */
    public Guardrails guardrails(String decision, Integer version, Instant from, Instant to) {
        var r = jdbc.sql("""
            select count(*) as n,
                   count(*) filter (where d.overridden_at is not null) as overridden,
                   coalesce(percentile_cont(0.95) within group (order by d.latency_ms), 0) as p95,
                   count(*) filter (where jsonb_exists(d.labels, 'fraud_outcome')) as fraud_labelled,
                   count(*) filter (where jsonb_exists(d.labels, 'fraud_outcome') and (d.labels->'fraud_outcome'->>'confirmed')::boolean is false
                                    and (d.requires_human or d.action = 'blocked' or lower(d.value #>> '{}') in ('block', 'decline', 'review', 'hold', 'cancel'))) as false_positives,
                   count(e.episode_id) as episodes,
                   count(e.episode_id) filter (where coalesce((e.reward_components->>'cx')::double precision, 0) > 0) as contacted
            from decision_record d
            left join episode e on e.tenant_id = d.tenant_id and e.decision_id = d.id
            where d.tenant_id = :t and d.decision = :dec and d.mode = 'LIVE' and d.created_at >= :from and d.created_at < :to
              and (cast(:v as int) is null or d.version = cast(:v as int)) and coalesce(d.actor, '') <> :seed
            """).param("t", TenantContext.require()).param("dec", decision).param("from", Timestamp.from(from)).param("to", Timestamp.from(to))
            .param("v", version).param("seed", DEMO_SEED_ACTOR).query().singleRow();
        long n = num(r.get("n")), fraudLabelled = num(r.get("fraud_labelled")), episodes = num(r.get("episodes"));
        return new Guardrails(n, n == 0 ? 0 : num(r.get("overridden")) / (double) n, ((Number) r.get("p95")).doubleValue(), fraudLabelled,
            fraudLabelled == 0 ? 0 : num(r.get("false_positives")) / (double) fraudLabelled, episodes, episodes == 0 ? 0 : num(r.get("contacted")) / (double) episodes);
    }

    public record LiftRow(String decision, String arm, Double reward, Map<String, Double> components) {}

    /** XP-01: matured or provisional episodes since a point in time, with the experiment arm of their anchor decision. */
    public List<LiftRow> liftRows(Instant since) {
        List<LiftRow> out = new ArrayList<>();
        jdbc.sql("""
            select e.decision_name, d.details->'experiment'->>'arm' as arm, e.reward, e.reward_components::text as components
            from episode e join decision_record d on d.tenant_id = e.tenant_id and d.id = e.decision_id
            where e.tenant_id = :t and e.reward is not null and e.created_at >= :since
            """).param("t", TenantContext.require()).param("since", Timestamp.from(since)).query().listOfRows().forEach(r -> {
                Map<String, Object> raw = Json.readMap((String) r.get("components"));
                Map<String, Double> comps = new LinkedHashMap<>();
                raw.forEach((k, v) -> { if (v instanceof Number n) comps.put(k, n.doubleValue()); });
                out.add(new LiftRow((String) r.get("decision_name"), (String) r.get("arm"), (Double) r.get("reward"), comps));
            });
        return out;
    }

    private static long num(Object o) { return o == null ? 0 : ((Number) o).longValue(); }
}
