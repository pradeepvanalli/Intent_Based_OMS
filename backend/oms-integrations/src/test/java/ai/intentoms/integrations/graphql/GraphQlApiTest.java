package ai.intentoms.integrations.graphql;

import ai.intentoms.integrations.IntegrationsTestBase;
import ai.intentoms.order.model.Order;
import ai.intentoms.platform.security.Principal;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.graphql.test.tester.GraphQlTester;
import org.springframework.test.web.servlet.MockMvc;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** FR-CAP-01 GraphQL: capture, query back, list, promise, inventory, returns, permission and validation errors, idempotent replay, modify/cancel. */
class GraphQlApiTest extends IntegrationsTestBase {
    @Autowired MockMvc mvc;

    static final String CAPTURE = """
        mutation Capture($input: CaptureOrderInput!, $key: String) {
          captureOrder(input: $input, idempotencyKey: $key) {
            replayed
            order { id channel channelOrderId orderType status currency placedAt
              lines { lineId sku qty unitPrice { amount currency } quantities { state qty } }
              addresses { type city state zip verified }
              tenders { type amount { amount currency } }
              totals { grandTotal { amount currency } }
              margin { marginRatio belowFloor }
              header { state }
              lineStatuses { entityId state quantities { state qty } } } } }
        """;

    Map<String, Object> captureInput(String channelOrderId) {
        Map<String, Object> in = new LinkedHashMap<>();
        in.put("channel", "web");
        in.put("channelOrderId", channelOrderId);
        in.put("orderType", "standard_ship");
        in.put("customerRef", "cust-gql");
        in.put("customer", Map.of("ref", "cust-gql", "name", "Gina Query", "email", "gina@example.com", "tier", "gold", "ageVerified", true));
        in.put("lines", List.of(
            Map.of("lineId", "1", "sku", "HOOD-GRY-M", "qty", 2, "unitPrice", Map.of("amount", "68.00", "currency", "USD")),
            Map.of("lineId", "2", "sku", "SNK-RUN-10", "qty", 1, "unitPrice", Map.of("amount", 145.00, "currency", "USD"))));
        in.put("addresses", List.of(Map.of("type", "ship_to", "name", "Gina Query", "line1", "233 S Wacker Dr", "city", "Chicago", "state", "IL", "zip", "60606", "country", "US", "addressType", "residential")));
        in.put("tenders", List.of(Map.of("type", "card", "token", "tok_ok_gql", "amount", Map.of("amount", "320.00", "currency", "USD"))));
        in.put("shipMethod", "ground");
        in.put("placedAt", "2026-10-06T09:00:00Z");
        in.put("extensions", Map.of("gift_message", "hi"));
        return in;
    }

    @Test
    void captureThenQueryBack() {
        GraphQlTester.Response res = graphql().document(CAPTURE).variable("input", captureInput("gql-1")).variable("key", "gql-1").execute();
        res.path("captureOrder.replayed").entity(Boolean.class).isEqualTo(false);
        String id = res.path("captureOrder.order.id").entity(String.class).get();
        res.path("captureOrder.order.channelOrderId").entity(String.class).isEqualTo("gql-1");
        res.path("captureOrder.order.placedAt").entity(String.class).isEqualTo("2026-10-06T09:00:00Z");
        res.path("captureOrder.order.lines[0].unitPrice.amount").entity(java.math.BigDecimal.class).satisfies(a -> assertThat(a).isEqualByComparingTo("68.00"));
        res.path("captureOrder.order.lines[0].quantities").entityList(Map.class).satisfies(q -> assertThat(q).isNotEmpty());
        res.path("captureOrder.order.lineStatuses").entityList(Map.class).hasSize(2);
        res.path("captureOrder.order.header.state").entity(String.class).satisfies(s -> assertThat(s).isNotBlank());
        res.path("captureOrder.order.margin.marginRatio").entity(Double.class).satisfies(m -> assertThat(m).isGreaterThan(0));
        res.path("captureOrder.order.totals.grandTotal.currency").entity(String.class).isEqualTo("USD");

        // the same order is visible to REST callers and to the other GraphQL queries
        Order o = orders.get(id);
        assertThat(o.channelOrderId()).isEqualTo("gql-1");
        assertThat(o.extensions()).containsEntry("gift_message", "hi");

        graphql().document("query($id: ID!) { order(id: $id) { id channelOrderId lines { sku qty } shipments { id } returns { rma } } }").variable("id", id).execute()
            .path("order.channelOrderId").entity(String.class).isEqualTo("gql-1")
            .path("order.lines").entityList(Map.class).hasSize(2)
            .path("order.returns").entityList(Map.class).hasSize(0);
        graphql().document("{ orderByChannelRef(channel: \"web\", channelOrderId: \"gql-1\") { id } }").execute().path("orderByChannelRef.id").entity(String.class).isEqualTo(id);
        graphql().document("{ orders(filter: {channel: \"web\"}, offset: 0, limit: 10) { total items { id channelOrderId lineCount } } }").execute()
            .path("orders.total").entity(Integer.class).satisfies(t -> assertThat(t).isGreaterThanOrEqualTo(1))
            .path("orders.items[*].channelOrderId").entityList(String.class).contains("gql-1");
        graphql().document("query($id: ID!) { returns(orderId: $id) { rma } }").variable("id", id).execute().path("returns").entityList(Map.class).hasSize(0);
    }

