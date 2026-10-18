package ai.intentoms.learning.learner;

import ai.intentoms.learning.governance.GovernedLearner;
import ai.intentoms.learning.governance.LearnerDescriptor;
import ai.intentoms.learning.governance.LearnerGovernance;
import ai.intentoms.decision.model.DecisionRecord;
import ai.intentoms.decision.record.DecisionRecordRepository;
import ai.intentoms.learning.model.Direction;
import ai.intentoms.learning.model.FactKey;
import ai.intentoms.learning.model.Hypothesis;
import ai.intentoms.learning.model.LearnedFact;
import ai.intentoms.learning.model.SubjectType;
import ai.intentoms.learning.model.TypedValue;
import ai.intentoms.learning.store.HypothesisRepository;
import ai.intentoms.learning.store.LearnedFactRepository;
import ai.intentoms.platform.ids.Ids;
import ai.intentoms.platform.json.Json;
import ai.intentoms.platform.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * FD-04: finds abuse patterns in real history and raises them as hypotheses for a person (or the author pipeline) to
 * turn into a reviewed {@code RuleAdd}; it never writes a rule or calls a model itself.
 * <ul>
 *   <li><b>Address-reuse rings.</b> Every {@code payment_risk} decision carries a non-reversible address token and the
 *       customer. Over 30 days, an address used by several different customers is a classic reshipping ring: the
 *       count is published as an {@code ADDRESS_REUSE_COUNT} fact on the address (the decision reads it once promoted
 *       for live use), and at {@code oms.learning.fraud.ring-min-customers} (default 3) or more a hypothesis is raised
 *       with the evidence, once per address while it is open.</li>
 *   <li><b>Channel concentration.</b> A confirmed-fraud rate of 15% or more in one order channel for a fraud decision
 *       (at least 20 labelled cases).</li>
 * </ul>
 */
@Component
public class FraudAbuseHypothesisScanner implements GovernedLearner {
    public static final String KEY = "fraud_abuse_scanner";
    private static final Logger log = LoggerFactory.getLogger(FraudAbuseHypothesisScanner.class);
    private static final List<String> FRAUD_DECISIONS = List.of("payment_risk", "return_risk", "rto_risk");
    private static final double ELEVATED_RATE_THRESHOLD = 0.15;
    private static final int MIN_SAMPLE = 20;

    private final DecisionRecordRepository decisionRecords;
    private final HypothesisRepository hypotheses;
    private final LearnedFactRepository facts;
    private final LearnerGovernance governance;

    @Value("${oms.learning.fraud.ring-min-customers:3}") private int ringMinCustomers = 3;

    public FraudAbuseHypothesisScanner(DecisionRecordRepository decisionRecords, HypothesisRepository hypotheses, LearnedFactRepository facts, LearnerGovernance governance) {
        this.governance = governance;
        this.decisionRecords = decisionRecords;
        this.hypotheses = hypotheses;
        this.facts = facts;
    }

    @Scheduled(fixedDelayString = "${oms.learning.abuse-scan-ms:86400000}", initialDelayString = "${oms.learning.abuse-scan-ms:86400000}")
    public void scheduled() {
        try { sweepOnce(); } catch (RuntimeException e) { log.error("Abuse hypothesis scan failed: {}", e.toString()); }
    }

    public void sweepOnce() {
        List<String> tenants = TenantContext.current().map(List::of).orElseGet(() -> decisionRecords.tenantsWithRecords(Instant.now().minus(Duration.ofDays(30))));
        for (String tenant : tenants) TenantContext.runAs(tenant, this::scanTenant);
    }

    private void scanTenant() {
        LearnerGovernance.Gate gate = governance.begin(KEY);
        if (!gate.mayRun()) return;
        governance.recordRun(KEY);
        Instant since = Instant.now().minus(Duration.ofDays(30));
        // L0 observes: ring counts are still measured and published as facts (never used live at L0), but no hypothesis.
        scanAddressRings(since, gate.mayPropose());
        if (!gate.mayPropose()) return;
        for (String decision : FRAUD_DECISIONS) {
            List<DecisionRecord> labelled = new ArrayList<>();
            for (int v : versions(decision, since)) labelled.addAll(decisionRecords.labelledBetween(decision, v, since, Instant.now()));
            if (labelled.size() < MIN_SAMPLE) continue;
            Map<String, List<DecisionRecord>> byChannel = labelled.stream().collect(Collectors.groupingBy(r -> String.valueOf(channel(r))));
            for (var entry : byChannel.entrySet()) {
                List<DecisionRecord> group = entry.getValue();
                if (group.size() < MIN_SAMPLE) continue;
                long confirmed = group.stream().filter(r -> Boolean.TRUE.equals(fraudConfirmed(r))).count();
                double rate = (double) confirmed / group.size();
                if (rate < ELEVATED_RATE_THRESHOLD) continue;
                raiseChannelHypothesis(decision, entry.getKey(), rate, group.size());
            }
        }
    }

