package ai.intentoms.copilot.support;

import ai.intentoms.platform.json.Json;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.MappingBuilder;
import com.github.tomakehurst.wiremock.stubbing.Scenario;

import java.util.List;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;

/** Canned OpenAI-compatible chat completion responses keyed by the strict schema name the client requests. */
public final class LlmStubs {
    /** Keeps explicit nulls (the platform mapper drops them), as a strict-schema model answer does. */
    public static final com.fasterxml.jackson.databind.ObjectMapper WITH_NULLS = new com.fasterxml.jackson.databind.ObjectMapper().registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule()).disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private LlmStubs() {}

    public static String json(Object o) {
        try { return WITH_NULLS.writeValueAsString(o); } catch (com.fasterxml.jackson.core.JsonProcessingException e) { throw new IllegalArgumentException(e); }
    }

    public static String completion(Object content) {
        return Json.write(Map.of("id", "cmpl_1", "model", "anthropic/claude-sonnet-4.5",
            "choices", List.of(Map.of("index", 0, "finish_reason", "stop", "message", Map.of("role", "assistant", "content", content instanceof String s ? s : json(content)))),
            "usage", Map.of("prompt_tokens", 1200, "completion_tokens", 300, "cost", 0.0081)));
    }

    public static MappingBuilder forSchema(String schemaName) {
        return post(urlEqualTo("/chat/completions")).withRequestBody(matchingJsonPath("$.response_format.json_schema.name", equalTo(schemaName)));
    }

    /** Stub one schema with one canned object. */
    public static void stub(WireMockServer wm, String schemaName, Object content) {
        wm.stubFor(forSchema(schemaName).willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody(completion(content))));
    }

    /** Stub a schema with successive responses (first call gets the first object, and so on). */
    public static void stubSequence(WireMockServer wm, String schemaName, Object... contents) {
        String scenario = "seq-" + schemaName;
        String state = Scenario.STARTED;
        for (int i = 0; i < contents.length; i++) {
            String next = i == contents.length - 1 ? "done-" + schemaName : "step-" + schemaName + "-" + (i + 1);
            wm.stubFor(forSchema(schemaName).inScenario(scenario).whenScenarioStateIs(state)
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody(completion(contents[i]))).willSetStateTo(next));
            state = next;
        }
    }
}
