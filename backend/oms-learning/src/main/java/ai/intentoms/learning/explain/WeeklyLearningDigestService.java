package ai.intentoms.learning.explain;

import ai.intentoms.learning.event.LearningEvents;
import ai.intentoms.learning.service.CoverageMonitor;
import ai.intentoms.platform.config.TenantConfigStore;
import ai.intentoms.platform.ids.Ids;
import ai.intentoms.platform.jdbc.Jsonb;
import ai.intentoms.platform.json.Json;
import ai.intentoms.platform.ledger.Actor;
import ai.intentoms.platform.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * XP-04: a weekly learning digest per tenant: what changed (promotions, rollbacks, learner pauses and autonomy
 * changes, facts learned), the measured impact (mean matured reward per decision this week against the week before,
 * outcome coverage) and what is pending (proposals by status, objective drafts, open hypotheses). Stored in
 * {@code learning_digest}, recorded as {@code learning.digest_published}, and sent through every
 * {@link LearningDigestSender} to the addresses in tenant config {@code learning.digest.recipients} (a list).
 * Runs every Monday 07:00 UTC ({@code oms.learning.digest-cron}).
 */
@Service
public class WeeklyLearningDigestService {
    private static final Logger log = LoggerFactory.getLogger(WeeklyLearningDigestService.class);
    public static final String RECIPIENTS_KEY = "learning.digest.recipients";

    private final JdbcClient jdbc;
    private final CoverageMonitor coverage;
    private final TenantConfigStore config;
    private final LearningEvents events;
    private final ObjectProvider<LearningDigestSender> senders;

    public WeeklyLearningDigestService(JdbcClient jdbc, CoverageMonitor coverage, TenantConfigStore config, LearningEvents events, ObjectProvider<LearningDigestSender> senders) {
        this.jdbc = jdbc;
        this.coverage = coverage;
        this.config = config;
        this.events = events;
        this.senders = senders;
    }

    public record Digest(String digestId, Instant periodStart, Instant periodEnd, Map<String, Object> summary, List<String> deliveredTo, String delivery,
                         String createdBy, Instant createdAt) {}

    @Scheduled(cron = "${oms.learning.digest-cron:0 0 7 * * MON}", zone = "UTC")
    public void scheduled() {
        List<String> tenants = jdbc.sql("select tenant_id from tenant order by tenant_id").query(String.class).list();
        for (String t : tenants) {
            try { TenantContext.runAs(t, () -> publish(7, "system:learning-digest")); }
            catch (RuntimeException e) { log.warn("Weekly learning digest failed for tenant {}: {}", t, e.toString()); }
        }
    }

    public Digest publish(int days, String by) {
        Instant end = Instant.now();
        Instant start = end.minus(Duration.ofDays(Math.max(1, Math.min(days, 90))));
        Map<String, Object> summary = summarise(start, end);
        List<String> recipients = config.getList(RECIPIENTS_KEY).stream().map(String::valueOf).filter(s -> s.contains("@")).toList();
        List<String> delivered = new ArrayList<>();
        String delivery = "stored_only";
        if (!recipients.isEmpty()) {
            for (LearningDigestSender s : senders.orderedStream().toList()) {
                try { delivered.addAll(s.send(recipients, subject(summary), render(summary))); }
                catch (RuntimeException e) { log.warn("Digest sender {} failed: {}", s.channel(), e.toString()); }
            }
            delivery = delivered.isEmpty() ? "failed" : "sent";
        }
        String id = Ids.prefixed("ldg");
        jdbc.sql("""
            insert into learning_digest(digest_id, tenant_id, period_start, period_end, summary, delivered_to, delivery, created_by, created_at)
            values (:id, :t, :s, :e, :sum, :to, :d, :by, :at)
            """).param("id", id).param("t", TenantContext.require()).param("s", Timestamp.from(start)).param("e", Timestamp.from(end))
            .param("sum", Jsonb.of(summary)).param("to", Jsonb.of(delivered)).param("d", delivery).param("by", by).param("at", Timestamp.from(end)).update();
        events.digestPublished(id, Actor.parse(by), Map.of("periodStart", start.toString(), "periodEnd", end.toString(), "delivery", delivery, "recipients", delivered.size()));
        return new Digest(id, start, end, summary, delivered, delivery, by, end);
    }