    @Test
    void idempotentReplayReturnsSameOrder() {
        String first = graphql().document(CAPTURE).variable("input", captureInput("gql-2")).variable("key", "gql-2").execute().path("captureOrder.order.id").entity(String.class).get();
        GraphQlTester.Response replay = graphql().document(CAPTURE).variable("input", captureInput("gql-2")).variable("key", "gql-2").execute();
        replay.path("captureOrder.replayed").entity(Boolean.class).isEqualTo(true);
        replay.path("captureOrder.order.id").entity(String.class).isEqualTo(first);
    }

    @Test
    void promiseAndInventoryQueries() {
        graphql().document("{ promise(input: {lines: [{sku: \"HOOD-GRY-M\", qty: 1}], destinationZip: \"60606\", destinationState: \"IL\", channel: \"web\", shipMethod: \"ground\"}) { date onTimeProbability fromNodeId shipMethod alternatives { shipMethod date } } }")
            .execute()
            .path("promise.date").entity(String.class).satisfies(d -> assertThat(d).matches("\\d{4}-\\d{2}-\\d{2}"))
            .path("promise.onTimeProbability").entity(Double.class).satisfies(p -> assertThat(p).isBetween(0.0, 1.0))
            .path("promise.fromNodeId").entity(String.class).satisfies(n -> assertThat(n).isNotBlank());
        graphql().document("{ inventory(skus: [\"HOOD-GRY-M\", \"NOPE-SKU\"]) { sku available } }").execute()
            .path("inventory[0].sku").entity(String.class).isEqualTo("HOOD-GRY-M")
            .path("inventory[0].available").entity(Integer.class).satisfies(a -> assertThat(a).isGreaterThan(0))
            .path("inventory[1].available").entity(Integer.class).isEqualTo(0);
    }

    @Test
    void permissionDeniedSurfacesAsForbiddenErrorCode() {
        actAs("reader", Principal.Type.USER, Set.of("order.read"));
        graphql().document(CAPTURE).variable("input", captureInput("gql-3")).execute().errors()
            .expect(e -> "forbidden".equals(e.getExtensions().get("code")) && Integer.valueOf(403).equals(e.getExtensions().get("status")) && e.getPath().equals("captureOrder"))
            .verify();
        // order.read still works for the same principal
        graphql().document("{ orders(limit: 1) { total } }").execute().path("orders.total").entity(Integer.class).satisfies(t -> assertThat(t).isGreaterThanOrEqualTo(0));
        // an agent principal never sees the margin snapshot
        bind();
        String id = graphql().document(CAPTURE).variable("input", captureInput("gql-4")).execute().path("captureOrder.order.id").entity(String.class).get();
        actAs("shopbot", Principal.Type.AGENT, Set.of("order.read"));
        graphql().document("query($id: ID!) { order(id: $id) { id margin { marginRatio } } }").variable("id", id).execute()
            .path("order.id").entity(String.class).isEqualTo(id)
            .path("order.margin").valueIsNull();
    }

