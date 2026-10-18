package ai.intentoms.learning.hooks;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class LearningDecisionHooksCopyTest {
    @Test @SuppressWarnings("unchecked")
    void contributorsMutateACopyNeverTheCallersInput() {
        Map<String, Object> cand = new LinkedHashMap<>(Map.of("id", "n1", "reject_rate", 0.1));
        List<Object> cands = new ArrayList<>(List.of(cand));
        Map<String, Object> input = new LinkedHashMap<>(Map.of("candidates", cands));

        Map<String, Object> copy = LearningDecisionHooks.deepCopy(input);
        ((Map<String, Object>) ((List<Object>) copy.get("candidates")).get(0)).put("reject_rate", 0.4);

        assertThat(cand.get("reject_rate")).isEqualTo(0.1);
        assertThat(((Map<String, Object>) ((List<Object>) copy.get("candidates")).get(0)).get("reject_rate")).isEqualTo(0.4);
    }
}
