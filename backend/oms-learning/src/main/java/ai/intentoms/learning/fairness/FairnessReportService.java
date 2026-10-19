package ai.intentoms.learning.fairness;

import ai.intentoms.learning.governance.FeatureAllowlist;
import ai.intentoms.learning.governance.LearnerDescriptor;
import ai.intentoms.learning.governance.LearnerRegistry;
import ai.intentoms.learning.model.FactKey;
import ai.intentoms.learning.model.LearnedFact;
import ai.intentoms.learning.model.Segment;
import ai.intentoms.learning.model.SubjectType;
import ai.intentoms.learning.model.TypedValue;
import ai.intentoms.learning.store.LearnedFactRepository;
import ai.intentoms.learning.store.SegmentRepository;
import ai.intentoms.platform.tenant.TenantContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * GV-07: protected attributes are excluded and segment-level outcome parity is reported.
 *
 * <p><b>Groups</b> are the customer segments the CI-02 discovery assigned ({@code SEGMENT_MEMBERSHIP} facts, labelled
 * with the segment's name). Until any customer has a membership, customers are grouped by their learned split
 * tolerance (low / medium / high tercile) instead, and the report says which grouping it used.
 *
 * <p><b>Outcomes per group</b> are the decision's LIVE records over the last 90 days that concern a member: records
 * whose subject is the customer, and records about one of the customer's orders. Per group: act-band rate and override
 * rate, flagged when they diverge from the tenant average by more than 15 / 10 points (groups under 10 customers are
 * not judged).
 *
 * <p><b>Feature audit</b>: for every learner that learns from this decision, its declared features are checked
 * against the protected-attribute list ({@link FeatureAllowlist}); any hit is a flag.
 */
@Service
public class FairnessReportService {
    private static final int MAX_MEMBERS_PER_GROUP = 2000;

    private final LearnedFactRepository facts;
    private final SegmentRepository segments;
    private final LearnerRegistry registry;
    private final JdbcClient jdbc;

    public FairnessReportService(LearnedFactRepository facts, SegmentRepository segments, LearnerRegistry registry, JdbcClient jdbc) {
        this.facts = facts;
        this.segments = segments;
        this.registry = registry;
        this.jdbc = jdbc;
    }

    /**
     * One customer group: decision parity (act-band and override rates) and outcome parity (GV-07: matured episode
     * reward and on-time rate for this decision's episodes on the group's orders; null when there are none).
     */
    public record GroupParity(String group, String label, int customers, int decisions, double actBandRate, double overrideRate,
                              int episodes, Double meanReward, Double onTimeRate) {}
    public record LearnerFeatureAudit(String learner, List<String> declaredFeatures, List<String> protectedFeatures) {}
    public record ParityReport(String decision, String grouping, List<GroupParity> groups, double tenantActBandRate, double tenantOverrideRate,
                               List<LearnerFeatureAudit> featureAudit, List<String> flags) {}

    public ParityReport report(String decisionName, int days) {
        Map<String, List<String>> groups = new LinkedHashMap<>();
        Map<String, String> labels = new LinkedHashMap<>();
        String grouping = groupBySegment(groups, labels) ? "segment" : groupBySplitTolerance(groups, labels);

        Instant since = Instant.now().minus(Duration.ofDays(Math.max(1, days)));
        boolean ordersTable = jdbc.sql("select to_regclass('orders') is not null").query(Boolean.class).single();
        List<GroupParity> result = new ArrayList<>();
        long totalAct = 0, totalOverridden = 0, totalRecords = 0, totalEpisodes = 0;
        double onTimeSum = 0;
        for (var e : groups.entrySet()) {
            long[] c = counts(decisionName, e.getValue(), since, ordersTable);
            totalRecords += c[0]; totalAct += c[1]; totalOverridden += c[2];
            Object[] o = ordersTable ? outcomes(decisionName, e.getValue(), since) : new Object[]{0L, null, null};
            long n = (Long) o[0];
            if (n > 0 && o[2] != null) { totalEpisodes += n; onTimeSum += n * (Double) o[2]; }
            result.add(new GroupParity(e.getKey(), labels.getOrDefault(e.getKey(), e.getKey()), e.getValue().size(), (int) c[0], rate(c[1], c[0]), rate(c[2], c[0]),
                (int) n, (Double) o[1], (Double) o[2]));
        }
        double tenantAct = rate(totalAct, totalRecords), tenantOverride = rate(totalOverridden, totalRecords);
        Double tenantOnTime = totalEpisodes == 0 ? null : Math.round(onTimeSum / totalEpisodes * 1000) / 1000.0;
        List<String> flags = new ArrayList<>();
        for (GroupParity g : result) {
            if (g.customers() < 10) continue;
            if (g.decisions() > 0) {
                if (Math.abs(g.actBandRate() - tenantAct) > 0.15) flags.add(g.label() + ": act-band rate " + g.actBandRate() + " vs tenant " + tenantAct + " (more than 15 points apart)");
                if (Math.abs(g.overrideRate() - tenantOverride) > 0.10) flags.add(g.label() + ": override rate " + g.overrideRate() + " vs tenant " + tenantOverride + " (more than 10 points apart)");
            }
            if (tenantOnTime != null && g.onTimeRate() != null && g.episodes() >= 10 && Math.abs(g.onTimeRate() - tenantOnTime) > 0.10)
                flags.add(g.label() + ": on-time rate " + g.onTimeRate() + " vs tenant " + tenantOnTime + " (more than 10 points apart)");
        }
        List<LearnerFeatureAudit> audit = featureAudit(decisionName);
        for (LearnerFeatureAudit a : audit) if (!a.protectedFeatures().isEmpty()) flags.add("learner " + a.learner() + " declares protected features " + a.protectedFeatures());
        return new ParityReport(decisionName, grouping, result, tenantAct, tenantOverride, audit, flags);
    }

    /** Declared features of the learners that read this decision's outcomes (every learner when none does). */
    public List<LearnerFeatureAudit> featureAudit(String decisionName) {
        List<String> readers = registry.learnersReadingOutcomesOf(decisionName);
        List<LearnerFeatureAudit> out = new ArrayList<>();
        for (LearnerDescriptor d : registry.descriptors()) {
            if (!readers.isEmpty() && !readers.contains(d.key())) continue;
            out.add(new LearnerFeatureAudit(d.key(), d.features(), FeatureAllowlist.protectedNames(d.features())));
        }
        return out;
    }

    private boolean groupBySegment(Map<String, List<String>> groups, Map<String, String> labels) {
        List<LearnedFact> memberships = facts.activeByKey(SubjectType.CUSTOMER, FactKey.SEGMENT_MEMBERSHIP, 20_000);
        if (memberships.isEmpty()) return false;
        Map<String, String> segmentLabels = new LinkedHashMap<>();
        for (Segment s : segments.list()) segmentLabels.putIfAbsent(s.segmentId(), s.label());
        for (LearnedFact f : memberships) {
            if (!(f.value() instanceof TypedValue.TextValue tv) || tv.value() == null) continue;
            List<String> members = groups.computeIfAbsent(tv.value(), k -> new ArrayList<>());
            if (members.size() < MAX_MEMBERS_PER_GROUP) members.add(f.subjectId());
            labels.putIfAbsent(tv.value(), segmentLabels.getOrDefault(tv.value(), tv.value()));
        }
        return !groups.isEmpty();
    }

    private String groupBySplitTolerance(Map<String, List<String>> groups, Map<String, String> labels) {
        List<LearnedFact> tolerance = facts.activeByKey(SubjectType.CUSTOMER, FactKey.SPLIT_TOLERANCE, 20_000);
        List<Double> values = tolerance.stream().map(f -> f.value() instanceof TypedValue.NumberValue nv ? nv.value() : null).filter(Objects::nonNull).sorted().toList();
        double lowCut = pct(values, 0.33), highCut = pct(values, 0.67);
        for (String g : List.of("low_tolerance", "medium_tolerance", "high_tolerance")) groups.put(g, new ArrayList<>());
        labels.put("low_tolerance", "Low split tolerance");
        labels.put("medium_tolerance", "Medium split tolerance");
        labels.put("high_tolerance", "High split tolerance");
        for (LearnedFact f : tolerance) {
            if (!(f.value() instanceof TypedValue.NumberValue nv)) continue;
            String g = nv.value() <= lowCut ? "low_tolerance" : nv.value() >= highCut ? "high_tolerance" : "medium_tolerance";
            if (groups.get(g).size() < MAX_MEMBERS_PER_GROUP) groups.get(g).add(f.subjectId());
        }
        return "split_tolerance";
    }

    /** {episodes, mean reward, on-time rate} for this decision's rewarded episodes on these customers' orders. */
    private Object[] outcomes(String decision, List<String> customers, Instant since) {
        if (customers.isEmpty()) return new Object[]{0L, null, null};
        var row = jdbc.sql("""
            select count(*) as n, avg(e.reward) as mean_reward, avg((e.reward_components->>'otd')::double precision) as on_time
            from episode e
            where e.tenant_id = :t and e.decision_name = :d and e.reward is not null and e.created_at >= :since
              and e.order_id in (%s)
            """.formatted(ordersOf("e"))).param("t", TenantContext.require()).param("d", decision).param("since", Timestamp.from(since)).param("ids", customers).query().singleRow();
        long n = ((Number) row.get("n")).longValue();
        Double mean = row.get("mean_reward") instanceof Number m ? Math.round(m.doubleValue() * 1000) / 1000.0 : null;
        Double onTime = row.get("on_time") instanceof Number m ? Math.round(m.doubleValue() * 1000) / 1000.0 : null;
        return new Object[]{n, mean, onTime};
    }

    /** {total, act, overridden} LIVE records of the decision since {@code since} concerning these customers. */
    private long[] counts(String decision, List<String> customers, Instant since, boolean ordersTable) {
        if (customers.isEmpty()) return new long[]{0, 0, 0};
        String orderClause = ordersTable ? " or (d.subject_type = 'order' and d.subject_id in (" + ordersOf("d") + "))" : "";
        // A decision about a customer names the raw reference; the members are tokens, so the reference comes from their orders.
        String customerRefs = ordersTable ? " or d.subject_id in (select o.customer_ref from orders o join customer_order_ref r on r.tenant_id = o.tenant_id"
            + " and r.order_id = o.id where o.tenant_id = d.tenant_id and r.customer_ref in (:ids))" : "";
        var row = jdbc.sql("""
            select count(*) as total, count(*) filter (where d.band = 'ACT') as act, count(*) filter (where d.overridden_at is not null) as overridden
            from decision_record d
            where d.tenant_id = :t and d.decision = :d and d.mode = 'LIVE' and d.created_at >= :since
              and ((d.subject_type = 'customer' and (d.subject_id in (:ids)""" + customerRefs + "))" + orderClause + ")")
            .param("t", TenantContext.require()).param("d", decision).param("since", Timestamp.from(since)).param("ids", customers)
            .query().singleRow();
        return new long[]{((Number) row.get("total")).longValue(), ((Number) row.get("act")).longValue(), ((Number) row.get("overridden")).longValue()};
    }

    /**
     * NF-07: group members are customer learning subjects (vault tokens), so their orders are found through the order
     * attribution learning keeps by token ({@code customer_order_ref}), and through {@code orders.customer_ref} for an
     * erased customer's pseudonymised orders (the pseudonym is the token).
     */
    private static String ordersOf(String alias) {
        return "select r.order_id from customer_order_ref r where r.tenant_id = " + alias + ".tenant_id and r.customer_ref in (:ids)"
            + " union select o.id from orders o where o.tenant_id = " + alias + ".tenant_id and o.customer_ref in (:ids)";
    }

    private static double pct(List<Double> sorted, double p) {
        if (sorted.isEmpty()) return 0;
        int i = Math.min(sorted.size() - 1, (int) Math.round(p * (sorted.size() - 1)));
        return sorted.get(i);
    }

    private static double rate(long n, long total) { return total == 0 ? 0 : Math.round((n / (double) total) * 1000) / 1000.0; }
}
