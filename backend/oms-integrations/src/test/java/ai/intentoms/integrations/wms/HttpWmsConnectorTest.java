package ai.intentoms.integrations.wms;

import ai.intentoms.integrations.IntegrationsTestBase;
import ai.intentoms.integrations.config.HmacSignatures;
import ai.intentoms.integrations.config.IntegrationsConfigKeys;
import ai.intentoms.integrations.config.IntegrationsStatus;
import ai.intentoms.orchestration.integration.WmsConnector;
import ai.intentoms.order.model.FulfilmentGroup;
import ai.intentoms.order.model.Order;
import ai.intentoms.platform.error.ConflictException;
import ai.intentoms.platform.json.Json;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Section 11 WMS (generic HTTP): signed release / cancel documents, per-node URLs, retries with backoff, outbound log, replay dedup. */
class HttpWmsConnectorTest extends IntegrationsTestBase {
    @Autowired WmsConnector connector;
    @Autowired WmsOutboundLogRepository outbound;
    @Autowired IntegrationsStatus status;

    @SuppressWarnings("unchecked")
    static Map<String, Object> asMap(Object o) { return (Map<String, Object>) o; }

    FulfilmentGroup group(Order o, String id, String node) {
        return new FulfilmentGroup(id, node, "ship_complete", o.lines().stream().map(l -> new FulfilmentGroup.Allocation(l.lineId(), l.sku(), l.qty())).toList(),
            LocalDate.of(2026, 10, 9), 0.93, "ground", null, "planned", "plan_1", null, Map.of());
    }

    @Test
    void httpConnectorIsActiveAndReportedInStatus() {
        assertThat(connector).isInstanceOf(HttpWmsConnector.class);
        Map<String, Object> s = status.snapshot();
        assertThat(((Map<?, ?>) s.get("wms")).get("provider")).isEqualTo("http");
        assertThat(((Map<?, ?>) s.get("carrier")).get("configured")).isEqualTo(true);
        assertThat(((Map<?, ?>) ((Map<?, ?>) s.get("notify")).get("sendgrid")).get("configured")).isEqualTo(true);
        assertThat(Json.write(s)).doesNotContain("SG.test-key").doesNotContain("EZTKtest").doesNotContain("tok");
    }

    @Test
    void postsASignedReleaseDocumentAndDedupesReplays() {
        config.set(IntegrationsConfigKeys.WMS_RELEASE_URL, Json.write(WM.baseUrl() + "/wms/release"));
        config.set(IntegrationsConfigKeys.WMS_WEBHOOK_SECRET, Json.write("wms-s3cret"));
        WM.stubFor(post(urlEqualTo("/wms/release")).willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody("{\"wmsRef\":\"W-1001\",\"accepted\":true}")));
        Order o = capture(webOrder("wms-1"));
        FulfilmentGroup g = group(o, "fg_wms1", "DC-NJ");

        Map<String, Object> ack = connector.sendRelease(o, g);
        assertThat(ack).containsEntry("documentId", "fg_wms1:release").containsEntry("nodeId", "DC-NJ").containsEntry("wmsRef", "W-1001").containsEntry("replayed", false);

        var requests = WM.findAll(postRequestedFor(urlEqualTo("/wms/release")));
        assertThat(requests).hasSize(1);
        String body = requests.get(0).getBodyAsString();
        assertThat(requests.get(0).getHeader("X-Webhook-Signature")).isEqualTo("sha256=" + HmacSignatures.hex("wms-s3cret", body));
        assertThat(requests.get(0).getHeader("X-Tenant-Id")).isEqualTo(tenant);
        assertThat(requests.get(0).getHeader("X-Oms-Document-Id")).isEqualTo("fg_wms1:release");
        Map<String, Object> doc = Json.readMap(body);
        assertThat(doc).containsEntry("documentType", "release").containsEntry("orderId", o.id()).containsEntry("fulfilmentGroupId", "fg_wms1").containsEntry("nodeId", "DC-NJ")
            .containsEntry("service", "ground").containsEntry("priority", 5).containsEntry("promiseDate", "2026-10-09");
        assertThat((List<?>) doc.get("allocations")).hasSize(2);
        assertThat(asMap(((List<?>) doc.get("allocations")).get(0))).containsEntry("sku", "HOOD-GRY-M").containsEntry("qty", 2).containsEntry("hazmat", false);
        assertThat(asMap(doc.get("shipTo"))).containsEntry("zip", "60606").containsEntry("city", "Chicago");

