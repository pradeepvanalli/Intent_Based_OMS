package ai.intentoms.simulation.whatif;

import ai.intentoms.sourcing.model.SimulationOverrides;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What-if knobs of a replay or batch simulation (FR-SIM-01..03; UI contract item 13). The sourcing knobs map onto
 * {@link SimulationOverrides} (closed nodes excluded from candidates, carrier outage = +2 transit days / −0.15 on-time,
 * tariff delta multiplies landed cost); the decision and state-model knobs select proposed versions to replay through.
 * {@code carrierOutage} accepts a boolean or, as the UI sends it, a list of affected carriers.
 *
 * @param closedNodes              node ids treated as closed
 * @param carrierOutage            whether a carrier outage is simulated
 * @param outageCarriers           the carriers named by the caller (informational; the rate card is carrier-agnostic)
 * @param tariffDelta              relative landed-cost change, e.g. 0.25 = +25 %
 * @param decisionVersionOverrides decision name → version to re-run with
 * @param stateModelVersion        proposed state-model version (null = current)
 * @param stateModelId             model the version refers to (null = the order's line model)
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ScenarioOverrides(Set<String> closedNodes, boolean carrierOutage, List<String> outageCarriers, double tariffDelta, Map<String, Integer> decisionVersionOverrides,
                                Integer stateModelVersion, String stateModelId) {
    public ScenarioOverrides {
        closedNodes = closedNodes == null ? Set.of() : Set.copyOf(closedNodes);
        outageCarriers = outageCarriers == null ? List.of() : List.copyOf(outageCarriers);
        decisionVersionOverrides = decisionVersionOverrides == null ? Map.of() : Map.copyOf(decisionVersionOverrides);
    }

    @JsonCreator
    public static ScenarioOverrides fromJson(@JsonProperty("closedNodes") Collection<String> closedNodes, @JsonProperty("carrierOutage") Object carrierOutage,
                                             @JsonProperty("outageCarriers") Collection<String> outageCarriers, @JsonProperty("tariffDelta") Double tariffDelta,
                                             @JsonProperty("decisionVersionOverrides") Map<String, Integer> decisionVersionOverrides,
                                             @JsonProperty("stateModelVersion") Integer stateModelVersion, @JsonProperty("stateModelId") String stateModelId) {
        boolean outage = false;
        List<String> carriers = new ArrayList<>(outageCarriers == null ? List.of() : outageCarriers);
        if (carrierOutage instanceof Boolean b) outage = b;
        else if (carrierOutage instanceof Collection<?> c) { outage = !c.isEmpty(); for (Object o : c) if (o != null) carriers.add(o.toString()); }
        else if (carrierOutage instanceof String s) { outage = !s.isBlank() && !"false".equalsIgnoreCase(s); if (outage && !"true".equalsIgnoreCase(s)) carriers.add(s); }
        else if (carrierOutage != null) outage = true;
        return new ScenarioOverrides(closedNodes == null ? Set.of() : new LinkedHashSet<>(closedNodes), outage, carriers, tariffDelta == null ? 0.0 : tariffDelta,
            decisionVersionOverrides, stateModelVersion, stateModelId);
    }

    public static ScenarioOverrides none() { return new ScenarioOverrides(Set.of(), false, List.of(), 0.0, Map.of(), null, null); }

    public static ScenarioOverrides nodeClosure(Collection<String> nodeIds) { return new ScenarioOverrides(new LinkedHashSet<>(nodeIds), false, List.of(), 0.0, Map.of(), null, null); }

    public static ScenarioOverrides carrierOutage(Collection<String> carriers) { return new ScenarioOverrides(Set.of(), true, carriers == null ? List.of() : new ArrayList<>(carriers), 0.0, Map.of(), null, null); }

    public static ScenarioOverrides tariffChange(double delta) { return new ScenarioOverrides(Set.of(), false, List.of(), delta, Map.of(), null, null); }

    /** The sourcing-side knobs the sourcing module consults through {@code SimulationContext}. */
    public SimulationOverrides sourcing() { return new SimulationOverrides(closedNodes, carrierOutage, tariffDelta); }

    public boolean affectsSourcing() { return !closedNodes.isEmpty() || carrierOutage || tariffDelta != 0.0; }

    public boolean affectsDecisions() { return !decisionVersionOverrides.isEmpty(); }

    public boolean affectsStateModel() { return stateModelVersion != null; }

    public boolean isEmpty() { return !affectsSourcing() && !affectsDecisions() && !affectsStateModel(); }
}
