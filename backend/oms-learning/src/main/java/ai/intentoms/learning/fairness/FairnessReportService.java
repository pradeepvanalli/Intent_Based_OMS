package ai.intentoms.learning.fairness;

import ai.intentoms.decision.model.DecisionRecord;
import ai.intentoms.decision.record.DecisionRecordRepository;
import ai.intentoms.learning.model.FactKey;
import ai.intentoms.learning.model.LearnedFact;
import ai.intentoms.learning.model.SubjectType;
import ai.intentoms.learning.model.TypedValue;
import ai.intentoms.learning.store.LearnedFactRepository;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * GV-07: segment-level outcome parity. No feature in the typed decision context is, or has ever been, a protected
 * attribute (ethnicity/gender/age/religion/disability have no field anywhere in {@code DecisionContext}, the
 * learned-fact catalog, or any proposal's changeset — confirmed by inspection, not assumed) — this report's job is
 * the second half of GV-07: showing whether outcomes are nonetheless skewed across customer groups, which a model
 * can still do even without being handed a protected attribute directly.
 *
 * <p><b>Grouping, stated plainly</b>: there is no persisted "customer belongs to segment X" membership table today
 * — {@code Segment} rows describe a discovered cluster's definition, not a membership list. This report groups
 * customers by their active {@code SPLIT_TOLERANCE} learned-fact value instead (low/medium/high tercile), the one
 * behavioural dimension already being learned per customer (CI-01) — a real, defensible grouping, not the full
 * multi-feature segmentation CI-02 eventually aims for. Swap the grouping key here once customer→segment membership
 * exists as its own queryable fact.
 */
@Service
public class FairnessReportService {
    private final LearnedFactRepository facts;
    private final DecisionRecordRepository decisionRecords;

    public FairnessReportService(LearnedFactRepository facts, DecisionRecordRepository decisionRecords) {
        this.facts = facts;
        this.decisionRecords = decisionRecords;
    }

    public record GroupParity(String group, int customers, double actBandRate, double overrideRate) {}
    public record ParityReport(String decision, List<GroupParity> groups, double tenantActBandRate, double tenantOverrideRate, List<String> flags) {}

    public ParityReport report(String decisionName, int sampleCustomers) {
        List<LearnedFact> tolerance = facts.activeByKey(SubjectType.CUSTOMER, FactKey.SPLIT_TOLERANCE, Math.max(30, sampleCustomers));
        List<Double> values = tolerance.stream().map(f -> f.value() instanceof TypedValue.NumberValue nv ? nv.value() : null).filter(java.util.Objects::nonNull).sorted().toList();
        double lowCut = pct(values, 0.33), highCut = pct(values, 0.67);

        Map<String, List<String>> groups = new LinkedHashMap<>(Map.of("low_tolerance", new java.util.ArrayList<>(), "medium_tolerance", new java.util.ArrayList<>(), "high_tolerance", new java.util.ArrayList<>()));
        for (LearnedFact f : tolerance) {
            if (!(f.value() instanceof TypedValue.NumberValue nv)) continue;
            String g = nv.value() <= lowCut ? "low_tolerance" : nv.value() >= highCut ? "high_tolerance" : "medium_tolerance";
            groups.get(g).add(f.subjectId());
        }

        Instant since = Instant.now().minus(Duration.ofDays(90));
        List<GroupParity> result = new java.util.ArrayList<>();
        int totalAct = 0, totalOverridden = 0, totalRecords = 0;
        for (var e : groups.entrySet()) {
            int act = 0, overridden = 0, recs = 0;
            for (String customerId : e.getValue()) {
                for (DecisionRecord r : decisionRecords.forSubject("customer", customerId)) {
                    if (!decisionName.equals(r.decision()) || r.createdAt().isBefore(since)) continue;
                    recs++;
                    if (r.band() == ai.intentoms.decision.model.Band.ACT) act++;
                    if (r.overriddenAt() != null) overridden++;
                }
            }
            totalAct += act; totalOverridden += overridden; totalRecords += recs;
            result.add(new GroupParity(e.getKey(), e.getValue().size(), rate(act, recs), rate(overridden, recs)));
        }
        double tenantAct = rate(totalAct, totalRecords), tenantOverride = rate(totalOverridden, totalRecords);
        List<String> flags = new java.util.ArrayList<>();
        for (GroupParity g : result) {
            if (g.customers() < 10) continue; // too few to judge
            if (Math.abs(g.actBandRate() - tenantAct) > 0.15) flags.add(g.group() + "'s act-band rate (" + g.actBandRate() + ") diverges from the tenant average (" + tenantAct + ") by more than 15 points");
            if (Math.abs(g.overrideRate() - tenantOverride) > 0.10) flags.add(g.group() + "'s override rate (" + g.overrideRate() + ") diverges from the tenant average (" + tenantOverride + ") by more than 10 points");
        }
        return new ParityReport(decisionName, result, tenantAct, tenantOverride, flags);
    }

    private static double pct(List<Double> sorted, double p) {
        if (sorted.isEmpty()) return 0;
        int i = Math.min(sorted.size() - 1, (int) Math.round(p * (sorted.size() - 1)));
        return sorted.get(i);
    }

    private static double rate(int n, int total) { return total == 0 ? 0 : Math.round((n / (double) total) * 1000) / 1000.0; }
}
