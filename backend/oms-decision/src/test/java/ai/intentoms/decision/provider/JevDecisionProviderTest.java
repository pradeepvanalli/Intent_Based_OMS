package ai.intentoms.decision.provider;

import ai.intentoms.decision.TestYaml;
import ai.intentoms.decision.definition.DecisionDefinitionParser;
import ai.intentoms.decision.model.Answer;
import ai.intentoms.decision.model.DecisionDefinition;
import ai.intentoms.platform.json.Json;
import ai.intentoms.platform.observability.Metrics;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class JevDecisionProviderTest {
    static WireMockServer wm;
    DecisionDefinition def = new DecisionDefinitionParser().parse(TestYaml.INTENT);
    JevDecisionProvider provider;

    static final String RESPONSE = """
        {"model":"jev-1.13.0","answers":{
          "intent":{"type":"choice","choice":"ship_complete","probabilities":{"ship_complete":0.88,"ship_as_available":0.05,"earliest_partial":0.03,"pickup_consolidated":0.02,"unclear":0.02},"confidence":0.81},
          "split_ok":{"type":"noul","noul":0.15},
          "is_gift":{"type":"noul","noul":0.95}},
         "usage":{"input_tokens":312,"output_tokens":48}}
        """;

    @BeforeAll static void start() { wm = new WireMockServer(WireMockConfiguration.options().dynamicPort()); wm.start(); }
    @AfterAll static void stop() { wm.stop(); }

    @BeforeEach
    void setUp() {
        wm.resetAll();
        JevProperties props = new JevProperties(true, "sk-test", wm.baseUrl(), "jev-latest", 800, 0.042, 0);
        provider = new JevDecisionProvider(props, RestClient.builder().requestFactory(new JdkClientHttpRequestFactory()), new Metrics(new SimpleMeterRegistry()));
    }

    @Test
    void mapsRequestAndResponseExactly() {
        wm.stubFor(post(urlEqualTo("/v1/systemone")).willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody(RESPONSE)));
        Map<String, Object> state = Map.of("order", Map.of("channel", "web", "notes", "gift"));
        ProviderResponse res = provider.ask(new ProviderRequest(state, def.questions(), 800, null));

        wm.verify(postRequestedFor(urlEqualTo("/v1/systemone"))
            .withHeader("Authorization", equalTo("Bearer sk-test"))
            .withHeader("Content-Type", equalTo("application/json"))
            .withRequestBody(matchingJsonPath("$.model", equalTo("jev-latest")))
            .withRequestBody(matchingJsonPath("$.state.order.channel", equalTo("web")))
            .withRequestBody(matchingJsonPath("$.questions.intent.type", equalTo("choice")))
            .withRequestBody(matchingJsonPath("$.questions.intent.criteria.ship_complete", equalTo("Everything together, even if it arrives later")))
            .withRequestBody(matchingJsonPath("$.questions.split_ok.type", equalTo("noul")))
            .withRequestBody(matchingJsonPath("$.questions.split_ok.instructions", equalTo("This buyer would accept the order in more than one package."))));
        String body = wm.getAllServeEvents().get(0).getRequest().getBodyAsString();
        assertThat(Json.readMap(body)).containsOnlyKeys("state", "model", "questions");
        Map<String, Object> splitOk = Json.toMap(((Map<?, ?>) Json.readMap(body).get("questions")).get("split_ok"));
        assertThat(splitOk).doesNotContainKey("criteria");

        Answer intent = res.answers().get("intent");
        assertThat(intent.value()).isEqualTo("ship_complete");
        assertThat(intent.confidence()).isEqualTo(0.81);
        assertThat(intent.probabilities()).containsEntry("ship_complete", 0.88);
        assertThat(res.answers().get("split_ok").confidence()).isCloseTo(0.7, within(1e-9));   // |2*0.15-1|
        assertThat(res.answers().get("is_gift").confidence()).isCloseTo(0.9, within(1e-9));
        assertThat(res.answers().get("is_gift").asBoolean()).isTrue();
        assertThat(res.modelVersion()).isEqualTo("jev-1.13.0");
        assertThat(res.inputTokens()).isEqualTo(312);
        assertThat(res.costUsd()).isCloseTo(312 * 0.042 / 1_000_000, within(1e-12));
    }

    @Test
    void scoreQuestionsSendCriteriaListAndParseLegend() {
        DecisionDefinition score = new DecisionDefinitionParser().parse(TestYaml.demo("sla_breach_risk"));
        wm.stubFor(post(urlEqualTo("/v1/systemone")).willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody("""
            {"model":"jev-1.13.0","answers":{"breach_risk":{"type":"score","score":3.05,"legend":{"0":"Very unlikely","3":"Likely"},"probabilities":{"3":0.9,"4":0.1},"confidence":0.88}},"usage":{"input_tokens":100,"output_tokens":5}}
            """)));
        ProviderResponse res = provider.ask(new ProviderRequest(Map.of("order", Map.of("state", "released")), score.questions(), 800, null));
        wm.verify(postRequestedFor(urlEqualTo("/v1/systemone")).withRequestBody(matchingJsonPath("$.questions.breach_risk.criteria[0]", equalTo("Very unlikely to breach, well ahead of promise with healthy node and carrier"))));
        assertThat(res.answers().get("breach_risk").asDouble()).isEqualTo(3.05);
        assertThat(res.answers().get("breach_risk").legend()).containsEntry("3", "Likely");
    }

    @Test
    void retriesOnceOn429ThenSucceeds() {
        wm.stubFor(post(urlEqualTo("/v1/systemone")).inScenario("retry").whenScenarioStateIs(Scenario.STARTED)
            .willReturn(aResponse().withStatus(429).withBody("{\"error\":\"rate limited\"}")).willSetStateTo("second"));
        wm.stubFor(post(urlEqualTo("/v1/systemone")).inScenario("retry").whenScenarioStateIs("second")
            .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody(RESPONSE)));
        ProviderResponse res = provider.ask(new ProviderRequest(Map.of(), def.questions(), 800, null));
        assertThat(res.answers().get("intent").value()).isEqualTo("ship_complete");
        wm.verify(2, postRequestedFor(urlEqualTo("/v1/systemone")));
    }

    @Test
    void timeoutBecomesRetryableProviderException() {
        wm.stubFor(post(urlEqualTo("/v1/systemone")).willReturn(aResponse().withStatus(200).withFixedDelay(1500).withBody(RESPONSE)));
        long start = System.currentTimeMillis();
        assertThatThrownBy(() -> provider.ask(new ProviderRequest(Map.of(), def.questions(), 200, null)))
            .isInstanceOf(ProviderException.class).matches(e -> ((ProviderException) e).isTimeout());
        assertThat(System.currentTimeMillis() - start).isLessThan(1200);
    }

    @Test
    void unknownChoiceIsRejected() {
        wm.stubFor(post(urlEqualTo("/v1/systemone")).willReturn(aResponse().withStatus(200).withBody(RESPONSE.replace("\"choice\":\"ship_complete\"", "\"choice\":\"teleport\""))));
        assertThatThrownBy(() -> provider.ask(new ProviderRequest(Map.of(), def.questions(), 800, null))).isInstanceOf(ProviderException.class).hasMessageContaining("teleport");
    }

    @Test
    void non2xxIsProviderException() {
        wm.stubFor(post(urlEqualTo("/v1/systemone")).willReturn(aResponse().withStatus(401).withBody("{\"error\":\"unauthorized\"}")));
        assertThatThrownBy(() -> provider.ask(new ProviderRequest(Map.of(), def.questions(), 800, null))).isInstanceOf(ProviderException.class)
            .matches(e -> ((ProviderException) e).status() == 401 && !((ProviderException) e).retryable());
    }

    @Test
    void unavailableWhenDisabledOrNoKey() {
        assertThat(new JevDecisionProvider(new JevProperties(false, "k", wm.baseUrl(), null, 0, 0, 0), RestClient.builder(), new Metrics(new SimpleMeterRegistry())).available()).isFalse();
        assertThat(new JevDecisionProvider(new JevProperties(true, "", wm.baseUrl(), null, 0, 0, 0), RestClient.builder(), new Metrics(new SimpleMeterRegistry())).available()).isFalse();
        assertThat(provider.available()).isTrue();
    }
}
