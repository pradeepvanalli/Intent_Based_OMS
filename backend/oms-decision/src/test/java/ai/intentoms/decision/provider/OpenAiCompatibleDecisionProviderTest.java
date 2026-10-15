package ai.intentoms.decision.provider;

import ai.intentoms.decision.TestYaml;
import ai.intentoms.decision.definition.DecisionDefinitionParser;
import ai.intentoms.decision.model.DecisionDefinition;
import ai.intentoms.platform.observability.Metrics;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

class OpenAiCompatibleDecisionProviderTest {
    static WireMockServer wm;

    @BeforeAll static void start() { wm = new WireMockServer(WireMockConfiguration.options().dynamicPort()); wm.start(); }
    @AfterAll static void stop() { wm.stop(); }

    @Test
    void usesStrictJsonSchemaResponseFormat() {
        DecisionDefinition def = new DecisionDefinitionParser().parse(TestYaml.INTENT);
        LlmProperties props = new LlmProperties("openai", null, null, null, "sk-oa", wm.baseUrl(), "gpt-5", 8000, 0, 0, 0, 0);
        var provider = new OpenAiCompatibleDecisionProvider(props, RestClient.builder().requestFactory(new JdkClientHttpRequestFactory()), new Metrics(new SimpleMeterRegistry()));
        wm.stubFor(post(urlEqualTo("/chat/completions")).willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody("""
            {"model":"gpt-5","choices":[{"message":{"role":"assistant","content":"{\\"intent\\":{\\"value\\":\\"pickup_consolidated\\",\\"confidence\\":0.9},\\"split_ok\\":{\\"value\\":false,\\"probability\\":0.2,\\"confidence\\":0.6},\\"is_gift\\":{\\"value\\":false,\\"probability\\":0.05,\\"confidence\\":0.9}}"}}],
             "usage":{"prompt_tokens":500,"completion_tokens":40}}
            """)));
        ProviderResponse res = provider.ask(new ProviderRequest(Map.of("order", Map.of("notes", "collect in store")), def.questions(), 5000, null));
        wm.verify(postRequestedFor(urlEqualTo("/chat/completions"))
            .withHeader("Authorization", equalTo("Bearer sk-oa"))
            .withRequestBody(matchingJsonPath("$.response_format.type", equalTo("json_schema")))
            .withRequestBody(matchingJsonPath("$.response_format.json_schema.strict", equalTo("true")))
            .withRequestBody(matchingJsonPath("$.response_format.json_schema.schema.properties.intent.properties.value.enum[3]", equalTo("pickup_consolidated"))));
        assertThat(res.answers().get("intent").value()).isEqualTo("pickup_consolidated");
        assertThat(res.answers().get("split_ok").asBoolean()).isFalse();
        assertThat(res.inputTokens()).isEqualTo(500);
        assertThat(provider.available()).isTrue();
    }
}