        // a workflow replay does not re-send an acknowledged release
        Map<String, Object> again = connector.sendRelease(o, g);
        assertThat(again).containsEntry("replayed", true);
        assertThat(WM.findAll(postRequestedFor(urlEqualTo("/wms/release")))).hasSize(1);
        assertThat(outbound.forOrder(o.id())).singleElement().satisfies(e -> { assertThat(e.status()).isEqualTo("sent"); assertThat(e.httpStatus()).isEqualTo(200); assertThat(e.attempt()).isEqualTo(1); });

        // cancel goes to the same URL (no cancel URL configured) as a cancel document
        Map<String, Object> cancel = connector.cancelRelease(o, g, "customer cancelled");
        assertThat(cancel).containsEntry("cancelled", true).containsEntry("documentId", "fg_wms1:cancel:1");
        WM.verify(postRequestedFor(urlEqualTo("/wms/release")).withHeader("X-Oms-Document-Type", equalTo("cancel"))
            .withRequestBody(matchingJsonPath("$.documentType", equalTo("cancel"))).withRequestBody(matchingJsonPath("$.reason", equalTo("customer cancelled"))));
    }

    @Test
    void retriesTransientFailuresWithPerNodeUrlAndGivesUpOnPermanentOnes() {
        config.set(IntegrationsConfigKeys.WMS_RELEASE_URL, Json.write(WM.baseUrl() + "/wms/default"));
        config.set(IntegrationsConfigKeys.wmsReleaseUrl("DC-TX"), Json.write(WM.baseUrl() + "/wms/tx"));
        WM.stubFor(post(urlEqualTo("/wms/tx")).inScenario("flaky").whenScenarioStateIs(Scenario.STARTED).willReturn(aResponse().withStatus(503)).willSetStateTo("second"));
        WM.stubFor(post(urlEqualTo("/wms/tx")).inScenario("flaky").whenScenarioStateIs("second").willReturn(aResponse().withStatus(200).withBody("{\"ok\":true}")));
        Order o = capture(webOrder("wms-2"));

        Map<String, Object> ack = connector.sendRelease(o, group(o, "fg_tx", "DC-TX"));
        assertThat(ack).containsEntry("ok", true);
        assertThat(WM.findAll(postRequestedFor(urlEqualTo("/wms/tx")))).hasSize(2);
        assertThat(WM.findAll(postRequestedFor(urlEqualTo("/wms/default")))).isEmpty();
        assertThat(outbound.forGroup("fg_tx", "release")).extracting(e -> e.status()).containsExactly("failed", "sent");
        assertThat(outbound.forGroup("fg_tx", "release").get(0).httpStatus()).isEqualTo(503);
        // no signature header without a secret
        assertThat(WM.findAll(postRequestedFor(urlEqualTo("/wms/tx"))).get(0).getHeader("X-Webhook-Signature")).isNull();

        WM.stubFor(post(urlEqualTo("/wms/default")).willReturn(aResponse().withStatus(400).withBody("{\"error\":\"unknown sku\"}")));
        assertThatThrownBy(() -> connector.sendRelease(o, group(o, "fg_bad", "DC-NV"))).isInstanceOf(ConflictException.class).hasMessageContaining("HTTP 400");
        assertThat(WM.findAll(postRequestedFor(urlEqualTo("/wms/default")))).hasSize(1);
        assertThat(outbound.forGroup("fg_bad", "release")).singleElement().satisfies(e -> { assertThat(e.status()).isEqualTo("gave_up"); assertThat(e.response()).contains("unknown sku"); });

        config.delete(IntegrationsConfigKeys.WMS_RELEASE_URL);
        assertThatThrownBy(() -> connector.sendRelease(o, group(o, "fg_none", "DC-NV"))).isInstanceOf(ConflictException.class).hasMessageContaining("No WMS endpoint");
    }
}
