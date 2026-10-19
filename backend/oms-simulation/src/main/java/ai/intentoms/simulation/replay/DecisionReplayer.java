package ai.intentoms.simulation.replay;

import ai.intentoms.decision.definition.DecisionDefinitionRepository;
import ai.intentoms.decision.model.DecisionDefinition;
import ai.intentoms.decision.model.DecisionOptions;
import ai.intentoms.decision.model.DecisionRecord;
import ai.intentoms.decision.model.DecisionResult;
import ai.intentoms.decision.model.Mode;
import ai.intentoms.decision.record.DecisionRecordRepository;
import ai.intentoms.decision.runtime.DecisionRuntime;
import ai.intentoms.decision.runtime.DecisionService;
import ai.intentoms.platform.error.ValidationException;
import ai.intentoms.platform.ids.Ids;
import ai.intentoms.platform.ledger.Actor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Re-runs an order's recorded decisions against the exact inputs they saw (design principle 4, FR-SIM-01, FR-PRM-03,
 * NF-04). Without an override a record is reproduced on its own recorded version: its recorded input (which carries the
 * learned facts it used), its recorded calibration, provider route and experiment arm, so the replay matches the
 * original even after live versions and facts have moved on. With an override version it is re-evaluated through that
 * version, which is the "what would the proposed policy have done" comparison. The sourcing-policy bandit, which logs
 * outside the decision fabric, is replayed from its stored seed. Replays never act, escalate or cache; each persists a
 * record in REPLAY or SIMULATION mode pointing at the original.
 */
@Component
public class DecisionReplayer {
    private final DecisionService decisions;
    private final DecisionRuntime runtime;
    private final DecisionDefinitionRepository definitions;
    private final DecisionRecordRepository records;

    public DecisionReplayer(DecisionService decisions, DecisionRuntime runtime, DecisionDefinitionRepository definitions, DecisionRecordRepository records) {
        this.decisions = decisions;
        this.runtime = runtime;
        this.definitions = definitions;
        this.records = records;
    }

    /** LIVE records of the order subject, oldest first; {@code newestPerDecision} keeps only the latest per decision name. */
    public List<DecisionRecord> recordsFor(String orderId, boolean newestPerDecision) {
        List<DecisionRecord> live = new ArrayList<>(decisions.forSubject("order", orderId).stream().filter(r -> r.mode() == Mode.LIVE).toList());
        live.sort((a, b) -> a.createdAt().compareTo(b.createdAt()));
        if (!newestPerDecision) return live;
        Map<String, DecisionRecord> newest = new LinkedHashMap<>();
        for (DecisionRecord r : live) newest.put(r.decision(), r);
        return new ArrayList<>(newest.values());
    }

    /**
     * Definition to replay a record with: the override version when given, else the record's own recorded version, else
     * (when that version no longer exists) the current live version. Throws {@code ValidationException} for an unknown
     * override version.
     */
    public DecisionDefinition target(DecisionRecord record, Map<String, Integer> overrides) {
        Integer override = overrides == null ? null : overrides.get(record.decision());
        if (override != null) {
            return definitions.find(record.decision(), override).map(DecisionDefinitionRepository.Row::definition)
                .orElseThrow(() -> new ValidationException("Decision " + record.decision() + " has no version " + override, Map.of("decision", record.decision(), "version", override)));
        }
        Optional<DecisionDefinitionRepository.Row> recorded = definitions.find(record.decision(), record.version());
        if (recorded.isPresent()) return recorded.get().definition();
        return definitions.byStatus(record.decision(), "live").stream().findFirst().map(DecisionDefinitionRepository.Row::definition)
            .orElseThrow(() -> new ValidationException("Decision " + record.decision() + " version " + record.version() + " no longer exists"));
    }

    /** Re-run one record: reproduced exactly on its own version, re-evaluated through any other version. */
    public DecisionResult rerun(DecisionRecord record, DecisionDefinition def, Mode mode, String actorId) {
        if (def.version() == record.version()) return runtime.reproduce(def, record, mode, Actor.system(actorId));
        DecisionOptions opts = new DecisionOptions(record.subjectType(), record.subjectId(), record.correlationId() == null ? record.subjectId() : record.correlationId(), mode, def.version(),
            Actor.system(actorId), Map.of());
        return runtime.evaluate(def, record.input(), opts);
    }

    /** Replay every selected record of an order; the result list is in record order. */
    public List<ReplayResult.DecisionReplay> replay(String orderId, Map<String, Integer> overrides, boolean allRecords, Mode mode, String actorId) {
        List<ReplayResult.DecisionReplay> out = new ArrayList<>();
        for (DecisionRecord r : recordsFor(orderId, !allRecords)) {
            if (BanditReplay.isBandit(r)) { out.add(replayBandit(r, mode, actorId)); continue; }
            DecisionDefinition def = target(r, overrides);
            DecisionResult res = rerun(r, def, mode, actorId);
            out.add(ReplayResult.DecisionReplay.of(r.id(), res.recordId(), r.decision(), r.subjectId(), r.version(), def.version(), Outcome.of(r), Outcome.of(res)));
        }
        return out;
    }

    /** The bandit's draw re-derived from its seed, persisted as a replay record pointing at the original. */
    ReplayResult.DecisionReplay replayBandit(DecisionRecord r, Mode mode, String actorId) {
        BanditReplay.Result b = BanditReplay.replay(r);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("replayOf", r.id());
        details.put("reproduced", b.reproduced());
        if (b.sampled() != null) details.put("sampledArm", b.sampled());
        details.put("mode", mode.name());
        String id = Ids.prefixed("dr");
        records.insert(new DecisionRecord(id, r.decision(), r.version(), mode, r.subjectType(), r.subjectId(), r.correlationId(), r.layer(), r.band(), r.action(),
            b.applied(), Map.of(), r.confidence(), r.provider(), r.modelVersion(), r.inputHash(), r.state(), r.input(), List.of(), List.of(), 0, 0, 0, false, null,
            Actor.system(actorId).ref(), details, null, null, null, null, Map.of(), Instant.now(), r.contextFeatures(), r.arms(), b.applied(), r.propensity(), r.policyVersion()));
        Outcome before = Outcome.of(r);
        Outcome after = new Outcome(b.applied(), r.band(), r.confidence(), r.layer(), r.action(), false, 0, 0);
        return ReplayResult.DecisionReplay.of(r.id(), id, r.decision(), r.subjectId(), r.version(), r.version(), before, after);
    }
}
