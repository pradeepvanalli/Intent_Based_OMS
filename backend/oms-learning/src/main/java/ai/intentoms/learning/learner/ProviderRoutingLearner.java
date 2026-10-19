package ai.intentoms.learning.learner;

import ai.intentoms.learning.governance.GovernedLearner;
import ai.intentoms.learning.governance.LearnerDescriptor;
import ai.intentoms.learning.governance.LearnerGovernance;
import ai.intentoms.decision.definition.DecisionDefinitionRepository;
import ai.intentoms.decision.model.DecisionDefinition;
import ai.intentoms.decision.model.DecisionRecord;
import ai.intentoms.decision.record.AiSpendRepository;
import ai.intentoms.decision.record.DecisionRecordRepository;
import ai.intentoms.learning.model.ArtifactRef;
import ai.intentoms.learning.model.FactKey;
import ai.intentoms.learning.model.LearnedFact;
import ai.intentoms.learning.model.PolicyChange;
import ai.intentoms.learning.model.PolicyChangeset;
import ai.intentoms.learning.model.Proposal;
import ai.intentoms.learning.model.ProposalStatus;
import ai.intentoms.learning.model.SubjectType;
import ai.intentoms.learning.model.TypedValue;
import ai.intentoms.learning.store.LearnedFactRepository;
import ai.intentoms.learning.store.ProposalRepository;
import ai.intentoms.platform.ids.Ids;
import ai.intentoms.platform.ledger.Actor;
import ai.intentoms.platform.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * DF-05: learns, per decision, which provider and model hits the target accuracy at the lowest cost and latency, and
 * proposes routing to it. Every provider that answered the decision in the window is compared (the primary, the
 * fallback, and any provider an experiment or an earlier route sent traffic to), not just the primary/fallback pair:
 * <ul>
 *   <li><b>accuracy</b>: the share of its labelled answers people kept (a {@code human_resolution} that did not
 *       override it), from at least {@code oms.learning.df05.min-labelled-sample} (default 20) labelled cases;</li>
 *   <li><b>cost</b>: spend per call from {@code ai_spend}, else the mean recorded cost of its answers;</li>
 *   <li><b>latency</b>: the 95th percentile of its answers' {@code latency_ms}.</li>
 * </ul>
 * A provider qualifies when its accuracy reaches the target: the decision's {@code minAccuracyInActBand} when set,
 * never more than {@code oms.learning.df05.accuracy-tolerance} (default 0.02) below the primary's. The cheapest
 * qualifying provider wins; providers within 10% of that cost are separated by latency. Each provider's cost, latency
 * and accuracy are published as facts; a winner other than the primary becomes a routing proposal.
 */
@Component
public class ProviderRoutingLearner implements GovernedLearner {
    public static final String KEY = "provider_routing";
    private static final Logger log = LoggerFactory.getLogger(ProviderRoutingLearner.class);
    private static final String LEARNER = "provider_routing";
    private static final String HUMAN_RESOLUTION_LABEL = "human_resolution";

    private final DecisionDefinitionRepository definitions;
    private final AiSpendRepository spend;
    private final DecisionRecordRepository decisionRecords;
    private final LearnedFactRepository facts;
    private final ProposalRepository proposals;

    @Value("${oms.learning.df05.min-labelled-sample:20}") private int minSample;
    @Value("${oms.learning.df05.accuracy-tolerance:0.02}") private double accuracyTolerance;
    @Value("${oms.learning.df05.window-days:30}") private int windowDays;

    private final LearnerGovernance governance;

    public ProviderRoutingLearner(DecisionDefinitionRepository definitions, AiSpendRepository spend, DecisionRecordRepository decisionRecords,
                                   LearnedFactRepository facts, ProposalRepository proposals, LearnerGovernance governance) {
        this.governance = governance;
        this.definitions = definitions;
        this.spend = spend;
        this.decisionRecords = decisionRecords;
        this.facts = facts;
        this.proposals = proposals;
    }

    @Scheduled(fixedDelayString = "${oms.learning.df05.sweep-ms:86400000}", initialDelayString = "${oms.learning.df05.sweep-ms:800000}")
    public void scheduled() {
        try { sweepOnce(); } catch (RuntimeException e) { log.error("Provider-routing sweep failed: {}", e.toString()); }
    }

    public int sweepOnce() {
        int n = 0;
        List<String> tenants = TenantContext.current().map(List::of).orElseGet(() -> decisionRecords.tenantsWithRecords(Instant.now().minus(Duration.ofDays(windowDays))));
        for (String tenant : tenants) n += TenantContext.supplyAs(tenant, this::sweepTenant);
        return n;
    }

    private int sweepTenant() {
        if (!governance.begin(KEY).mayPropose()) return 0;
        governance.recordRun(KEY);
        int n = 0;
        for (String name : definitions.names()) {
            try { if (sweepDecision(name)) n++; } catch (RuntimeException e) { log.warn("Provider-routing check failed for {}: {}", name, e.toString()); }
        }
        return n;
    }

