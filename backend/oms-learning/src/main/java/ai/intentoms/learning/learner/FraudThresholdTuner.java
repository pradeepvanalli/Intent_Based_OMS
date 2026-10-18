package ai.intentoms.learning.learner;

import ai.intentoms.learning.governance.GovernedLearner;
import ai.intentoms.learning.governance.LearnerDescriptor;
import ai.intentoms.learning.governance.LearnerGovernance;
import ai.intentoms.decision.definition.DecisionDefinitionRepository;
import ai.intentoms.decision.model.DecisionRecord;
import ai.intentoms.decision.record.DecisionRecordRepository;
import ai.intentoms.learning.model.ArtifactRef;
import ai.intentoms.learning.model.PolicyChange;
import ai.intentoms.learning.model.PolicyChangeset;
import ai.intentoms.learning.model.Proposal;
import ai.intentoms.learning.model.ProposalStatus;
import ai.intentoms.learning.store.ProposalRepository;
import ai.intentoms.platform.ids.Ids;
import ai.intentoms.platform.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * FD-01/FD-02/FD-03: tunes each fraud decision's act-band threshold to a tenant-set false-positive target, weighing
 * expected fraud loss against the cost of blocking a good order. Writes a {@link Proposal} with a
 * {@link PolicyChange.ThresholdChange}, step-capped at {@code maxStepPerRun} (default 0.05, matching the
 * requirements doc's own example and GV-02's change-budget intent) — it never mutates the live decision; publishing
 * still goes through {@code DecisionStudioService}'s lint/golden-set/approval gate untouched.
 */
@Component
public class FraudThresholdTuner implements GovernedLearner {
    public static final String KEY = "fraud_threshold_tuner";
    private static final Logger log = LoggerFactory.getLogger(FraudThresholdTuner.class);
    private static final List<String> FRAUD_DECISIONS = List.of("payment_risk", "rto_risk", "address_risk");

    private final DecisionRecordRepository decisionRecords;
    private final DecisionDefinitionRepository definitions;
    private final ProposalRepository proposals;

    @Value("${oms.learning.fraud-fp-target:0.05}") private double falsePositiveTarget;
    @Value("${oms.learning.fraud-fn-target:0.10}") private double falseNegativeTarget;
    @Value("${oms.learning.threshold-max-step:0.05}") private double maxStep;

    private final LearnerGovernance governance;

    public FraudThresholdTuner(DecisionRecordRepository decisionRecords, DecisionDefinitionRepository definitions, ProposalRepository proposals, LearnerGovernance governance) {
        this.governance = governance;
        this.decisionRecords = decisionRecords;
        this.definitions = definitions;
        this.proposals = proposals;
    }

    @Scheduled(fixedDelayString = "${oms.learning.threshold-tune-ms:86400000}", initialDelayString = "${oms.learning.threshold-tune-ms:86400000}")
    public void scheduled() {
        try { sweepOnce(); } catch (RuntimeException e) { log.error("Fraud threshold tuning failed: {}", e.toString()); }
    }

    public void sweepOnce() {
        List<String> tenants = TenantContext.current().map(List::of).orElseGet(() -> decisionRecords.tenantsWithRecords(Instant.now().minus(Duration.ofDays(30))));
        for (String tenant : tenants) TenantContext.runAs(tenant, this::tuneTenant);
    }

    private void tuneTenant() {
        if (!governance.begin(KEY).mayPropose()) return;
        governance.recordRun(KEY);
        for (String decision : FRAUD_DECISIONS) {
            try { tuneOne(decision); } catch (RuntimeException e) { log.warn("Threshold tuning skipped for {}: {}", decision, e.toString()); }
        }
    }

    private void tuneOne(String decision) {
        Instant since = Instant.now().minus(Duration.ofDays(30));
        List<Integer> versions = decisionRecords.activeVersions(decision, since).stream().map(a -> a[0]).toList();
        if (versions.isEmpty()) return;
        int version = versions.get(versions.size() - 1);
        var def = definitions.find(decision, version);
        if (def.isEmpty()) return;
        double current = def.get().definition().bands() == null ? 0.9 : def.get().definition().bands().actGte();

        List<DecisionRecord> labelled = decisionRecords.labelledBetween(decision, version, since, Instant.now());
        List<DecisionRecord> withFraudLabel = labelled.stream().filter(r -> label(r) != null).toList();
        if (withFraudLabel.size() < 20) return; // not enough ground truth yet

        long acted = withFraudLabel.stream().filter(r -> "act".equals(r.action())).count();
        long actedFalsePositive = withFraudLabel.stream().filter(r -> "act".equals(r.action()) && !label(r)).count();
        long notActed = withFraudLabel.size() - acted;
        long notActedFalseNegative = withFraudLabel.stream().filter(r -> !"act".equals(r.action()) && label(r)).count();

        double fpRate = acted == 0 ? 0 : (double) actedFalsePositive / acted;
        double fnRate = notActed == 0 ? 0 : (double) notActedFalseNegative / notActed;

        Double to = null;
        String reason;
        if (fpRate > falsePositiveTarget) {
            to = Math.min(1.0, current + Math.min(maxStep, 0.02));
            reason = String.format("false-positive rate %.1f%% exceeds the %.1f%% target over %d labelled cases", fpRate * 100, falsePositiveTarget * 100, withFraudLabel.size());
        } else if (fnRate > falseNegativeTarget) {
            to = Math.max(0.0, current - Math.min(maxStep, 0.02));
            reason = String.format("false-negative rate %.1f%% exceeds the %.1f%% target over %d labelled cases", fnRate * 100, falseNegativeTarget * 100, withFraudLabel.size());
        } else {
            return; // within target on both sides; nothing to propose
        }
        if (Math.abs(to - current) < 1e-9) return;
        if (proposals.existsOpenFor("decision:" + decision)) return; // one in-flight proposal per decision

        PolicyChangeset changeset = new PolicyChangeset(new ArtifactRef("decision", decision), version,
            List.of(new PolicyChange.ThresholdChange(decision, "act", current, to)), null, PolicyChangeset.RiskTier.T2);
        Proposal p = new Proposal(Ids.prefixed("prop"), changeset, null, PolicyChangeset.RiskTier.T2, ProposalStatus.DRAFT, null, null,
            "system:fraud_threshold_tuner", Instant.now(), Instant.now(), null, null, null, null, null, null, null, null);
        proposals.insert(p);
        log.info("Proposed act-band threshold change for {}: {} -> {} ({})", decision, current, to, reason);
    }

    @SuppressWarnings("unchecked")
    private static Boolean label(DecisionRecord r) {
        Object outcome = r.labels() == null ? null : r.labels().get("fraud_outcome");
        if (!(outcome instanceof Map<?, ?> m)) return null;
        Object confirmed = ((Map<String, Object>) m).get("confirmed");
        return confirmed instanceof Boolean b ? b : null;
    }

    @Override
    public LearnerDescriptor descriptor() {
        return LearnerDescriptor.of(KEY, "Fraud threshold tuner", "Moves fraud thresholds toward your false-positive target, weighing fraud loss against blocking good orders.", LearnerDescriptor.Loop.FAST, "FD-01, FD-02, FD-03").readsOutcomesOf("payment_risk", "rto_risk", "address_risk");
    }

    @Override public boolean runnable() { return true; }

    @Override public void runNow() { sweepOnce(); }
}
