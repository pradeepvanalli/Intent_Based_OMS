package ai.intentoms.simulation.replay;

import ai.intentoms.simulation.whatif.ScenarioOverrides;
import ai.intentoms.sourcing.model.SimulationOverrides;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.Map;

/**
 * What to replay an order against (FR-SIM-01; UI contract item 13).
 *
 * @param decisionVersionOverrides decision name → definition version to re-run with (absent = current live version)
 * @param stateModelVersion        proposed state-model version to fold the recorded transitions through (null = skip)
 * @param stateModelId             model the version refers to (null = the order's line model)
 * @param overrides                what-if knobs for the re-sourcing comparison (null = none); only its sourcing knobs are used here
 * @param allRecords               replay every LIVE record of the order rather than the newest per decision
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ReplayOptions(Map<String, Integer> decisionVersionOverrides, Integer stateModelVersion, String stateModelId, ScenarioOverrides overrides, boolean allRecords) {
    public ReplayOptions {
        decisionVersionOverrides = decisionVersionOverrides == null ? Map.of() : Map.copyOf(decisionVersionOverrides);
    }

    public static ReplayOptions current() { return new ReplayOptions(Map.of(), null, null, null, false); }

    public static ReplayOptions decisions(Map<String, Integer> versions) { return new ReplayOptions(versions, null, null, null, false); }

    public boolean hasSourcingOverrides() { return overrides != null && overrides.affectsSourcing(); }

    public SimulationOverrides effectiveOverrides() { return overrides == null ? SimulationOverrides.none() : overrides.sourcing(); }
}