    /** What one provider showed for one decision in the window. */
    public record ProviderStats(String provider, int answers, int labelled, double accuracy, Double costPerCall, Long p95LatencyMs) {}

    private boolean sweepDecision(String name) {
        var live = definitions.byStatus(name, "live");
        if (live.isEmpty()) return false;
        DecisionDefinition def = live.get(0).definition();
        if (def.provider() == null) return false;
        Instant since = Instant.now().minus(Duration.ofDays(windowDays));
        LocalDate from = since.atZone(ZoneOffset.UTC).toLocalDate();
        LocalDate to = Instant.now().atZone(ZoneOffset.UTC).toLocalDate();
        Map<String, Double> spendPerCall = costPerCallByProvider(spend.byDecision(from, to), name);
        List<DecisionRecord> answered = decisionRecords.recentLive(name, def.version(), since, 20_000).stream()
            .filter(r -> r.provider() != null && r.layer() != ai.intentoms.decision.model.Layer.L0_RULE && r.layer() != ai.intentoms.decision.model.Layer.DEFAULT).toList();
        List<DecisionRecord> labelled = decisionRecords.labelledBetween(name, def.version(), since, Instant.now()).stream()
            .filter(r -> r.labels() != null && r.labels().containsKey(HUMAN_RESOLUTION_LABEL)).toList();
        List<ProviderStats> stats = stats(answered, labelled, spendPerCall);
        for (ProviderStats st : stats) {
            if (st.costPerCall() != null) writeFact(name, st.provider(), FactKey.PROVIDER_COST_PER_CALL, st.costPerCall(), st.answers());
            if (st.p95LatencyMs() != null) writeFact(name, st.provider(), FactKey.PROVIDER_LATENCY_MS, st.p95LatencyMs(), st.answers());
            if (st.labelled() > 0) writeFact(name, st.provider(), FactKey.PROVIDER_ACCURACY, round(st.accuracy()), st.labelled());
        }
        ProviderStats primary = stats.stream().filter(st -> st.provider().equals(def.provider())).findFirst().orElse(null);
        Double floor = def.evaluation() == null ? null : def.evaluation().minAccuracyInActBand();
        Optional<ProviderStats> best = choose(stats, primary, floor, minSample, accuracyTolerance);
        if (best.isEmpty() || best.get().provider().equals(def.provider())) return false;
        if (hasPendingProposal(name)) return false;

        ProviderStats b = best.get();
        String compared = stats.stream().map(st -> String.format(java.util.Locale.ROOT, "%s: accuracy %s on %d, $%s/call, p95 %s ms", st.provider(),
            st.labelled() == 0 ? "n/a" : String.format(java.util.Locale.ROOT, "%.3f", st.accuracy()), st.labelled(),
            st.costPerCall() == null ? "n/a" : String.format(java.util.Locale.ROOT, "%.5f", st.costPerCall()), st.p95LatencyMs() == null ? "n/a" : st.p95LatencyMs()))
            .collect(java.util.stream.Collectors.joining("; "));
        // Promoted as a routing overlay (config:learning.provider_route.<decision>) read by PromotedProviderRouter: the
        // decision keeps its own provider chain behind the routed one, and rollback or a paused learner restores it.
        String routeKey = ai.intentoms.learning.hooks.PromotedProviderRouter.KEY_PREFIX + name;
        PolicyChange change = new PolicyChange.ParameterChange(routeKey, def.provider(), b.provider());
        PolicyChangeset changeset = new PolicyChangeset(new ArtifactRef("config", routeKey), def.version(), List.of(change),
            String.format(java.util.Locale.ROOT, "%s reaches the accuracy target for %s at the lowest cost and latency. Compared: %s", b.provider(), name, compared),
            PolicyChangeset.RiskTier.valueOf(def.tier().name()));
        Proposal p = new Proposal(Ids.prefixed("prop"), changeset, null, PolicyChangeset.RiskTier.valueOf(def.tier().name()), ProposalStatus.DRAFT, null, null,
            Actor.system(LEARNER).ref(), Instant.now(), Instant.now(), null, null, null, null, null, null, null, null);
        proposals.insert(p);
        log.info("DF-05 proposal {}: {} provider {} -> {} ({})", p.proposalId(), name, def.provider(), b.provider(), compared);
        return true;
    }

    /** Per provider: answers, labelled answers, kept share, cost per call (spend, else recorded cost) and p95 latency. */
    static List<ProviderStats> stats(List<DecisionRecord> answered, List<DecisionRecord> labelled, Map<String, Double> spendPerCall) {
        java.util.Set<String> providers = new java.util.TreeSet<>();
        answered.forEach(r -> providers.add(r.provider()));
        labelled.stream().filter(r -> r.provider() != null).forEach(r -> providers.add(r.provider()));
        List<ProviderStats> out = new java.util.ArrayList<>();
        for (String provider : providers) {
            List<DecisionRecord> mine = answered.stream().filter(r -> provider.equals(r.provider())).toList();
            List<DecisionRecord> mineLabelled = labelled.stream().filter(r -> provider.equals(r.provider())).toList();
            Double cost = spendPerCall.containsKey(provider) ? spendPerCall.get(provider)
                : mine.isEmpty() ? null : mine.stream().mapToDouble(DecisionRecord::costUsd).average().orElse(0);
            Long p95 = mine.isEmpty() ? null : percentile(mine.stream().map(DecisionRecord::latencyMs).sorted().toList(), 0.95);
            out.add(new ProviderStats(provider, mine.size(), mineLabelled.size(), accuracy(mineLabelled), cost, p95));
        }
        return out;
    }

