package ai.intentoms.agent.mandate;

import java.util.List;

/** Outcome of {@link MandateService#verify}: valid or the reason codes that failed; the mandate when it exists. */
public record MandateCheck(boolean valid, List<String> reasons, Mandate mandate) {
    public MandateCheck { reasons = reasons == null ? List.of() : List.copyOf(reasons); }
    public static MandateCheck ok(Mandate m) { return new MandateCheck(true, List.of(), m); }
}
