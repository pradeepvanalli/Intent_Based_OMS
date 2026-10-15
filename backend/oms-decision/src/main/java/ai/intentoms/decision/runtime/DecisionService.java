package ai.intentoms.decision.runtime;

import ai.intentoms.decision.model.DecisionOptions;
import ai.intentoms.decision.model.DecisionRecord;
import ai.intentoms.decision.model.DecisionResult;
import ai.intentoms.decision.model.DecisionTrace;
import ai.intentoms.decision.model.Invocation;

import java.util.List;
import java.util.Map;

/**
 * The decision fabric entry point other modules call (FR-DEC-01..11, GR-01..08). {@code decide} runs the layered
 * evaluation L0 rules, L1 System One, L2 LLM, L3 human and always returns: on any AI failure the L0 default is returned
 * with layer DEFAULT (GR-07), never an exception, so callers such as order capture can never block on AI.
 */
public interface DecisionService {
    DecisionResult decide(String decisionName, Map<String, Object> input, DecisionOptions opts);

    /** Batch mode (FR-DEC-07): evaluates each invocation and returns results in the same order. */
    List<DecisionResult> decideAll(List<Invocation> batch);

    DecisionRecord record(String recordId);

    List<DecisionRecord> forSubject(String subjectType, String subjectId);

    /** FR-XAI-01: the complete trace of one decision. */
    DecisionTrace why(String recordId);

    /** FR-DEC-10: attach an outcome label ("correct": {value: true}, "promise_met", "return_abusive", ...). */
    void label(String recordId, String label, Map<String, Object> details);

    /** GR-13: human override with a mandatory reason; requires permission decision.override. */
    DecisionRecord override(String recordId, Object newValue, String reason);
}