    /**
     * The provider to route to: among providers with enough labelled answers whose accuracy reaches the target (the
     * floor when set, never more than {@code tolerance} below the primary), the cheapest; those within 10% of that cost
     * are separated by p95 latency. Empty when no provider qualifies.
     */
    static Optional<ProviderStats> choose(List<ProviderStats> stats, ProviderStats primary, Double floor, int minSample, double tolerance) {
        double target = Math.max(floor == null ? 0 : floor, primary == null || primary.labelled() < minSample ? 0 : primary.accuracy() - tolerance);
        List<ProviderStats> ok = stats.stream().filter(st -> st.labelled() >= minSample && st.accuracy() >= target && st.costPerCall() != null).toList();
        if (ok.isEmpty()) return Optional.empty();
        double cheapest = ok.stream().mapToDouble(ProviderStats::costPerCall).min().orElse(0);
        return ok.stream().filter(st -> st.costPerCall() <= cheapest * 1.10 + 1e-12)
            .min(java.util.Comparator.comparingLong((ProviderStats st) -> st.p95LatencyMs() == null ? Long.MAX_VALUE : st.p95LatencyMs())
                .thenComparingDouble(ProviderStats::costPerCall));
    }

    private static long percentile(List<Long> sorted, double q) {
        if (sorted.isEmpty()) return 0;
        int idx = (int) Math.ceil(q * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(sorted.size() - 1, idx)));
    }

    private static double round(double v) { return Math.round(v * 10_000) / 10_000.0; }

    private static Map<String, Double> costPerCallByProvider(List<Map<String, Object>> rows, String decisionName) {
        Map<String, double[]> agg = new java.util.LinkedHashMap<>(); // provider -> [calls, cost]
        for (Map<String, Object> r : rows) {
            if (!decisionName.equals(r.get("decision"))) continue;
            String provider = String.valueOf(r.get("provider"));
            double calls = ((Number) r.get("calls")).doubleValue();
            double cost = ((Number) r.get("cost_usd")).doubleValue();
            double[] acc = agg.computeIfAbsent(provider, k -> new double[2]);
            acc[0] += calls; acc[1] += cost;
        }
        Map<String, Double> out = new java.util.LinkedHashMap<>();
        agg.forEach((p, acc) -> { if (acc[0] > 0) out.put(p, acc[1] / acc[0]); });
        return out;
    }

    @SuppressWarnings("unchecked")
    private static double accuracy(List<DecisionRecord> labelled) {
        if (labelled.isEmpty()) return 0;
        long kept = labelled.stream().filter(r -> {
            Map<String, Object> res = (Map<String, Object>) r.labels().get(HUMAN_RESOLUTION_LABEL);
            Object action = res == null ? null : res.get("action");
            return action == null || String.valueOf(action).isBlank() || String.valueOf(action).equalsIgnoreCase(String.valueOf(r.value()));
        }).count();
        return kept / (double) labelled.size();
    }

    private void writeFact(String decisionName, String provider, FactKey key, double value, int sampleSize) {
        Instant now = Instant.now();
        String subjectId = decisionName + ":" + provider;
        var prior = facts.active(SubjectType.DECISION, subjectId, key);
        if (prior.isPresent() && prior.get().value() instanceof TypedValue.NumberValue n && Math.abs(n.value() - value) < 1e-9 && prior.get().sampleSize() == sampleSize) return;
        int version = prior.map(f -> f.version() + 1).orElse(1);
        facts.supersede(SubjectType.DECISION, subjectId, key, now);
        facts.insert(new LearnedFact(Ids.prefixed("fact"), SubjectType.DECISION, subjectId, key, new TypedValue.NumberValue(value), 1.0, sampleSize, LEARNER, version, now, null, now));
    }

    private boolean hasPendingProposal(String name) {
        return proposals.existsOpenFor("config:" + ai.intentoms.learning.hooks.PromotedProviderRouter.KEY_PREFIX + name);
    }

    @Override
    public LearnerDescriptor descriptor() {
        return LearnerDescriptor.of(KEY, "AI provider routing", "Finds, among every AI provider that answered a decision, the one that meets the accuracy target at the lowest cost and latency.", LearnerDescriptor.Loop.FAST, "DF-05");
        // Routing goes live as a promoted config change (learning.provider_route.<decision>), not as a learned live value.
    }

    @Override public boolean runnable() { return true; }

    @Override public void runNow() { sweepOnce(); }
}
