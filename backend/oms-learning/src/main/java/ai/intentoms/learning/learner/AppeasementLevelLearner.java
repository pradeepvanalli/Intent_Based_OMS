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
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * CX-04: the appeasement credit that keeps customers from complaining again, per segment, within budget. For every
 * {@code appeasement} episode it takes the credit actually given (the person's resolution when there was one, else
 * the recommendation: {@code credit_10} = 10, {@code credit_20} = 20, {@code refund_shipping} = the shipping paid,
 * {@code none} = 0) and whether another complaint followed. Per segment, among credit levels with enough cases, the
 * smallest level whose repeat-complaint rate is within {@code cs.appeasement.tolerance} (default 2 points) of the best
 * level's and no more than the per-case budget becomes {@code APPEASEMENT_LEVEL} ({@code SEGMENT:tier:<tier>} and
 * {@code SEGMENT:default}). Appeasement stays T2: this only sets the credit a person is asked to approve.
 */
@Component
public class AppeasementLevelLearner implements GovernedLearner {
    public static final String KEY = "appeasement_level";
    public static final String PARAMETER = "cs.appeasement_level";
    public static final String STATIC_KEY = "cs.appeasement_default_usd";
    public static final String BUDGET_KEY = "cs.appeasement_budget_usd_per_case";
    public static final String DECISION = "appeasement";
    private static final Logger log = LoggerFactory.getLogger(AppeasementLevelLearner.class);

    private final EpisodeRepository episodes;
    private final OutcomeRepository outcomes;
    private final DecisionRecordRepository decisionRecords;
    private final FulfilmentFacts facts;
    private final TenantConfigStore config;
    private final LearnerGovernance governance;

    @Value("${oms.learning.cx04.window-days:180}") private int windowDays = 180;
    @Value("${oms.learning.cx04.min-cases:10}") private int minCases = 10;

    public AppeasementLevelLearner(EpisodeRepository episodes, OutcomeRepository outcomes, DecisionRecordRepository decisionRecords, FulfilmentFacts facts,
                                   TenantConfigStore config, LearnerGovernance governance) {
        this.episodes = episodes;
        this.outcomes = outcomes;
        this.decisionRecords = decisionRecords;
        this.facts = facts;
        this.config = config;
        this.governance = governance;
    }

    public static String segment(String tier) { return tier == null || tier.isBlank() ? "default" : "tier:" + tier.toLowerCase(Locale.ROOT); }

    @Scheduled(fixedDelayString = "${oms.learning.cx04.sweep-ms:86400000}", initialDelayString = "${oms.learning.cx04.sweep-ms:940000}")
    public void scheduled() {
        try { sweepOnce(); } catch (RuntimeException e) { log.error("Appeasement level sweep failed: {}", e.toString()); }
    }

    public int sweepOnce() {
        List<String> tenants = TenantContext.current().map(List::of).orElseGet(() -> decisionRecords.tenantsWithRecords(Instant.now().minus(Duration.ofDays(windowDays))));
        int n = 0;
        for (String tenant : tenants) n += TenantContext.supplyAs(tenant, this::sweepTenant);
        return n;
    }

    /** One appeasement: the customer's segment, the credit given and whether another complaint followed. */
    record Case(String segment, double credit, boolean repeat) {}

    private int sweepTenant() {
        if (!governance.begin(KEY).mayRun()) return 0;
        governance.recordRun(KEY);
        List<Case> cases = new ArrayList<>();
        for (Episode ep : episodes.forDecisionSince(DECISION, Instant.now().minus(Duration.ofDays(windowDays)), 20_000)) {
            var rec = decisionRecords.find(ep.decisionId());
            if (rec.isEmpty()) continue;
            Double credit = creditGiven(rec.get());
            if (credit == null) continue;
            Instant after = ep.createdAt().plusSeconds(60);
            boolean repeat = outcomes.forEpisode(ep.episodeId()).stream()
                .anyMatch(o -> o.payload() instanceof OutcomeData.ContactOutcome && o.observedAt().isAfter(after));
            cases.add(new Case(segment(tierOf(rec.get())), credit, repeat));
        }
        double budget = config.getDouble(BUDGET_KEY, 25.0);
        double tolerance = config.getDouble("cs.appeasement.tolerance", 0.02);
        int written = 0;
        Map<String, List<Case>> bySegment = new TreeMap<>();
        for (Case c : cases) bySegment.computeIfAbsent(c.segment(), k -> new ArrayList<>()).add(c);
        bySegment.put("default", cases);
        for (var e : bySegment.entrySet()) {
            Double level = bestLevel(e.getValue(), minCases, budget, tolerance);
            if (level != null && facts.number(SubjectType.SEGMENT, e.getKey(), FactKey.APPEASEMENT_LEVEL, level, e.getValue().size(), KEY, 0.5)) written++;
        }
        return written;
    }

    /** The smallest credit within budget whose smoothed repeat-complaint rate is within {@code tolerance} of the best. */
    static Double bestLevel(List<Case> cases, int minCases, double budget, double tolerance) {
        Map<Double, int[]> byLevel = new TreeMap<>();
        for (Case c : cases) {
            int[] a = byLevel.computeIfAbsent(c.credit(), k -> new int[2]);
            a[0]++;
            if (c.repeat()) a[1]++;
        }
        Map<Double, Double> rate = new TreeMap<>();
        byLevel.forEach((level, a) -> { if (a[0] >= minCases && level <= budget) rate.put(level, (a[1] + 1.0) / (a[0] + 2.0)); });
        if (rate.isEmpty()) return null;
        double best = rate.values().stream().mapToDouble(Double::doubleValue).min().orElse(1);
        for (var e : rate.entrySet()) if (e.getValue() <= best + tolerance) return e.getKey();
        return null;
    }

    /** USD credit actually given: the person's resolution when present, else the recommended option. */
    static Double creditGiven(DecisionRecord r) {
        Object value = r.overriddenValue() != null ? r.overriddenValue() : r.value();
        if (r.labels() != null && r.labels().get("human_resolution") instanceof Map<?, ?> h && h.get("value") != null) value = h.get("value");
        if (value instanceof Number n) return n.doubleValue();
        String s = value == null ? "" : String.valueOf(value);
        return switch (s) {
            case "none" -> 0.0;
            case "credit_10" -> 10.0;
            case "credit_20" -> 20.0;
            case "refund_shipping" -> shippingPaid(r);
            default -> {
                try { yield Double.parseDouble(s); } catch (NumberFormatException e) { yield null; }
            }
        };
    }

    private static Double shippingPaid(DecisionRecord r) {
        if (r.input() != null && r.input().get("order") instanceof Map<?, ?> o && o.get("shipping_paid") instanceof Number n) return n.doubleValue();
        return null;
    }

    private static String tierOf(DecisionRecord r) {
        if (r.input() != null && r.input().get("customer") instanceof Map<?, ?> c && c.get("tier") != null && !String.valueOf(c.get("tier")).isBlank()) return String.valueOf(c.get("tier"));
        return null;
    }

    @Override public boolean runnable() { return true; }

    @Override public void runNow() { sweepOnce(); }

    @Override
    public LearnerDescriptor descriptor() {
        return LearnerDescriptor.of(KEY, "Appeasement level", "Learns the goodwill credit that stops repeat complaints for each kind of customer, within budget. A person still approves every credit.",
            LearnerDescriptor.Loop.FAST, "CX-04").readsOutcomesOf(DECISION).feeds(PARAMETER);
    }
}
