package ai.intentoms.simulation.replay;

import java.util.List;
import java.util.Map;

/**
 * Outcome of folding an order's recorded transitions through a proposed state-model version (FR-SIM-01).
 *
 * @param currentVersion      version the entities were pinned to when the transitions were recorded
 * @param proposedVersion     version the transitions were replayed through
 * @param transitionsReplayed recorded transitions considered (header roll-ups excluded)
 * @param blockedTransitions  recorded triggers with no transition from the simulated state in the proposed model
 * @param unmappedStates      recorded states the proposed model does not declare
 * @param finalStateBefore    header status as recorded in the ledger
 * @param finalStateAfter     header status derived from the simulated line buckets with the proposed/current roll-up rules
 * @param entityStatesBefore  per entity: recorded dominant state
 * @param entityStatesAfter   per entity: simulated dominant state under the proposed model
 */
public record StateModelReplayResult(String modelId, int currentVersion, int proposedVersion, int transitionsReplayed, int blockedTransitions, int retargeted,
                                     List<String> unmappedStates, String finalStateBefore, String finalStateAfter, Map<String, String> entityStatesBefore,
                                     Map<String, String> entityStatesAfter, Map<String, Map<String, Integer>> simulatedBuckets, List<Step> steps) {
    public StateModelReplayResult {
        unmappedStates = unmappedStates == null ? List.of() : List.copyOf(unmappedStates);
        entityStatesBefore = entityStatesBefore == null ? Map.of() : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(entityStatesBefore));
        entityStatesAfter = entityStatesAfter == null ? Map.of() : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(entityStatesAfter));
        simulatedBuckets = simulatedBuckets == null ? Map.of() : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(simulatedBuckets));
        steps = steps == null ? List.of() : List.copyOf(steps);
    }

    public static final String APPLIED = "applied";
    public static final String BLOCKED = "blocked";
    public static final String UNMAPPED = "unmapped";
    public static final String SKIPPED = "skipped";

    /**
     * One recorded transition replayed.
     *
     * @param status applied | blocked | unmapped (applied via a different source state) | skipped (header roll-up)
     */
    public record Step(String entityId, String trigger, String from, String to, String simulatedFrom, String simulatedTo, int qty, String status, String reason) {
        public boolean blocked() { return BLOCKED.equals(status); }
    }

    public StateModelReplayResult withHeader(String before, String after) {
        return new StateModelReplayResult(modelId, currentVersion, proposedVersion, transitionsReplayed, blockedTransitions, retargeted, unmappedStates, before, after,
            entityStatesBefore, entityStatesAfter, simulatedBuckets, steps);
    }
}
