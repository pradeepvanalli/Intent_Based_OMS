package ai.intentoms.learning.learner;

import ai.intentoms.decision.record.DecisionRecordRepository;
import ai.intentoms.learning.model.FactKey;
import ai.intentoms.learning.model.LearnedFact;
import ai.intentoms.learning.model.Segment;
import ai.intentoms.learning.model.SubjectType;
import ai.intentoms.learning.model.TypedValue;
import ai.intentoms.learning.store.SegmentRepository;
import ai.intentoms.platform.ids.Ids;
import ai.intentoms.platform.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * CI-02: statistics-defined customer segmentation. <b>Scope note:</b> this is a quantile-bucket segmentation on one
 * feature at a time (split tolerance first), not a multi-feature k-means clustering — a deliberate, documented
 * simplification given no ML/clustering library is on this stack. Terciles (low/medium/high) on the already-learned
 * {@code SPLIT_TOLERANCE} fact, with a minimum segment size (default 30) below which a bucket isn't published.
 * Naming the segment with a typed label/description is the analyst LLM's job once this produces a raw bucket — this
 * writes the segment with a generic label the analyst can relabel, not a final human-facing name.
 */
@Component
public class CiSegmentDiscovery {
    private static final Logger log = LoggerFactory.getLogger(CiSegmentDiscovery.class);

    private final ai.intentoms.learning.store.LearnedFactRepository facts;
    private final SegmentRepository segments;
    private final DecisionRecordRepository decisionRecords;

    @Value("${oms.learning.segment-min-size:30}") private int minSize;

    public CiSegmentDiscovery(ai.intentoms.learning.store.LearnedFactRepository facts, SegmentRepository segments, DecisionRecordRepository decisionRecords) {
        this.facts = facts;
        this.segments = segments;
        this.decisionRecords = decisionRecords;
    }

    @Scheduled(fixedDelayString = "${oms.learning.segment-sweep-ms:86400000}", initialDelayString = "${oms.learning.segment-sweep-ms:86400000}")
    public void scheduled() {
        try { sweepOnce(); } catch (RuntimeException e) { log.error("Segment discovery failed: {}", e.toString()); }
    }

    public void sweepOnce() {
        List<String> tenants = TenantContext.current().map(List::of).orElseGet(() -> decisionRecords.tenantsWithRecords(Instant.now().minusSeconds(30L * 86400)));
        for (String tenant : tenants) TenantContext.runAs(tenant, this::discoverForTenant);
    }

    private void discoverForTenant() {
        List<LearnedFact> splitTolerance = facts.activeByKey(SubjectType.CUSTOMER, FactKey.SPLIT_TOLERANCE, 10_000);
        if (splitTolerance.size() < minSize) return;
        List<double[]> scored = new ArrayList<>();
        for (int i = 0; i < splitTolerance.size(); i++) {
            LearnedFact f = splitTolerance.get(i);
            if (f.value() instanceof TypedValue.NumberValue n) scored.add(new double[]{n.value(), i});
        }
        scored.sort((a, b) -> Double.compare(a[0], b[0]));
        int n = scored.size();
        int t1 = n / 3, t2 = 2 * n / 3;
        writeBucket("split_tolerance_low", "Customers with low observed split tolerance", splitTolerance, scored.subList(0, t1));
        writeBucket("split_tolerance_medium", "Customers with medium observed split tolerance", splitTolerance, scored.subList(t1, t2));
        writeBucket("split_tolerance_high", "Customers with high observed split tolerance", splitTolerance, scored.subList(t2, n));
    }

    private void writeBucket(String label, String description, List<LearnedFact> all, List<double[]> members) {
        if (members.size() < minSize) return;
        List<String> customerIds = members.stream().map(m -> all.get((int) m[1]).subjectId()).toList();
        Map<String, Object> definition = new LinkedHashMap<>();
        definition.put("feature", "SPLIT_TOLERANCE");
        definition.put("method", "tercile");
        definition.put("customerIds", customerIds);
        segments.insert(new Segment(Ids.prefixed("seg"), 1, label, description, definition, customerIds.size(), "system:ci_segment_discovery", Instant.now()));
        log.info("Discovered segment {} with {} members", label, customerIds.size());
    }
}
