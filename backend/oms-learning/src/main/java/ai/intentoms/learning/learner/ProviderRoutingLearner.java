package ai.intentoms.learning.learner;

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

/**
 * DF-05: learns, per decision, which already-configured provider (primary vs. fallback) hits comparable accuracy
 * at lower cost, and proposes routing to it. Scoped to providers already in real use for this decision — finding
 * the globally best provider among ones never tried needs controlled exposure (shadow/canary), which is the
 * experiment-manager build's job; this learner analyses what {@code ai_spend} and labelled {@code decision_record}
 * rows already show about the primary/fallback pair. Accuracy is proxied by the human-resolution non-override
 * rate among labelled records answered by each provider — a real but indirect signal, noted here rather than
 * overstated as ground truth.
 */
@Component
public class ProviderRoutingLearner {
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

    public ProviderRoutingLearner(DecisionDefinitionRepository definitions, AiSpendRepository spend, DecisionRecordRepository decisionRecords,
                                   LearnedFactRepository facts, ProposalRepository proposals) {
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
        int n = 0;
        for (String name : definitions.names()) {
            try { if (sweepDecision(name)) n++; } catch (RuntimeException e) { log.warn("Provider-routing check failed for {}: {}", name, e.toString()); }
        }
        return n;
    }

    private boolean sweepDecision(String name) {
        var live = definitions.byStatus(name, "live");
        if (live.isEmpty()) return false;
        DecisionDefinition def = live.get(0).definition();
        if (def.provider() == null || def.fallbackProvider() == null || def.provider().equals(def.fallbackProvider())) return false;

        LocalDate from = Instant.now().minus(Duration.ofDays(windowDays)).atZone(ZoneOffset.UTC).toLocalDate();
        LocalDate to = Instant.now().atZone(ZoneOffset.UTC).toLocalDate();
        Map<String, Double> costPerCall = costPerCallByProvider(spend.byDecision(from, to), name);
        Double primaryCost = costPerCall.get(def.provider());
        Double fallbackCost = costPerCall.get(def.fallbackProvider());
        if (primaryCost != null) writeFact(name, def.provider(), primaryCost);
        if (fallbackCost != null) writeFact(name, def.fallbackProvider(), fallbackCost);
        if (primaryCost == null || fallbackCost == null || fallbackCost >= primaryCost) return false;

        List<DecisionRecord> labelled = decisionRecords.labelledBetween(name, def.version(), Instant.now().minus(Duration.ofDays(windowDays)), Instant.now()).stream()
            .filter(r -> r.labels() != null && r.labels().containsKey(HUMAN_RESOLUTION_LABEL)).toList();
        double primaryAcc = accuracy(labelled, def.provider());
        double fallbackAcc = accuracy(labelled, def.fallbackProvider());
        long fallbackSample = labelled.stream().filter(r -> def.fallbackProvider().equals(r.provider())).count();
        if (fallbackSample < minSample || fallbackAcc < primaryAcc - accuracyTolerance) return false;

        if (hasPendingProposal(name)) return false;
        PolicyChange change = new PolicyChange.ParameterChange("provider", def.provider(), def.fallbackProvider());
        PolicyChangeset changeset = new PolicyChangeset(new ArtifactRef("decision", name), def.version(), List.of(change), null, PolicyChangeset.RiskTier.valueOf(def.tier().name()));
        Proposal p = new Proposal(Ids.prefixed("prop"), changeset, null, PolicyChangeset.RiskTier.valueOf(def.tier().name()), ProposalStatus.DRAFT, null, null,
            Actor.system(LEARNER).ref(), Instant.now(), Instant.now(), null, null, null, null, null, null, null, null);
        proposals.insert(p);
        log.info("DF-05 proposal {}: {} provider {} -> {} (cost ${} -> ${}, labelled accuracy {} vs {})", p.proposalId(), name, def.provider(), def.fallbackProvider(),
            primaryCost, fallbackCost, primaryAcc, fallbackAcc);
        return true;
    }

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
    private static double accuracy(List<DecisionRecord> labelled, String provider) {
        List<DecisionRecord> subset = labelled.stream().filter(r -> provider.equals(r.provider())).toList();
        if (subset.isEmpty()) return 0;
        long nonOverridden = subset.stream().filter(r -> {
            Map<String, Object> res = (Map<String, Object>) r.labels().get(HUMAN_RESOLUTION_LABEL);
            Object action = res == null ? null : res.get("action");
            return action == null || String.valueOf(action).isBlank() || String.valueOf(action).equalsIgnoreCase(String.valueOf(r.effectiveValue()));
        }).count();
        return nonOverridden / (double) subset.size();
    }

    private void writeFact(String decisionName, String provider, double costPerCall) {
        Instant now = Instant.now();
        String subjectId = decisionName + ":" + provider;
        var prior = facts.active(SubjectType.DECISION, subjectId, FactKey.PROVIDER_COST_PER_CALL);
        int version = prior.map(f -> f.version() + 1).orElse(1);
        facts.supersede(SubjectType.DECISION, subjectId, FactKey.PROVIDER_COST_PER_CALL, now);
        facts.insert(new LearnedFact(Ids.prefixed("fact"), SubjectType.DECISION, subjectId, FactKey.PROVIDER_COST_PER_CALL,
            new TypedValue.NumberValue(costPerCall), 1.0, 0, LEARNER, version, now, null, now));
    }

    private boolean hasPendingProposal(String name) {
        String ref = "decision:" + name;
        return proposals.byStatus("DRAFT", 50).stream().anyMatch(p -> p.changeset().target().toString().equals(ref))
            || proposals.byStatus("EVALUATED", 50).stream().anyMatch(p -> p.changeset().target().toString().equals(ref))
            || proposals.byStatus("APPROVED", 50).stream().anyMatch(p -> p.changeset().target().toString().equals(ref));
    }
}
