package ai.intentoms.learning.learner;

import ai.intentoms.decision.model.DecisionRecord;
import ai.intentoms.decision.record.DecisionRecordRepository;
import ai.intentoms.learning.governance.GovernedLearner;
import ai.intentoms.learning.governance.LearnerDescriptor;
import ai.intentoms.learning.governance.LearnerGovernance;
import ai.intentoms.learning.model.Episode;
import ai.intentoms.learning.model.EpisodeStatus;
import ai.intentoms.learning.model.FactKey;
import ai.intentoms.learning.model.SubjectType;
import ai.intentoms.learning.store.EpisodeRepository;
import ai.intentoms.platform.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * FS-01, the learning half of the contextual bandit: turns rewarded {@code sourcing_policy_bandit} episodes into arm
 * weights per context bucket. Each episode's anchor record logged the applied policy and the probability it had of
 * being applied, so the value of an arm is the self-normalised inverse-propensity estimate of its reward
 * (sum of reward/propensity over sum of 1/propensity). Provisional rewards count (OC-04: learners use the best current
 * estimate and are corrected as outcomes mature). Values become weights with a softmax; an arm with too little data
 * gets the bucket's mean value, so it keeps a fair share of exploration. Written as {@code SOURCING_POLICY_PROPENSITY}
 * per bucket ({@code SEGMENT:bandit:<bucket>}) and pooled ({@code SEGMENT:default}); {@link SourcingPolicyBandit} reads
 * the bucket's weights, then the pooled ones.
 */
@Component
public class BanditRewardLearner implements GovernedLearner {
    public static final String KEY = "bandit_reward";
    private static final Logger log = LoggerFactory.getLogger(BanditRewardLearner.class);

    private final EpisodeRepository episodes;
    private final DecisionRecordRepository decisionRecords;
    private final FulfilmentFacts facts;
    private final LearnerGovernance governance;

    @Value("${oms.learning.fs01.window-days:90}") private int windowDays = 90;
    @Value("${oms.learning.fs01.min-bucket-episodes:20}") private int minBucket = 20;
    @Value("${oms.learning.fs01.min-arm-episodes:5}") private int minArm = 5;
    @Value("${oms.learning.fs01.temperature:0.2}") private double temperature = 0.2;

    public BanditRewardLearner(EpisodeRepository episodes, DecisionRecordRepository decisionRecords, FulfilmentFacts facts, LearnerGovernance governance) {
        this.episodes = episodes;
        this.decisionRecords = decisionRecords;
        this.facts = facts;
        this.governance = governance;
    }

    /** Every 15 minutes by default (NF-02: bandit posteriors updated within 15 minutes of an outcome). */
    @Scheduled(fixedDelayString = "${oms.learning.fs01.sweep-ms:900000}", initialDelayString = "${oms.learning.fs01.sweep-ms:900000}")
    public void scheduled() {
        try { sweepOnce(); } catch (RuntimeException e) { log.error("Bandit reward sweep failed: {}", e.toString()); }
    }

    public int sweepOnce() {
        List<String> tenants = TenantContext.current().map(List::of).orElseGet(() -> decisionRecords.tenantsWithRecords(Instant.now().minus(Duration.ofDays(windowDays))));
        int n = 0;
        for (String tenant : tenants) n += TenantContext.supplyAs(tenant, this::sweepTenant);
        return n;
    }

    /** One logged choice: the context bucket, the applied arm, its propensity and the episode's reward. */
    record Logged(String bucket, String arm, double propensity, double reward) {}