    public List<Digest> recent(int limit) {
        return jdbc.sql("select digest_id, period_start, period_end, summary::text as summary, delivered_to::text as delivered_to, delivery, created_by, created_at from learning_digest where tenant_id = :t order by created_at desc limit :lim")
            .param("t", TenantContext.require()).param("lim", limit).query().listOfRows().stream().map(WeeklyLearningDigestService::hydrate).toList();
    }

    public Optional<Digest> latest() { return recent(1).stream().findFirst(); }

    Map<String, Object> summarise(Instant start, Instant end) {
        String t = TenantContext.require();
        Instant prevStart = start.minus(Duration.between(start, end));
        Map<String, Object> changed = new LinkedHashMap<>();
        changed.put("promoted", rows("""
            select proposal_id, target_ref, tier, approved_by, promoted_at from proposal where tenant_id = :t and status = 'PROMOTED' and promoted_at >= :s and promoted_at < :e order by promoted_at
            """, t, start, end));
        changed.put("rolledBack", rows("""
            select proposal_id, target_ref, rolled_back_reason, rolled_back_at from proposal where tenant_id = :t and rolled_back_at >= :s and rolled_back_at < :e order by rolled_back_at
            """, t, start, end));
        changed.put("learnerChanges", rows("""
            select type, aggregate_id, payload->>'learner' as learner, payload->>'to' as to_level, payload->>'reason' as reason, occurred_at
            from event where tenant_id = :t and aggregate_type = 'learning' and type in ('learner.autonomy_changed','learner.switched','learner.paused','learner.resumed','learning.kill_switch_changed')
              and occurred_at >= :s and occurred_at < :e order by occurred_at
            """, t, start, end));
        changed.put("factsLearned", rows("""
            select fact_key, count(*) as versions, count(distinct subject_id) as subjects from learned_fact where tenant_id = :t and created_at >= :s and created_at < :e group by fact_key order by versions desc
            """, t, start, end));

        Map<String, Object> impact = new LinkedHashMap<>();
        List<Map<String, Object>> rewards = new ArrayList<>();
        var cur = jdbc.sql("select decision_name, count(*) as n, avg(reward) as mean from episode where tenant_id = :t and status = 'MATURE' and reward is not null and matured_at >= :s and matured_at < :e group by decision_name")
            .param("t", t).param("s", Timestamp.from(start)).param("e", Timestamp.from(end)).query().listOfRows();
        for (var r : cur) {
            var prev = jdbc.sql("select avg(reward) from episode where tenant_id = :t and decision_name = :d and status = 'MATURE' and reward is not null and matured_at >= :s and matured_at < :e")
                .param("t", t).param("d", r.get("decision_name")).param("s", Timestamp.from(prevStart)).param("e", Timestamp.from(start)).query(Double.class).optional().orElse(null);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("decision", r.get("decision_name"));
            m.put("maturedEpisodes", r.get("n"));
            m.put("meanReward", round(((Number) r.get("mean")).doubleValue()));
            m.put("previousMeanReward", prev == null ? null : round(prev));
            m.put("change", prev == null ? null : round(((Number) r.get("mean")).doubleValue() - prev));
            rewards.add(m);
        }
        impact.put("rewardByDecision", rewards);
        impact.put("coverage", coverage.latest());

        Map<String, Object> pending = new LinkedHashMap<>();
        pending.put("proposalsByStatus", rows("select status, count(*) as proposals, min(created_at) as oldest from proposal where tenant_id = :t and status in ('DRAFT','EVALUATED','APPROVED','CANARY') group by status", t, null, null));
        pending.put("objectiveDrafts", jdbc.sql("select count(*) from objective_profile where tenant_id = :t and status = 'DRAFT'").param("t", t).query(Long.class).single());
        pending.put("openHypotheses", jdbc.sql("select count(*) from hypothesis where tenant_id = :t and status = 'OPEN'").param("t", t).query(Long.class).single());
        pending.put("pausedLearners", rows("select learner_key, paused_reason from learner where tenant_id = :t and (paused_reason is not null or not enabled)", t, null, null));

        Map<String, Object> s = new LinkedHashMap<>();
        s.put("periodStart", start.toString());
        s.put("periodEnd", end.toString());
        s.put("changed", changed);
        s.put("impact", impact);
        s.put("pending", pending);
        s.put("headline", headline(changed, pending));
        return s;
    }

