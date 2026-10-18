package ai.intentoms.simulation.replay;

import ai.intentoms.decision.definition.DecisionDefinitionRepository;
import ai.intentoms.decision.model.DecisionDefinition;
import ai.intentoms.decision.model.DecisionOptions;
import ai.intentoms.decision.model.DecisionRecord;
import ai.intentoms.decision.model.DecisionResult;
import ai.intentoms.decision.model.Mode;
import ai.intentoms.decision.runtime.DecisionRuntime;
import ai.intentoms.decision.runtime.DecisionService;
import ai.intentoms.learning.learner.SourcingPolicyBandit;
import ai.intentoms.platform.error.ValidationException;
import ai.intentoms.platform.ledger.Actor;
import org.springframework.stereotype.Component;

import java.util.Set;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Re-runs an order's recorded decisions against the exact inputs they saw (design principle 4, FR-SIM-01, FR-PRM-03).
 * Records store their {@code input}; the re-run goes through {@link DecisionRuntime#evaluate} with a specific definition
 * version in REPLAY or SIMULATION mode, which never acts, escalates or caches but still persists a record flagged with
 * that mode so reports can compare.
 */
@Component
public class DecisionReplayer {
    /** Decision names written directly to {@code decision_record} by a learner outside the normal decision fabric
     * (SOO's propensity logging, requirements doc FS-01) — these have no {@code decision_definition} to replay
     * against by design (they're a reinforcement-learning arm choice, not a risk-tiered judgment call with golden
     * sets and lint), so order-level replay skips them rather than treating a missing definition as a data-integrity
     * error. */
    private static final Set<String> NOT_REPLAYABLE = Set.of(SourcingPolicyBandit.DECISION_NAME);

    private final DecisionService decisions;
    private final DecisionRuntime runtime;
    private final DecisionDefinitionRepository definitions;

    public DecisionReplayer(DecisionService decisions, DecisionRuntime runtime, DecisionDefinitionRepository definitions) {
        this.decisions = decisions;
        this.runtime = runtime;
        this.definitions = definitions;
    }

    /** LIVE records of the order subject, oldest first; {@code newestPerDecision} keeps only the latest per decision name. */
    public List<DecisionRecord> recordsFor(String orderId, boolean newestPerDecision) {
        List<DecisionRecord> live = new ArrayList<>(decisions.forSubject("order", orderId).stream().filter(r -> r.mode() == Mode.LIVE && !NOT_REPLAYABLE.contains(r.decision())).toList());
        live.sort((a, b) -> a.createdAt().compareTo(b.createdAt()));
        if (!newestPerDecision) return live;
        Map<String, DecisionRecord> newest = new LinkedHashMap<>();
        for (DecisionRecord r : live) newest.put(r.decision(), r);
        return new ArrayList<>(newest.values());
    }

    /**
     * Definition to replay a record with: the override version when given, else the current live version, else the
     * record's own version. Throws {@code ValidationException} for an unknown override version.
     */
    public DecisionDefinition target(DecisionRecord record, Map<String, Integer> overrides) {
        Integer override = overrides == null ? null : overrides.get(record.decision());
        if (override != null) {
            return definitions.find(record.decision(), override).map(DecisionDefinitionRepository.Row::definition)
                .orElseThrow(() -> new ValidationException("Decision " + record.decision() + " has no version " + override, Map.of("decision", record.decision(), "version", override)));
        }
        Optional<DecisionDefinitionRepository.Row> live = definitions.byStatus(record.decision(), "live").stream().findFirst();
        if (live.isPresent()) return live.get().definition();
        return definitions.find(record.decision(), record.version()).map(DecisionDefinitionRepository.Row::definition)
            .orElseThrow(() -> new ValidationException("Decision " + record.decision() + " version " + record.version() + " no longer exists"));
    }

    /** Re-run one record's stored input against {@code def}. */
    public DecisionResult rerun(DecisionRecord record, DecisionDefinition def, Mode mode, String actorId) {
        DecisionOptions opts = new DecisionOptions(record.subjectType(), record.subjectId(), record.correlationId() == null ? record.subjectId() : record.correlationId(), mode, def.version(),
            Actor.system(actorId), Map.of());
        return runtime.evaluate(def, record.input(), opts);
    }

    /** Replay every selected record of an order; the result list is in record order. */
    public List<ReplayResult.DecisionReplay> replay(String orderId, Map<String, Integer> overrides, boolean allRecords, Mode mode, String actorId) {
        List<ReplayResult.DecisionReplay> out = new ArrayList<>();
        for (DecisionRecord r : recordsFor(orderId, !allRecords)) {
            DecisionDefinition def = target(r, overrides);
            DecisionResult res = rerun(r, def, mode, actorId);
            out.add(ReplayResult.DecisionReplay.of(r.id(), res.recordId(), r.decision(), r.subjectId(), r.version(), def.version(), Outcome.of(r), Outcome.of(res)));
        }
        return out;
    }
}
