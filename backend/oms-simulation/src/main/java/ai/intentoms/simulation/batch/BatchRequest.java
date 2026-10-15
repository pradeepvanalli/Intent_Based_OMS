package ai.intentoms.simulation.batch;

import ai.intentoms.simulation.whatif.ScenarioOverrides;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;

/**
 * A batch simulation over the orders placed in {@code [from, to)} (FR-SIM-02, FR-SIM-03).
 *
 * @param overrides   what-if knobs (null = none: the report then shows baseline == simulated)
 * @param sampleLimit maximum orders simulated, newest first (null = module default, see {@code oms.simulation.default-sample-limit})
 * @param label       free-text label shown in the job list
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record BatchRequest(@com.fasterxml.jackson.databind.annotation.JsonDeserialize(using = ai.intentoms.simulation.api.LenientInstant.class) Instant from,
                           @com.fasterxml.jackson.databind.annotation.JsonDeserialize(using = ai.intentoms.simulation.api.LenientInstant.class) Instant to,
                           ScenarioOverrides overrides, Integer sampleLimit, String label) {
    public BatchRequest {
        overrides = overrides == null ? ScenarioOverrides.none() : overrides;
    }

    public static BatchRequest of(Instant from, Instant to, ScenarioOverrides overrides) { return new BatchRequest(from, to, overrides, null, null); }

    public BatchRequest withLabel(String l) { return new BatchRequest(from, to, overrides, sampleLimit, l); }
}
