package ai.intentoms.integrations.carrier;

import ai.intentoms.integrations.IntegrationsTestBase;
import ai.intentoms.integrations.config.HmacSignatures;
import ai.intentoms.integrations.config.IntegrationsConfigKeys;
import ai.intentoms.orchestration.integration.CarrierConnector;
import ai.intentoms.orchestration.shipment.ShipmentRecord;
import ai.intentoms.orchestration.shipment.ShipmentService;
import ai.intentoms.order.model.Order;
import ai.intentoms.platform.json.Json;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Section 11 Carriers (EasyPost): create + buy cheapest matching rate, label stored and exposed, tracker webhook drives the shipment state. */
class EasyPostIntegrationTest extends IntegrationsTestBase {
    @Autowired CarrierConnector connector;
    @Autowired ShipmentService shipments;
    @Autowired CarrierLabelRepository labels;
    @Autowired CarrierLabelController labelController;
    @Autowired MockMvc mvc;

    static final String CREATED = """
        {"id":"shp_ep1","object":"Shipment","rates":[
          {"id":"rate_ups_ground","carrier":"UPS","service":"Ground","rate":"8.50","currency":"USD"},
          {"id":"rate_usps_ga","carrier":"USPS","service":"GroundAdvantage","rate":"7.10","currency":"USD"},
          {"id":"rate_ups_nda","carrier":"UPS","service":"NextDayAir","rate":"31.20","currency":"USD"},
          {"id":"rate_fedex_2d","carrier":"FedEx","service":"FEDEX_2_DAY","rate":"14.90","currency":"USD"}]}
        """;
    static final String BOUGHT = """
        {"id":"shp_ep1","tracking_code":"9400111899223197428490","selected_rate":{"id":"rate_usps_ga","carrier":"USPS","service":"GroundAdvantage","rate":"7.10"},
         "postage_label":{"id":"pl_1","label_url":"https://easypost-files.s3.amazonaws.com/files/postage_label/label.pdf"}}
        """;

