package ai.intentoms.learning.learner;

import ai.intentoms.decision.record.DecisionRecordRepository;
import ai.intentoms.learning.governance.GovernedLearner;
import ai.intentoms.learning.governance.LearnerDescriptor;
import ai.intentoms.learning.governance.LearnerGovernance;
import ai.intentoms.learning.hooks.NodeReliabilityFeatures;
import ai.intentoms.learning.model.FactKey;
import ai.intentoms.learning.model.SubjectType;
import ai.intentoms.platform.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * FS-05: per node, how likely an order is to miss its promise given the node's backlog when it was assigned. Fitted
 * daily from delivered assignments (backlog at sourcing time vs on time) as a monotone curve and published as
 * {@code NODE_BACKLOG_BREACH_CURVE}; read live by sourcing ({@code sourcing_node_score} candidates'
 * {@code breach_probability}) and by the SLA breach-risk scorer, each through {@code ParameterResolver}
 * ({@code sourcing.node_backlog_breach}).
 */
@Component
public class BacklogBreachCurveLearner implements GovernedLearner {
    public static final String KEY = "node_backlog_breach";
    private static final Logger log = LoggerFactory.getLogger(BacklogBreachCurveLearner.class);

    private final NodeAssignmentRepository assignments;
    private final FulfilmentFacts facts;
    private final DecisionRecordRepository decisionRecords;
    private final LearnerGovernance governance;

    @Value("${oms.learning.fs05.window-days:60}") private int windowDays = 60;
    @Value("${oms.learning.fs05.min-per-bucket:10}") private int minPerBucket = 10;

    public BacklogBreachCurveLearner(NodeAssignmentRepository assignments, FulfilmentFacts facts, DecisionRecordRepository decisionRecords, LearnerGovernance governance) {
        this.assignments = assignments;
        this.facts = facts;
        this.decisionRecords = decisionRecords;
        this.governance = governance;
    }

    @Scheduled(fixedDelayString = "${oms.learning.fs05.sweep-ms:86400000}", initialDelayString = "${oms.learning.fs05.sweep-ms:900000}")
    public void scheduled() {
        try { sweepOnce(); } catch (RuntimeException e) { log.error("Backlog-to-breach sweep failed: {}", e.toString()); }
    }

    public int sweepOnce() {
        List<String> tenants = TenantContext.current().map(List::of).orElseGet(() -> decisionRecords.tenantsWithRecords(Instant.now().minus(Duration.ofDays(windowDays))));
        int n = 0;
        for (String tenant : tenants) n += TenantContext.supplyAs(tenant, this::sweepTenant);
        return n;
    }

    private int sweepTenant() {
        if (!governance.begin(KEY).mayRun()) return 0;
        governance.recordRun(KEY);
        Instant since = Instant.now().minus(Duration.ofDays(windowDays));
        int written = 0;
        for (String nodeId : assignments.nodesDeliveredSince(since)) {
            List<double[]> samples = assignments.backlogOutcomes(nodeId, since);
            List<BacklogBreachCurve.Point> curve = BacklogBreachCurve.fit(samples, 5, minPerBucket);
            if (curve.size() < 2) continue;
            if (facts.list(SubjectType.NODE, nodeId, FactKey.NODE_BACKLOG_BREACH_CURVE, BacklogBreachCurve.encode(curve), samples.size(), KEY)) written++;
        }
        return written;
    }

    @Override public boolean runnable() { return true; }

    @Override public void runNow() { sweepOnce(); }

    @Override
    public LearnerDescriptor descriptor() {
        return LearnerDescriptor.of(KEY, "Backlog breach risk", "Learns how much each node's backlog raises the chance of missing the promised date.",
            LearnerDescriptor.Loop.FAST, "FS-05").readsOutcomesOf("sourcing_node_score").feeds(NodeReliabilityFeatures.BACKLOG_BREACH);
    }
}
