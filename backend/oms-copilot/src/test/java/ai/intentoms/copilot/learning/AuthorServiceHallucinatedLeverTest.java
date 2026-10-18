package ai.intentoms.copilot.learning;

import ai.intentoms.learning.model.PolicyChange;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** §11 "LLM hallucinated lever": a ThresholdChange whose band or from doesn't match the live artifact must be
 * rejected before it ever reaches a Proposal row — see AuthorService.referencesLiveState. */
class AuthorServiceHallucinatedLeverTest {
    private static final Map<String, Object> LIVE_STATE = Map.of("version", 3, "actGte", 0.9, "confirmGte", 0.6);

    @Test
    void acceptsAThresholdChangeThatMatchesTheLiveValue() {
        var change = new PolicyChange.ThresholdChange("fulfilment_intent", "act", 0.9, 0.92);
        assertThat(AuthorService.referencesLiveState(change, LIVE_STATE)).isTrue();
    }

    @Test
    void rejectsAThresholdChangeWithAHallucinatedFromValue() {
        var change = new PolicyChange.ThresholdChange("fulfilment_intent", "act", 0.5, 0.6); // live actGte is 0.9, not 0.5
        assertThat(AuthorService.referencesLiveState(change, LIVE_STATE)).isFalse();
    }

    @Test
    void rejectsAThresholdChangeForABandThatDoesNotExist() {
        var change = new PolicyChange.ThresholdChange("fulfilment_intent", "escalate", 0.9, 0.95); // no such band on this artifact
        assertThat(AuthorService.referencesLiveState(change, LIVE_STATE)).isFalse();
    }

    @Test
    void nonThresholdChangesPassThrough() {
        var change = new PolicyChange.RuleAdd("fulfilment_intent", "order.total > 1000", "escalate", "high value");
        assertThat(AuthorService.referencesLiveState(change, LIVE_STATE)).isTrue();
    }
}