    @Test
    void validationFailureCarriesProblemCodeAndIssues() {
        Map<String, Object> in = captureInput("gql-5");
        in.put("tenders", List.of());   // tender required for a positive total
        graphql().document(CAPTURE).variable("input", in).execute().errors()
            .expect(e -> "validation_failed".equals(e.getExtensions().get("code")) && Integer.valueOf(422).equals(e.getExtensions().get("status")) && e.getExtensions().containsKey("issues"))
            .verify();
        graphql().document("{ order(id: \"ord_missing\") { id } }").execute().errors()
            .expect(e -> "not_found".equals(e.getExtensions().get("code"))).verify();
    }

    @Test
    void modifyAndCancelThroughMutations() {
        String id = graphql().document(CAPTURE).variable("input", captureInput("gql-6")).execute().path("captureOrder.order.id").entity(String.class).get();
        graphql().document("mutation($id: ID!) { modifyOrder(orderId: $id, modification: {type: ChangeShipMethod, shipMethod: \"air_2day\"}, reason: \"customer asked\") { id shipMethod } }")
            .variable("id", id).execute().path("modifyOrder.shipMethod").entity(String.class).isEqualTo("air_2day");
        graphql().document("mutation($id: ID!) { modifyOrder(orderId: $id, modification: {type: ChangeAddress, address: {type: \"ship_to\", name: \"Gina Query\", line1: \"1 N State St\", city: \"Chicago\", state: \"IL\", zip: \"60602\", country: \"US\"}}, reason: \"moved\") { addresses { type line1 zip } } }")
            .variable("id", id).execute().path("modifyOrder.addresses[0].line1").entity(String.class).isEqualTo("1 N State St");
        graphql().document("mutation($id: ID!) { cancelOrder(orderId: $id, reason: \"changed mind\") { id status } }").variable("id", id).execute()
            .path("cancelOrder.id").entity(String.class).isEqualTo(id);
        assertThat(orders.get(id).lines()).allSatisfy(l -> assertThat(l.status()).isIn("cancelled", null));
    }

    @Test
    void graphqlEndpointRequiresAuthenticationOverHttpAndWorksWithAToken() throws Exception {
        mvc.perform(post("/graphql").contentType("application/json").header("X-Tenant-Id", tenant).content("{\"query\":\"{ orders(limit: 1) { total } }\"}"))
            .andExpect(status().isUnauthorized());
        // dev user admin/admin of tenant "test" (PostgresTestBase) → session token → full request path through the security chain
        String login = mvc.perform(post("/api/v1/auth/login").contentType("application/json").content("{\"tenantId\":\"test\",\"userId\":\"admin\",\"password\":\"admin\"}"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String token = (String) ai.intentoms.platform.json.Json.readMap(login).get("token");
        // Spring for GraphQL answers asynchronously on MVC, so MockMvc needs the async dispatch to read the body
        var started = mvc.perform(post("/graphql").contentType("application/json").header("Authorization", "Bearer " + token)
                .content("{\"query\":\"{ orders(limit: 1) { total offset limit } inventory(skus: [\\\"HOOD-GRY-M\\\"]) { sku available } }\"}"))
            .andExpect(request().asyncStarted()).andReturn();
        mvc.perform(asyncDispatch(started))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.errors").doesNotExist())
            .andExpect(jsonPath("$.data.orders.total").isNumber())
            .andExpect(jsonPath("$.data.inventory[0].sku").value("HOOD-GRY-M"));
        bind();
    }

    @Test
    void modificationInputMapsToSealedVariants() {
        assertThat(OmsGraphQlController.toModification(Map.of("type", "CancelLine", "lineId", "1"))).isInstanceOf(ai.intentoms.order.modification.Modification.CancelLine.class);
        assertThat(OmsGraphQlController.toModification(Map.of("type", "ChangeQty", "lineId", "1", "qty", 3))).isEqualTo(new ai.intentoms.order.modification.Modification.ChangeQty("1", 3));
        assertThat(OmsGraphQlController.toModification(Map.of("type", "ChangeItem", "lineId", "1", "newSku", "TEE-BLK-M"))).isEqualTo(new ai.intentoms.order.modification.Modification.ChangeItem("1", "TEE-BLK-M"));
        assertThat(OmsGraphQlController.toModification(Map.of("type", "CancelOrder"))).isInstanceOf(ai.intentoms.order.modification.Modification.CancelOrder.class);
    }
}