    void stubEasyPost() {
        WM.stubFor(post(urlEqualTo("/v2/shipments")).willReturn(aResponse().withStatus(201).withHeader("Content-Type", "application/json").withBody(CREATED)));
        WM.stubFor(post(urlEqualTo("/v2/shipments/shp_ep1/buy")).willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody(BOUGHT)));
    }

    @Test
    void easyPostIsTheActiveConnector() { assertThat(connector).isInstanceOf(EasyPostCarrierConnector.class); }

    @Test
    void buysCheapestRateOfTheRequestedServiceAndStoresTheLabel() throws Exception {
        stubEasyPost();
        Order o = capture(webOrder("ep-1"));
        ShipmentRecord sh = shipments.create(o.id(), "fg_ep1", "DC-NJ", "USPS", "ground", null,
            List.of(new ShipmentRecord.Line("1", "HOOD-GRY-M", 2), new ShipmentRecord.Line("2", "SNK-RUN-10", 1)), 3, SYSTEM);
        assertThat(sh.tracking()).isEqualTo("9400111899223197428490");

        WM.verify(postRequestedFor(urlEqualTo("/v2/shipments")).withBasicAuth(new com.github.tomakehurst.wiremock.client.BasicCredentials("EZTKtest", ""))
            .withRequestBody(matchingJsonPath("$.shipment.to_address.zip", equalTo("60606")))
            .withRequestBody(matchingJsonPath("$.shipment.to_address.city", equalTo("Chicago")))
            .withRequestBody(matchingJsonPath("$.shipment.from_address.company", equalTo("DC-NJ")))
            .withRequestBody(matchingJsonPath("$.shipment.parcel.weight"))
            .withRequestBody(matchingJsonPath("$.shipment.reference", equalTo(sh.id()))));
        WM.verify(postRequestedFor(urlEqualTo("/v2/shipments/shp_ep1/buy")).withRequestBody(matchingJsonPath("$.rate.id", equalTo("rate_usps_ga"))));

        CarrierLabelRepository.Label label = labels.forShipment(sh.id()).orElseThrow();
        assertThat(label.labelUrl()).endsWith("label.pdf");
        assertThat(label.carrier()).isEqualTo("USPS");
        assertThat(label.rateUsd()).isEqualByComparingTo("7.10");
        assertThat(label.providerShipmentId()).isEqualTo("shp_ep1");
        assertThat(labelController.label(sh.id()).tracking()).isEqualTo(sh.tracking());

        // tracker webhook → carrier event → shipment delivered
        String event = Json.write(Map.of("id", "evt_1", "object", "Event", "description", "tracker.updated",
            "result", Map.of("object", "Tracker", "tracking_code", sh.tracking(), "status", "delivered", "carrier", "USPS", "shipment_id", "shp_ep1")));
        mvc.perform(MockMvcRequestBuilders.post("/webhooks/easypost").param("tenant", tenant).contentType("application/json").content(event))
            .andExpect(status().isOk()).andExpect(jsonPath("$.type").value("delivered")).andExpect(jsonPath("$.eventId").value("easypost:evt_1"))
            .andExpect(jsonPath("$.shipments[0]").value(sh.id()));
        bind();
        assertThat(shipments.get(sh.id()).status()).isEqualTo("delivered");

        // redelivery of the same event is a no-op
        mvc.perform(MockMvcRequestBuilders.post("/webhooks/easypost").param("tenant", tenant).contentType("application/json").content(event))
            .andExpect(status().isOk()).andExpect(jsonPath("$.replayed").value(true));
        // non-tracker events are acknowledged and ignored
        mvc.perform(MockMvcRequestBuilders.post("/webhooks/easypost").param("tenant", tenant).contentType("application/json").content("{\"id\":\"evt_2\",\"description\":\"batch.created\",\"result\":{}}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.ignored").value(true));
        bind();
    }

    @Test
    void webhookSignatureIsEnforcedWhenConfigured() throws Exception {
        config.set(IntegrationsConfigKeys.EASYPOST_WEBHOOK_SECRET, Json.write("ep-secret"));
        String event = "{\"id\":\"evt_9\",\"description\":\"tracker.updated\",\"result\":{\"tracking_code\":\"NOPE\",\"status\":\"in_transit\"}}";
        mvc.perform(MockMvcRequestBuilders.post("/webhooks/easypost").param("tenant", tenant).contentType("application/json").content(event)).andExpect(status().isUnauthorized());
        mvc.perform(MockMvcRequestBuilders.post("/webhooks/easypost").param("tenant", tenant).contentType("application/json").header("X-Hmac-Signature", "hmac-sha256-hex=00").content(event)).andExpect(status().isUnauthorized());
        mvc.perform(MockMvcRequestBuilders.post("/webhooks/easypost").param("tenant", tenant).contentType("application/json")
            .header("X-Hmac-Signature", "hmac-sha256-hex=" + HmacSignatures.hex("ep-secret", event)).content(event)).andExpect(status().isOk());
        bind();
    }

    @Test
    void rateSelectionAndTrackerMappingArePure() {
        List<Map<String, Object>> rates = EasyPostCarrierConnector.rates(Json.readMap(CREATED));
        assertThat(EasyPostCarrierConnector.pickRate(rates, "ground").get("id")).isEqualTo("rate_usps_ga");
        assertThat(EasyPostCarrierConnector.pickRate(rates, "air_2day").get("id")).isEqualTo("rate_fedex_2d");
        assertThat(EasyPostCarrierConnector.pickRate(rates, "air_next_day").get("id")).isEqualTo("rate_ups_nda");
        assertThat(EasyPostCarrierConnector.pickRate(rates, "freight").get("id")).isEqualTo("rate_usps_ga");   // cheapest overall
        assertThat(EasyPostCarrierConnector.pickRate(List.of(), "ground")).isNull();

        Map<String, Object> ev = EasyPostTrackerMapper.toCarrierEvent(Map.of("id", "e1", "description", "tracker.updated", "result", Map.of("tracking_code", "T1", "status", "return_to_sender")));
        assertThat(ev).containsEntry("type", "returned_to_sender").containsEntry("tracking", "T1").containsEntry("eventId", "easypost:e1");
        assertThat(EasyPostTrackerMapper.toCarrierEvent(Map.of("description", "tracker.updated", "result", Map.of("tracking_code", "T1", "status", "failure"))).get("type")).isEqualTo("exception");
        assertThat(EasyPostTrackerMapper.toCarrierEvent(Map.of("description", "tracker.updated", "result", Map.of("tracking_code", "T1", "status", "out_for_delivery"))).get("type")).isEqualTo("out_for_delivery");
        assertThat(EasyPostTrackerMapper.toCarrierEvent(Map.of("description", "tracker.updated", "result", Map.of("tracking_code", "T1", "status", "unknown")))).isNull();
        assertThat(EasyPostTrackerMapper.toCarrierEvent(Map.of("description", "shipment.purchased", "result", Map.of()))).isNull();
    }
}
