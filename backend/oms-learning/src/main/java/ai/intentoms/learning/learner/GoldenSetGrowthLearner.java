package ai.intentoms.learning.learner;

import ai.intentoms.decision.definition.DecisionDefinitionRepository;
import ai.intentoms.decision.model.DecisionDefinition;
import ai.intentoms.decision.model.DecisionRecord;
import ai.intentoms.decision.record.DecisionRecordRepository;
import ai.intentoms.learning.model.Direction;
import ai.intentoms.learning.model.Hypothesis;
import ai.intentoms.learning.store.HypothesisRepository;
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
import java.util.stream.Collectors;

/**
 * DF-03: grows golden sets from human-labeled escalations after deduplication and reviewer sign-off. Groups
 * {@code decision_record} rows carrying a {@code human_resolution} label (written by {@code HumanResolutionCapture},
 * Wave 0) by exact input hash within a decision — a conservative, exact-match grouping, not fuzzy similarity — and
 * raises a {@link Hypothesis} once a situation has recurred often enough with a consistent human answer. Does not
 * insert into {@code decision_golden_case} itself: that still needs a human's sign-off (requirements doc §8.7,
 * "after deduplication and reviewer sign-off"), so this only produces the candidate and its evidence.
 */
@Component
public class GoldenSetGrowthLearner {
    private static final Logger log = LoggerFactory.getLogger(GoldenSetGrowthLearner.class);
    private static final String HUMAN_RESOLUTION_LABEL = "human_resolution";

    private final DecisionDefinitionRepository definitions;
    private final DecisionRecordRepository decisionRecords;
    private final HypothesisRepository hypotheses;

    @Value("${oms.learning.df03.min-occurrences:3}") private int minOccurrences;
    @Value("${oms.learning.df03.window-days:60}") private int windowDays;

    public GoldenSetGrowthLearner(DecisionDefinitionRepository definitions, DecisionRecordRepository decisionRecords, HypothesisRepository hypotheses) {
        this.definitions = definitions;
        this.decisionRecords = decisionRecords;
        this.hypotheses = hypotheses;
    }

    @Scheduled(fixedDelayString = "${oms.learning.df03.sweep-ms:86400000}", initialDelayString = "${oms.learning.df03.sweep-ms:700000}")
    public void scheduled() {
        try { sweepOnce(); } catch (RuntimeException e) { log.error("Golden-set growth sweep failed: {}", e.toString()); }
    }

    public int sweepOnce() {
        int n = 0;
        List<String> tenants = TenantContext.current().map(List::of).orElseGet(() -> decisionRecords.tenantsWithRecords(Instant.now().minus(Duration.ofDays(windowDays))));
        for (String tenant : tenants) n += TenantContext.supplyAs(tenant, this::sweepTenant);
        return n;
    }

    private int sweepTenant() {
        int n = 0;
        Instant since = Instant.now().minus(Duration.ofDays(windowDays));
        for (String name : definitions.names()) {
            var live = definitions.byStatus(name, "live");
            if (live.isEmpty()) continue;
            DecisionDefinition def = live.get(0).definition();
            List<DecisionRecord> labelled = decisionRecords.labelledBetween(name, def.version(), since, Instant.now()).stream()
                .filter(r -> r.labels() != null && r.labels().containsKey(HUMAN_RESOLUTION_LABEL)).toList();
            Map<String, List<DecisionRecord>> byInput = labelled.stream().collect(Collectors.groupingBy(DecisionRecord::inputHash));
            for (var entry : byInput.entrySet()) {
                if (entry.getValue().size() < minOccurrences) continue;
                if (raiseIfNew(name, entry.getKey(), entry.getValue())) n++;
            }
        }
        return n;
    }

    private boolean raiseIfNew(String decisionName, String inputHash, List<DecisionRecord> group) {
        String refKey = decisionName + ":" + inputHash;
        boolean already = hypotheses.byStatus("OPEN", 500).stream().anyMatch(h -> refKey.equals(h.lever().get("groupKey")))
            || hypotheses.byStatus("DRAFTED", 500).stream().anyMatch(h -> refKey.equals(h.lever().get("groupKey")));
        if (already) return false;

        DecisionRecord sample = group.get(0);
        @SuppressWarnings("unchecked") Map<String, Object> resolution = (Map<String, Object>) sample.labels().get(HUMAN_RESOLUTION_LABEL);
        Object action = resolution == null ? null : resolution.get("action");
        if (action == null || String.valueOf(action).isBlank()) return false;

        Hypothesis h = new Hypothesis(Ids.prefixed("hyp"), Map.of("decision", decisionName), "golden_set_coverage", Direction.INCREASE,
            Map.of("action", "add_golden_case", "decision", decisionName, "groupKey", refKey, "inputHash", inputHash, "expectedValue", String.valueOf(action),
                "sourceRecordIds", group.stream().map(DecisionRecord::id).limit(10).toList()),
            0.7, group.stream().map(DecisionRecord::id).toList(),
            group.size() + " human resolutions of the same input for " + decisionName + " agreed on \"" + action + "\" — a strong golden-case candidate.",
            Hypothesis.Status.OPEN, Instant.now());
        hypotheses.insert(h);
        log.info("DF-03 hypothesis {}: {} recurring resolutions for {} (input hash {})", h.hypothesisId(), group.size(), decisionName, inputHash);
        return true;
    }
}
