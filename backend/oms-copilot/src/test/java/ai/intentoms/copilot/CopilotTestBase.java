package ai.intentoms.copilot;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** {@link SeededTestBase} plus a WireMock stand-in for the OpenAI-compatible endpoint (canned answers via {@code LlmStubs}). */
public abstract class CopilotTestBase extends SeededTestBase {
    protected static final WireMockServer LLM = new WireMockServer(WireMockConfiguration.options().dynamicPort());

    static { LLM.start(); }

    @DynamicPropertySource
    static void mockLlm(DynamicPropertyRegistry r) {
        r.add("oms.copilot.enabled", () -> "true");
        r.add("oms.copilot.api-key", () -> "sk-or-test-key");
        r.add("oms.copilot.base-url", () -> LLM.baseUrl());
        r.add("oms.copilot.max-retries", () -> "1");
    }

    @BeforeEach
    void resetLlm() { LLM.resetAll(); }
}
