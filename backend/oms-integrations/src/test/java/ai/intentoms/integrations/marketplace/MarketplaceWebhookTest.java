package ai.intentoms.integrations.marketplace;

import ai.intentoms.integrations.IntegrationsTestBase;
import ai.intentoms.integrations.config.HmacSignatures;
import ai.intentoms.integrations.config.IntegrationsConfigKeys;
import ai.intentoms.order.capture.CaptureRequest;
import ai.intentoms.order.model.Order;
import ai.intentoms.platform.error.ValidationException;
import ai.intentoms.platform.json.Json;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** FR-CAP-01 marketplace connector: Amazon SP-API shape, generic shape, HMAC enforcement, idempotent replay, import log. */
class MarketplaceWebhookTest extends IntegrationsTestBase {
    @Autowired MockMvc mvc;
    @Autowired MarketplaceImportRepository imports;

    static final String AMAZON = """
        {"AmazonOrderId":"111-7654321-1234567","PurchaseDate":"2026-10-06T08:15:00Z","OrderStatus":"Unshipped","FulfillmentChannel":"MFN",
         "SalesChannel":"Amazon.com","ShipmentServiceLevelCategory":"Expedited","IsPrime":true,
         "ShippingAddress":{"Name":"Priya Natarajan","AddressLine1":"233 S Wacker Dr","AddressLine2":"Suite 4400","City":"Chicago","StateOrRegion":"IL","PostalCode":"60606","CountryCode":"US","Phone":"+13125551212","AddressType":"Residential"},
         "OrderTotal":{"CurrencyCode":"USD","Amount":"281.00"},
         "BuyerInfo":{"BuyerEmail":"priya@marketplace.amazon.com","BuyerName":"Priya Natarajan"},
         "OrderItems":[
           {"ASIN":"B08XYZ1234","SellerSKU":"HOOD-GRY-M","OrderItemId":"88821","QuantityOrdered":2,"ItemPrice":{"CurrencyCode":"USD","Amount":"136.00"},"Title":"Fleece hoodie grey M"},
           {"ASIN":"B08XYZ9999","SellerSKU":"SNK-RUN-10","OrderItemId":"88822","QuantityOrdered":1,"ItemPrice":{"CurrencyCode":"USD","Amount":"145.00"},"Title":"Trail runner sneaker 10"}]}
        """;

    static final String GENERIC = """
        {"externalOrderId":"WM-5001","placedAt":"2026-10-06T10:00:00Z","shipMethod":"ground",
         "customer":{"ref":"wm-cust-9","name":"Lee Marsh","email":"lee@example.com","phone":"+13125550000"},
         "lines":[{"lineId":"a","sku":"TEE-BLK-M","qty":3,"unitPrice":{"amount":"28.00","currency":"USD"}},{"sku":"CAP-NVY","qty":1,"price":32.00}],
         "shipTo":{"name":"Lee Marsh","line1":"1 N State St","city":"Chicago","state":"IL","zip":"60602","country":"US"},
         "total":{"amount":"116.00","currency":"USD"}}
        """;

    @Test
    void amazonOrderIsCapturedAndReplayed() throws Exception {
        MvcResult r = mvc.perform(post("/webhooks/marketplace/amazon").contentType("application/json").header("X-Tenant-Id", tenant).content(AMAZON))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.replayed").value(false)).andExpect(jsonPath("$.channel").value("marketplace")).andReturn();
        bind();
        String orderId = (String) Json.readMap(r.getResponse().getContentAsString()).get("orderId");
        Order o = orders.get(orderId);
        assertThat(o.channel()).isEqualTo("marketplace");
        assertThat(o.channelOrderId()).isEqualTo("amazon:111-7654321-1234567");
        assertThat(o.orderType()).isEqualTo("standard_ship");
        assertThat(o.shipMethod()).isEqualTo("air_2day");
        assertThat(o.lines()).hasSize(2);
        assertThat(o.line("88821").orElseThrow().unitPrice().amount()).isEqualByComparingTo("68.00");   // 136.00 / 2
        assertThat(o.line("88821").orElseThrow().attributes()).containsEntry("asin", "B08XYZ1234");
        assertThat(o.tenders()).singleElement().satisfies(t -> {
            assertThat(t.type()).isEqualTo("net_terms");
            assertThat(t.details()).containsEntry("settlement", "marketplace_settlement");
            assertThat(t.amount().amount()).isEqualByComparingTo("281.00");
        });
        assertThat(o.shipTo().orElseThrow().line2()).isEqualTo("Suite 4400");
        assertThat(o.customer().email()).isEqualTo("priya@marketplace.amazon.com");
        assertThat(o.customer().tier()).isEqualTo("prime");
        assertThat(o.extensions()).containsEntry("marketplace", "amazon").containsEntry("marketplace_order_id", "111-7654321-1234567").containsEntry("is_prime", true);
        assertThat(o.placedAt()).isEqualTo(java.time.Instant.parse("2026-10-06T08:15:00Z"));

        mvc.perform(post("/webhooks/marketplace/amazon").contentType("application/json").header("X-Tenant-Id", tenant).content(AMAZON))
            .andExpect(status().isOk()).andExpect(jsonPath("$.replayed").value(true)).andExpect(jsonPath("$.orderId").value(orderId));
        bind();
        List<Map<String, Object>> log = imports.forExternal("amazon", "111-7654321-1234567");
        assertThat(log).extracting(m -> m.get("status")).containsExactly("captured", "replayed");
    }

