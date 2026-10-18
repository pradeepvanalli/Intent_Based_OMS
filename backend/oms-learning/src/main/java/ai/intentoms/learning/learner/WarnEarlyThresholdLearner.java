package ai.intentoms.learning.learner;

import ai.intentoms.decision.model.DecisionRecord;
import ai.intentoms.decision.record.DecisionRecordRepository;
import ai.intentoms.learning.governance.GovernedLearner;
import ai.intentoms.learning.governance.LearnerDescriptor;
import ai.intentoms.learning.governance.LearnerGovernance;
import ai.intentoms.learning.model.Episode;
import ai.intentoms.learning.model.FactKey;
import ai.intentoms.learning.model.OutcomeData;
import ai.intentoms.learning.model.OutcomeRecord;
import ai.intentoms.learning.model.SubjectType;
import ai.intentoms.learning.store.EpisodeRepository;
import ai.intentoms.learning.store.OutcomeRepository;
import ai.intentoms.platform.config.TenantConfigStore;
import ai.intentoms.platform.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * CX-03: the breach-risk score at which to warn a customer before the promise is missed. For every order with an
 * {@code sla_breach_risk} episode and a delivery outcome, takes the highest score the order reached and whether it
 * arrived late; a threshold t would have warned every order whose highest score reached t. Each late order warned is
 * worth {@code cx.warn_early.benefit} (default 1), each on-time order warned costs {@code cx.warn_early.false_alarm_cost}
 * (default 0.3); the threshold with the best net value, when positive, is published as {@code WARN_EARLY_THRESHOLD} on
 * {@code DECISION:sla_breach_risk}. The breach-risk scorer uses it through {@code ParameterResolver}
 * ({@code monitoring.warn_early_threshold}) to trigger the proactive delay notice.
 */
@Component
public class WarnEarlyThresholdLearner implements GovernedLearner {
    public static final String KEY = "warn_early";
    public static final String PARAMETER = "monitoring.warn_early_threshold";
    public static final String STATIC_KEY = "cx.warn_early_threshold";
    public static final String DECISION = "sla_breach_risk";
    private static final Logger log = LoggerFactory.getLogger(WarnEarlyThresholdLearner.class);

    private final EpisodeRepository episodes;
    private final OutcomeRepository outcomes;
    private final DecisionRecordRepository decisionRecords;
    private final FulfilmentFacts facts;
    private final TenantConfigStore config;
    private final LearnerGovernance governance;

    @Value("${oms.learning.cx03.window-days:90}") private int windowDays = 90;
    @Value("${oms.learning.cx03.min-delivered:30}") private int minDelivered = 30;

    public WarnEarlyThresholdLearner(EpisodeRepository episodes, OutcomeRepository outcomes, DecisionRecordRepository decisionRecords, FulfilmentFacts facts,
                                     TenantConfigStore config, LearnerGovernance governance) {
        this.episodes = episodes;
        this.outcomes = outcomes;
        this.decisionRecords = decisionRecords;
        this.facts = facts;
        this.config = config;
        this.governance = governance;
    }

    @Scheduled(fixedDelayString = "${oms.learning.cx03.sweep-ms:86400000}", initialDelayString = "${oms.learning.cx03.sweep-ms:920000}")
    public void scheduled() {
        try { sweepOnce(); } catch (RuntimeException e) { log.error("Warn-early sweep failed: {}", e.toString()); }
    }

    public int sweepOnce() {
        List<String> tenants = TenantContext.current().map(List::of).orElseGet(() -> decisionRecords.tenantsWithRecords(Instant.now().minus(Duration.ofDays(windowDays))));
        int n = 0;
        for (String tenant : tenants) n += TenantContext.supplyAs(tenant, this::sweepTenant);
        return n;
    }

    /** One order: the highest risk score it reached and whether it arrived late. */
    record Observed(int maxScore, boolean late) {}

    private int sweepTenant() {
        if (!governance.begin(KEY).mayRun()) return 0;
        governance.recordRun(KEY);
        List<Observed> observed = new ArrayList<>();
        for (Episode ep : episodes.forDecisionSince(DECISION, Instant.now().minus(Duration.ofDays(windowDays)), 10_000)) {
            Boolean late = late(outcomes.forEpisode(ep.episodeId()));
            if (late == null || ep.orderId() == null) continue;
            int max = decisionRecords.forSubject("order", ep.orderId()).stream().filter(r -> DECISION.equals(r.decision()))
                .mapToInt(WarnEarlyThresholdLearner::score).max().orElse(-1);
            if (max >= 0) observed.add(new Observed(max, late));
        }
        if (observed.size() < minDelivered || observed.stream().filter(Observed::late).count() < 3) return 0;
        Integer best = bestThreshold(observed, config.getDouble("cx.warn_early.benefit", 1.0), config.getDouble("cx.warn_early.false_alarm_cost", 0.3));
        if (best == null) return 0;
        return facts.number(SubjectType.DECISION, DECISION, FactKey.WARN_EARLY_THRESHOLD, best, observed.size(), KEY, 0.5) ? 1 : 0;
    }

    /** The threshold (1..4) with the highest benefit x late-warned - cost x on-time-warned, or null when none is positive. */
    static Integer bestThreshold(List<Observed> observed, double benefit, double falseAlarmCost) {
        Integer best = null;
        double bestValue = 0;
        for (int t = 1; t <= 4; t++) {
            double value = 0;
            for (Observed o : observed) if (o.maxScore() >= t) value += o.late() ? benefit : -falseAlarmCost;
            if (value > bestValue + 1e-9) { bestValue = value; best = t; }
        }
        return best;
    }

    private static Boolean late(List<OutcomeRecord> outs) {
        for (OutcomeRecord o : outs) if (o.payload() instanceof OutcomeData.DeliveryOutcome d) return !d.onTime() || d.lost();
        return null;
    }

    private static int score(DecisionRecord r) {
        Object v = r.value();
        if (v instanceof Number n) return (int) Math.round(n.doubleValue());
        try { return v == null ? -1 : (int) Math.round(Double.parseDouble(String.valueOf(v))); } catch (NumberFormatException e) { return -1; }
    }

    @Override public boolean runnable() { return true; }

    @Override public void runNow() { sweepOnce(); }

    @Override
    public LearnerDescriptor descriptor() {
        return LearnerDescriptor.of(KEY, "Early delay warning", "Learns how risky an order must look before telling the customer about a likely delay.",
            LearnerDescriptor.Loop.FAST, "CX-03").readsOutcomesOf(DECISION).feeds(PARAMETER);
    }
}
