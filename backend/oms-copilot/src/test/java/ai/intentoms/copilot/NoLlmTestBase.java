package ai.intentoms.copilot;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** {@link SeededTestBase} with the copilot's model access disabled, exercising every deterministic fallback. */
public abstract class NoLlmTestBase extends SeededTestBase {
    @DynamicPropertySource
    static void disableLlm(DynamicPropertyRegistry r) {
        r.add("oms.copilot.enabled", () -> "false");
    }
}