    /** Groups recent payment_risk decisions by address token; publishes the reuse count and raises ring hypotheses. */
    void scanAddressRings(Instant since, boolean mayPropose) {
        Map<String, Set<String>> customersByAddress = new LinkedHashMap<>();
        Map<String, Integer> confirmedByAddress = new LinkedHashMap<>();
        for (int v : versions("payment_risk", since)) {
            for (DecisionRecord r : decisionRecords.recentLive("payment_risk", v, since, 20_000)) {
                Object token = path(r, "address.token");
                Object customer = path(r, "customer.ref");
                if (token == null || customer == null) continue;
                customersByAddress.computeIfAbsent(String.valueOf(token), k -> new HashSet<>()).add(String.valueOf(customer));
                if (Boolean.TRUE.equals(fraudConfirmed(r))) confirmedByAddress.merge(String.valueOf(token), 1, Integer::sum);
            }
        }
        Set<String> openRings = mayPropose ? openRingHypotheses() : Set.of();
        int rings = 0;
        for (var e : customersByAddress.entrySet()) {
            int customers = e.getValue().size();
            if (customers < 2) continue; // one customer at one address is the normal case: nothing to publish
            publishReuseCount(e.getKey(), customers);
            if (mayPropose && customers >= ringMinCustomers && !openRings.contains(e.getKey())) {
                raiseRingHypothesis(e.getKey(), customers, confirmedByAddress.getOrDefault(e.getKey(), 0));
                rings++;
            }
        }
        if (rings > 0) log.info("Raised {} address-reuse ring hypotheses", rings);
    }

    private void publishReuseCount(String token, int customers) {
        var active = facts.active(SubjectType.ADDRESS, token, FactKey.ADDRESS_REUSE_COUNT);
        if (active.isPresent() && active.get().value() instanceof TypedValue.NumberValue n && n.value() == customers) return;
        Instant now = Instant.now();
        facts.supersede(SubjectType.ADDRESS, token, FactKey.ADDRESS_REUSE_COUNT, now);
        facts.insert(new LearnedFact(Ids.prefixed("fact"), SubjectType.ADDRESS, token, FactKey.ADDRESS_REUSE_COUNT, new TypedValue.NumberValue(customers),
            Math.min(1.0, customers / 5.0), customers, KEY, active.map(f -> f.version() + 1).orElse(1), now, null, now));
    }

    private Set<String> openRingHypotheses() {
        Set<String> out = new HashSet<>();
        for (Hypothesis h : hypotheses.byStatus("OPEN", 1000)) if (h.scope() != null && h.scope().get("addressToken") != null) out.add(String.valueOf(h.scope().get("addressToken")));
        return out;
    }

    private void raiseRingHypothesis(String token, int customers, int confirmed) {
        String rationale = customers + " different customers shipped to the same address in 30 days" + (confirmed > 0 ? ", " + confirmed + " confirmed fraud" : "")
            + "; consider a rule holding orders to this address for review.";
        Hypothesis h = new Hypothesis(Ids.prefixed("hyp"), Map.of("decision", "payment_risk", "addressToken", token, "pattern", "address_reuse_ring"),
            "fraud_and_abuse_loss", Direction.DECREASE, Map.of("decision", "payment_risk", "lever", "ruleAdd", "addressToken", token),
            Math.min(0.9, 0.3 + 0.1 * customers + 0.2 * Math.min(confirmed, 2)),
            List.of("distinct_customers=" + customers, "confirmed_fraud=" + confirmed, "window_days=30"), rationale.length() > 280 ? rationale.substring(0, 280) : rationale,
            Hypothesis.Status.OPEN, Instant.now());
        hypotheses.insert(h);
    }

    private void raiseChannelHypothesis(String decision, String channel, double rate, int n) {
        Hypothesis h = new Hypothesis(Ids.prefixed("hyp"), Map.of("decision", decision, "channel", channel), "fraud_and_abuse_loss", Direction.DECREASE,
            Map.of("decision", decision, "lever", "ruleAdd"), Math.min(0.9, rate), List.of(),
            ("Confirmed-fraud rate for " + decision + " in channel " + channel + " is " + String.format("%.0f%%", rate * 100) + " over " + n + " labelled cases, above the "
                + (int) (ELEVATED_RATE_THRESHOLD * 100) + "% floor."), Hypothesis.Status.OPEN, Instant.now());
        hypotheses.insert(h);
        log.info("Raised abuse hypothesis for {} in channel {} (rate {}, n={})", decision, channel, rate, n);
    }

    private List<Integer> versions(String decision, Instant since) {
        return decisionRecords.activeVersions(decision, since).stream().map(a -> a[0]).toList();
    }

    private static Object channel(DecisionRecord r) {
        Object c = path(r, "order.channel");
        return c == null ? "unknown" : c;
    }

    private static Object path(DecisionRecord r, String dotted) {
        if (r.input() == null) return null;
        Object v = Json.path(r.input(), dotted);
        return v != null ? v : r.input().get(dotted);
    }

    @SuppressWarnings("unchecked")
    private static Boolean fraudConfirmed(DecisionRecord r) {
        Object outcome = r.labels() == null ? null : r.labels().get("fraud_outcome");
        if (!(outcome instanceof Map<?, ?> m)) return false;
        return Boolean.TRUE.equals(((Map<String, Object>) m).get("confirmed"));
    }

    @Override
    public LearnerDescriptor descriptor() {
        return LearnerDescriptor.of(KEY, "Abuse pattern finder", "Looks for new fraud and abuse patterns, such as one address shared by many customers, and suggests rules for a person to review.",
            LearnerDescriptor.Loop.FAST, "FD-04").readsOutcomesOf("payment_risk", "return_risk", "rto_risk").feeds(ai.intentoms.learning.hooks.FraudFactFeatures.ADDRESS_REUSE);
    }

    @Override public boolean runnable() { return true; }

    @Override public void runNow() { sweepOnce(); }
}