    private List<Map<String, Object>> rows(String sql, String tenant, Instant start, Instant end) {
        var spec = jdbc.sql(sql).param("t", tenant);
        if (start != null) spec = spec.param("s", Timestamp.from(start)).param("e", Timestamp.from(end));
        List<Map<String, Object>> out = new ArrayList<>();
        for (var r : spec.query().listOfRows()) {
            Map<String, Object> m = new LinkedHashMap<>();
            r.forEach((k, v) -> m.put(k, v instanceof Timestamp ts ? ts.toInstant().toString() : v));
            out.add(m);
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    static String headline(Map<String, Object> changed, Map<String, Object> pending) {
        int promoted = ((List<?>) changed.get("promoted")).size();
        int rolledBack = ((List<?>) changed.get("rolledBack")).size();
        long waiting = ((List<Map<String, Object>>) pending.get("proposalsByStatus")).stream().mapToLong(r -> ((Number) r.get("proposals")).longValue()).sum();
        return promoted + " change(s) promoted, " + rolledBack + " rolled back, " + waiting + " waiting for review this week.";
    }

    static String subject(Map<String, Object> summary) { return "Weekly learning digest: " + summary.get("headline"); }

    @SuppressWarnings("unchecked")
    static String render(Map<String, Object> s) {
        StringBuilder b = new StringBuilder();
        b.append(s.get("headline")).append("\n\nPeriod: ").append(s.get("periodStart")).append(" to ").append(s.get("periodEnd")).append("\n\n");
        Map<String, Object> changed = (Map<String, Object>) s.get("changed");
        b.append("What changed\n");
        for (Map<String, Object> p : (List<Map<String, Object>>) changed.get("promoted"))
            b.append("- Promoted ").append(p.get("target_ref")).append(" (").append(p.get("tier")).append(", ").append(p.get("approved_by") == null ? "automatic" : "approved by " + p.get("approved_by")).append(")\n");
        for (Map<String, Object> p : (List<Map<String, Object>>) changed.get("rolledBack"))
            b.append("- Rolled back ").append(p.get("target_ref")).append(": ").append(p.get("rolled_back_reason")).append("\n");
        b.append("\nMeasured impact\n");
        for (Map<String, Object> r : (List<Map<String, Object>>) ((Map<String, Object>) s.get("impact")).get("rewardByDecision"))
            b.append("- ").append(r.get("decision")).append(": mean reward ").append(r.get("meanReward")).append(r.get("change") == null ? "" : " (" + r.get("change") + " vs last week)").append(" over ").append(r.get("maturedEpisodes")).append(" matured episodes\n");
        Map<String, Object> pending = (Map<String, Object>) s.get("pending");
        b.append("\nWhat is pending\n");
        for (Map<String, Object> r : (List<Map<String, Object>>) pending.get("proposalsByStatus")) b.append("- ").append(r.get("proposals")).append(" proposal(s) ").append(r.get("status")).append("\n");
        b.append("- ").append(pending.get("objectiveDrafts")).append(" objective draft(s) awaiting approval\n");
        b.append("- ").append(pending.get("openHypotheses")).append(" open hypothesis(es)\n");
        return b.toString();
    }

    private static double round(double v) { return Math.round(v * 10_000) / 10_000.0; }

    private static final com.fasterxml.jackson.core.type.TypeReference<List<String>> STRINGS = new com.fasterxml.jackson.core.type.TypeReference<>() {};

    private static Digest hydrate(Map<String, Object> r) {
        return new Digest((String) r.get("digest_id"), ((Timestamp) r.get("period_start")).toInstant(), ((Timestamp) r.get("period_end")).toInstant(),
            Json.readMap((String) r.get("summary")), Json.read((String) r.get("delivered_to"), STRINGS), (String) r.get("delivery"), (String) r.get("created_by"),
            ((Timestamp) r.get("created_at")).toInstant());
    }
}