    @Test
    void genericShapeIsCaptured() throws Exception {
        MvcResult r = mvc.perform(post("/webhooks/marketplace/walmart").contentType("application/json").param("tenant", tenant).content(GENERIC))
            .andExpect(status().isCreated()).andReturn();
        bind();
        Order o = orders.get((String) Json.readMap(r.getResponse().getContentAsString()).get("orderId"));
        assertThat(o.channelOrderId()).isEqualTo("walmart:WM-5001");
        assertThat(o.customerRef()).isEqualTo("wm-cust-9");
        assertThat(o.lines()).extracting(l -> l.sku()).containsExactlyInAnyOrder("TEE-BLK-M", "CAP-NVY");
        assertThat(o.line("2").orElseThrow().unitPrice().amount()).isEqualByComparingTo("32.00");
        assertThat(o.tenders().get(0).amount().amount()).isEqualByComparingTo("116.00");
    }

    @Test
    void hmacIsEnforcedWhenTheTenantConfiguredASecret() throws Exception {
        config.set(IntegrationsConfigKeys.marketplaceWebhookSecret("amazon"), Json.write("mp-secret"));
        mvc.perform(post("/webhooks/marketplace/amazon").contentType("application/json").header("X-Tenant-Id", tenant).content(AMAZON)).andExpect(status().isUnauthorized());
        mvc.perform(post("/webhooks/marketplace/amazon").contentType("application/json").header("X-Tenant-Id", tenant).header("X-Marketplace-Signature", "deadbeef").content(AMAZON)).andExpect(status().isUnauthorized());
        mvc.perform(post("/webhooks/marketplace/amazon").contentType("application/json").header("X-Tenant-Id", tenant)
                .header("X-Marketplace-Signature", HmacSignatures.hex("mp-secret", AMAZON)).content(AMAZON)).andExpect(status().isCreated());
        mvc.perform(post("/webhooks/marketplace/amazon").contentType("application/json").header("X-Tenant-Id", tenant)
                .header("X-Marketplace-Signature", "sha256=" + HmacSignatures.base64("mp-secret", AMAZON)).content(AMAZON)).andExpect(status().isOk());
        bind();
    }

    @Test
    void badPayloadsAre422AndRecordedAsFailed() throws Exception {
        mvc.perform(post("/webhooks/marketplace/amazon").contentType("application/json").header("X-Tenant-Id", tenant)
            .content("{\"AmazonOrderId\":\"111-0000000-0000000\",\"OrderItems\":[{\"ASIN\":\"B1\",\"QuantityOrdered\":1}]}")).andExpect(status().isUnprocessableEntity());
        mvc.perform(post("/webhooks/marketplace/amazon").contentType("application/json").header("X-Tenant-Id", tenant).content("{\"hello\":1}")).andExpect(status().isUnprocessableEntity());
        mvc.perform(post("/webhooks/marketplace/bad.name").contentType("application/json").header("X-Tenant-Id", tenant).content(AMAZON)).andExpect(status().isUnprocessableEntity());
        bind();
        assertThat(imports.forExternal("amazon", "111-0000000-0000000")).singleElement().satisfies(m -> assertThat(m.get("status")).isEqualTo("failed"));
    }

    @Test
    void mapperIsPureAndStrict() {
        CaptureRequest req = MarketplaceOrderMapper.map("amazon", Json.readMap(AMAZON), "standard_ship");
        assertThat(req.channel()).isEqualTo("marketplace");
        assertThat(req.tenders()).singleElement().satisfies(t -> assertThat(t.type()).isEqualTo(MarketplaceOrderMapper.TENDER_TYPE));
        assertThat(MarketplaceOrderMapper.shipMethod("NextDay")).isEqualTo("air_next_day");
        assertThat(MarketplaceOrderMapper.shipMethod("Standard")).isEqualTo("ground");
        assertThatThrownBy(() -> MarketplaceOrderMapper.map("amazon", Map.of("AmazonOrderId", "x", "OrderItems", List.of(Map.of("SellerSKU", "A", "QuantityOrdered", 0))), "standard_ship"))
            .isInstanceOf(ValidationException.class).hasMessageContaining("QuantityOrdered");
        assertThatThrownBy(() -> MarketplaceOrderMapper.map("x", Map.of("externalOrderId", "1", "lines", List.of(Map.of("sku", "A", "qty", 1))), "standard_ship"))
            .isInstanceOf(ValidationException.class).hasMessageContaining("unitPrice");
    }
}