    private int sweepTenant() {
        if (!governance.begin(KEY).mayRun()) return 0;
        governance.recordRun(KEY);
        List<Logged> logged = new ArrayList<>();
        for (Episode ep : episodes.forDecisionSince(SourcingPolicyBandit.DECISION_NAME, Instant.now().minus(Duration.ofDays(windowDays)), 20_000)) {
            if (ep.reward() == null || ep.status() == EpisodeStatus.OPEN) continue;
            Optional<DecisionRecord> rec = decisionRecords.find(ep.decisionId());
            if (rec.isEmpty() || rec.get().chosenArm() == null || rec.get().propensity() <= 0) continue;
            // A record whose policy was overridden downstream (split check, no plan for the drawn policy) did not apply the bandit's arm.
            if (rec.get().details() != null && Boolean.TRUE.equals(rec.get().details().get("overridden"))) continue;
            Object bucket = rec.get().details() == null ? null : rec.get().details().get("bucket");
            if (bucket == null && rec.get().contextFeatures() != null) bucket = rec.get().contextFeatures().get("bucket");
            logged.add(new Logged(bucket == null ? SourcingPolicyBandit.bucket(rec.get().contextFeatures() == null ? Map.of() : rec.get().contextFeatures()) : String.valueOf(bucket),
                rec.get().chosenArm(), rec.get().propensity(), ep.reward()));
        }
        Map<String, List<Logged>> byBucket = new LinkedHashMap<>();
        for (Logged l : logged) {
            byBucket.computeIfAbsent(l.bucket(), k -> new ArrayList<>()).add(l);
            // The coarse bucket (without segment and load) pools a new segment or load band until it has its own evidence.
            String coarse = SourcingPolicyBandit.coarseBucket(l.bucket());
            if (!coarse.equals(l.bucket())) byBucket.computeIfAbsent(coarse, k -> new ArrayList<>()).add(l);
        }
        int written = 0;
        for (var e : byBucket.entrySet()) {
            if (e.getValue().size() < minBucket) continue;
            Map<String, Double> w = weights(e.getValue(), minArm, temperature);
            if (!w.isEmpty() && facts.list(SubjectType.SEGMENT, SourcingPolicyBandit.bucketSubject(e.getKey()), FactKey.SOURCING_POLICY_PROPENSITY, encode(w), e.getValue().size(), KEY)) written++;
        }
        if (logged.size() >= minBucket) {
            Map<String, Double> w = weights(logged, minArm, temperature);
            if (!w.isEmpty() && facts.list(SubjectType.SEGMENT, "default", FactKey.SOURCING_POLICY_PROPENSITY, encode(w), logged.size(), KEY)) written++;
        }
        return written;
    }

    /** Self-normalised IPS value per arm, turned into softmax weights. Arms below {@code minArm} get the mean value. */
    static Map<String, Double> weights(List<Logged> logged, int minArm, double temperature) {
        Map<String, double[]> acc = new LinkedHashMap<>(); // arm -> {sum r/p, sum 1/p, n}
        for (Logged l : logged) {
            double[] a = acc.computeIfAbsent(l.arm(), k -> new double[3]);
            a[0] += l.reward() / l.propensity();
            a[1] += 1.0 / l.propensity();
            a[2] += 1;
        }
        Map<String, Double> value = new LinkedHashMap<>();
        double sum = 0;
        int counted = 0;
        for (var e : acc.entrySet()) {
            if (e.getValue()[2] < minArm) continue;
            double v = e.getValue()[0] / e.getValue()[1];
            value.put(e.getKey(), v);
            sum += v;
            counted++;
        }
        if (counted == 0) return Map.of();
        double mean = sum / counted;
        for (String arm : acc.keySet()) value.putIfAbsent(arm, mean);
        double max = value.values().stream().mapToDouble(Double::doubleValue).max().orElse(0);
        double t = Math.max(1e-6, temperature);
        Map<String, Double> w = new LinkedHashMap<>();
        double z = 0;
        for (var e : value.entrySet()) { double x = Math.exp((e.getValue() - max) / t); w.put(e.getKey(), x); z += x; }
        for (var e : w.entrySet()) e.setValue(Math.round(e.getValue() / z * 10_000) / 10_000d);
        return w;
    }

    static List<String> encode(Map<String, Double> weights) {
        return weights.entrySet().stream().map(e -> e.getKey() + ":" + String.format(java.util.Locale.ROOT, "%.4f", e.getValue())).toList();
    }

    @Override public boolean runnable() { return true; }

    @Override public void runNow() { sweepOnce(); }

    @Override
    public LearnerDescriptor descriptor() {
        return LearnerDescriptor.of(KEY, "Sourcing policy scores", "Scores each sourcing policy per kind of order from real outcomes, correcting for how often each was tried.",
            LearnerDescriptor.Loop.FAST, "FS-01").readsOutcomesOf(SourcingPolicyBandit.DECISION_NAME).feeds(SourcingPolicyBandit.LIVE_PARAMETER);
    }
}
