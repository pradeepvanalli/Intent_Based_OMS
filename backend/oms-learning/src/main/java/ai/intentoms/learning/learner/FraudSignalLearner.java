package ai.intentoms.learning.learner;

import ai.intentoms.decision.model.DecisionRecord;
import ai.intentoms.decision.record.DecisionRecordRepository;
import ai.intentoms.learning.governance.GovernedLearner;
import ai.intentoms.learning.governance.LearnerDescriptor;
import ai.intentoms.learning.governance.LearnerGovernance;
import ai.intentoms.learning.hooks.FraudFactFeatures;
import ai.intentoms.learning.model.FactKey;
import ai.intentoms.learning.model.SubjectType;
import ai.intentoms.platform.config.TenantConfigStore;
import ai.intentoms.platform.json.Json;
import ai.intentoms.platform.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * FD-01: what the {@code payment_risk} signals have meant for this tenant. The workflow hands the decision raw counts
 * (extra cards in the last hour / day, other customers on the same address, device or card); this learner turns them
 * into learned features from labelled outcomes ({@code fraud_outcome}, provisional labels only after the chargeback
 * window, see {@link FraudLabels}):
 * <ul>
 *   <li>per signal and level ({@code 0}, {@code 1}, {@code 2-3}, {@code 4+}), the fraud rate of decisions that saw that
 *       level, shrunk toward the tenant's base rate with weight 10: {@code FRAUD_SIGNAL_RATE} for subject DECISION
 *       {@code payment_risk|<signal>|<level>};</li>
 *   <li>per agent, the mandate history: the fraud rate of the orders it placed under mandates, shrunk the same way:
 *       {@code FRAUD_SIGNAL_RATE} for subject AGENT.</li>
 * </ul>
 * {@code FraudFactFeatures} serves them to the decision through {@code ParameterResolver} once {@code fraud.signal_risk}
 * is promoted for live use. Needs {@value #MIN_LABELLED} usable labels; a level or agent needs {@value #MIN_LEVEL}.
 */
@Component
public class FraudSignalLearner implements GovernedLearner {
    public static final String KEY = "fraud_signals";
    private static final Logger log = LoggerFactory.getLogger(FraudSignalLearner.class);
    public static final String DECISION = "payment_risk";
    public static final List<String> SIGNALS = List.of("velocity_1h", "velocity_24h", "address_linkage_count", "device_linkage_count", "token_customer_count");
    static final int MIN_LABELLED = 30;
    static final int MIN_LEVEL = 5;
    static final double PRIOR_WEIGHT = 10;

    private final DecisionRecordRepository decisionRecords;
    private final FulfilmentFacts facts;
    private final LearnerGovernance governance;
    private final TenantConfigStore config;

    public FraudSignalLearner(DecisionRecordRepository decisionRecords, FulfilmentFacts facts, LearnerGovernance governance, TenantConfigStore config) {
        this.decisionRecords = decisionRecords;
        this.facts = facts;
        this.governance = governance;
        this.config = config;
    }

    public static String subject(String signal, String level) { return DECISION + "|" + signal + "|" + level; }

    /** Signal levels: 0, 1, 2-3, 4+. */
    public static String level(double count) { return count < 1 ? "0" : count < 2 ? "1" : count < 4 ? "2-3" : "4+"; }

    @Scheduled(fixedDelayString = "${oms.learning.fraud-signal-sweep-ms:86400000}", initialDelayString = "${oms.learning.fraud-signal-initial-ms:5400000}")
    public void scheduled() {
        try { sweepOnce(); } catch (RuntimeException e) { log.error("Fraud signal learning failed: {}", e.toString()); }
    }

    public void sweepOnce() {
        List<String> tenants = TenantContext.current().map(List::of).orElseGet(() -> decisionRecords.tenantsWithRecords(Instant.now().minus(Duration.ofDays(400))));
        for (String tenant : tenants) {
            try { TenantContext.runAs(tenant, this::learnTenant); } catch (RuntimeException e) { log.warn("Fraud signal learning failed for tenant {}: {}", tenant, e.toString()); }
        }
    }

    public record Rate(int n, int fraud) {
        double shrunk(double base) { return (fraud + PRIOR_WEIGHT * base) / (n + PRIOR_WEIGHT); }
    }

    /** Learns this tenant's signal and agent rates; returns how many facts changed. */
    public int learnTenant() {
        if (!governance.begin(KEY).mayRun()) return 0;
        governance.recordRun(KEY);
        Instant now = Instant.now();
        int window = config.getInt(FraudLabels.WINDOW_KEY, FraudLabels.DEFAULT_WINDOW_DAYS);
        Instant since = now.minus(Duration.ofDays(365 + window));
        int n = 0, fraud = 0;
        Map<String, int[]> levels = new HashMap<>();
        Map<String, int[]> agents = new HashMap<>();
        for (int[] v : decisionRecords.activeVersions(DECISION, since)) {
            for (DecisionRecord r : decisionRecords.labelledBetween(DECISION, v[0], since, now)) {
                Boolean label = FraudLabels.usable(r, now, window);
                if (label == null || r.input() == null) continue;
                n++;
                if (label) fraud++;
                for (String signal : SIGNALS) {
                    Object x = Json.path(r.input(), "payment." + signal);
                    if (x instanceof Number num) count(levels, subject(signal, level(num.doubleValue())), label);
                }
                Object agent = Json.path(r.input(), "agent.id");
                if (agent != null && !String.valueOf(agent).isBlank()) count(agents, String.valueOf(agent), label);
            }
        }
        if (n < MIN_LABELLED) return 0;
        double base = (fraud + 1.0) / (n + 2.0);
        int written = 0;
        for (var e : levels.entrySet()) {
            Rate r = new Rate(e.getValue()[0], e.getValue()[1]);
            if (r.n() >= MIN_LEVEL && facts.number(SubjectType.DECISION, e.getKey(), FactKey.FRAUD_SIGNAL_RATE, round(r.shrunk(base)), r.n(), KEY, 0.001)) written++;
        }
        for (var e : agents.entrySet()) {
            Rate r = new Rate(e.getValue()[0], e.getValue()[1]);
            if (r.n() >= MIN_LEVEL && facts.number(SubjectType.AGENT, e.getKey(), FactKey.FRAUD_SIGNAL_RATE, round(r.shrunk(base)), r.n(), KEY, 0.001)) written++;
        }
        return written;
    }

    private static void count(Map<String, int[]> m, String key, boolean fraud) {
        int[] c = m.computeIfAbsent(key, k -> new int[2]);
        c[0]++;
        if (fraud) c[1]++;
    }

    private static double round(double v) { return Math.round(v * 10_000) / 10_000.0; }

    @Override
    public LearnerDescriptor descriptor() {
        return LearnerDescriptor.of(KEY, "Fraud signal meaning",
                "Learns how often orders turned out fraudulent at each level of card velocity and address, device and card sharing, and for each AI agent's mandated orders, from confirmed outcomes.",
                LearnerDescriptor.Loop.FAST, "FD-01")
            .readsOutcomesOf(DECISION).feeds(FraudFactFeatures.SIGNAL_RISK);
    }

    @Override public boolean runnable() { return true; }

    @Override public void runNow() { sweepOnce(); }
}
