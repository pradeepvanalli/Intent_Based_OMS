package ai.intentoms.inventory.api;

import ai.intentoms.inventory.model.InventoryPosition;
import ai.intentoms.inventory.service.InventoryService;
import ai.intentoms.platform.security.JwtService;
import ai.intentoms.platform.security.Principal;
import ai.intentoms.platform.tenant.TenantFilter;
import ai.intentoms.testing.PostgresTestBase;
import ai.intentoms.testing.TestApplication;
import com.fasterxml.jackson.databind.json.JsonMapper;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * REST contract of §3.4 over real JWTs: permissions (inventory.read / inventory.write / promise.read) and
 * Idempotency-Key replay. The MockMvc is built from the Spring Security chain followed by the tenant filter, i.e. the
 * order in which authentication must precede tenant binding.
 */
@SpringBootTest(classes = TestApplication.class)
@ActiveProfiles("test")
class InventoryApiTest extends PostgresTestBase {
    @Autowired JwtService jwt;
    @Autowired InventoryService inventory;
    @Autowired WebApplicationContext ctx;
    @Autowired @Qualifier("springSecurityFilterChain") Filter securityChain;
    @Autowired TenantFilter tenantFilter;
    MockMvc mvc;

    @BeforeEach
    void mockMvc() { mvc = MockMvcBuilders.webAppContextSetup(ctx).addFilters(securityChain, tenantFilter).build(); }

    private String bearer(String... permissions) {
        return "Bearer " + jwt.issue(new Principal(Principal.Type.USER, "api-tester", TENANT, Set.of(), Set.of(permissions), 3));
    }

    private static String sku() { return "sku-" + UUID.randomUUID().toString().substring(0, 8); }

    @Test
    void networkEndpointAndPermissions() throws Exception {
        String sku = sku();
        inventory.upsert(List.of(InventoryPosition.onHand(sku, "dc1", 6), InventoryPosition.onHand(sku, "st1", 4)), "wms");

        mvc.perform(get("/api/v1/inventory/network").param("skus", sku + ",missing").header("Authorization", bearer("promise.read")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$." + sku).value(10))
            .andExpect(jsonPath("$.missing").value(0));
        mvc.perform(get("/api/v1/inventory/" + sku).header("Authorization", bearer("inventory.read")))
            .andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(2)));
        mvc.perform(get("/api/v1/inventory/" + sku).header("Authorization", bearer("promise.read")))
            .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/inventory/" + sku))
            .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/inventory/" + sku).header("Authorization", "Bearer not-a-token"))
            .andExpect(status().isUnauthorized());
        String feed = "{\"source\":\"pos\",\"positions\":[{\"sku\":\"" + sku + "\",\"nodeId\":\"st2\",\"qty\":1}]}";
        mvc.perform(post("/api/v1/inventory/feed").header("Authorization", bearer("inventory.read")).contentType(MediaType.APPLICATION_JSON).content(feed))
            .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/inventory/feed").header("Authorization", bearer("inventory.write")).contentType(MediaType.APPLICATION_JSON).content(feed))
            .andExpect(status().isOk()).andExpect(jsonPath("$.positions").value(1)).andExpect(jsonPath("$.nodes").value(1));
        mvc.perform(post("/api/v1/inventory/feed").header("Authorization", bearer("inventory.write")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"source\":\"pos\",\"positions\":[{\"sku\":\"" + sku + "\",\"nodeId\":\"st2\",\"supplyType\":\"bogus\",\"qty\":1}]}"))
            .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("validation_failed"));
    }

    @Test
    void reservationFlowWithIdempotencyKey() throws Exception {
        String sku = sku();
        String key = "idem-" + UUID.randomUUID();
        String write = bearer("inventory.write"), read = bearer("inventory.read");
        inventory.upsert(List.of(InventoryPosition.onHand(sku, "dc1", 3)), "wms");
        String body = "{\"source\":\"checkout\",\"refId\":\"chk-1-" + sku + "\",\"ttlSeconds\":600,\"lines\":[{\"sku\":\"" + sku + "\",\"nodeId\":\"dc1\",\"qty\":2}]}";

        mvc.perform(post("/api/v1/reservations").header("Authorization", write).header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isCreated()).andExpect(jsonPath("$", hasSize(1))).andExpect(jsonPath("$[0].status").value("ACTIVE"));
        // replay: same key + same body returns the stored reservations without reserving again
        mvc.perform(post("/api/v1/reservations").header("Authorization", write).header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isCreated()).andExpect(jsonPath("$", hasSize(1)));
        mvc.perform(get("/api/v1/inventory/" + sku + "/nodes/dc1").header("Authorization", read))
            .andExpect(jsonPath("$.reserved").value(2)).andExpect(jsonPath("$.available").value(1));
        // same key, different body -> 409
        mvc.perform(post("/api/v1/reservations").header("Authorization", write).header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON)
                .content(body.replace("\"qty\":2", "\"qty\":1")))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("conflict")).andExpect(jsonPath("$.detail").value("idempotency_key_reused"));
        // over ATP -> 409 insufficient_atp with details
        mvc.perform(post("/api/v1/reservations").header("Authorization", write).contentType(MediaType.APPLICATION_JSON)
                .content("{\"source\":\"cart\",\"refId\":\"c-9\",\"sku\":\"" + sku + "\",\"nodeId\":\"dc1\",\"qty\":2}"))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.detail").value("insufficient_atp")).andExpect(jsonPath("$.available").value(1));

        String listed = mvc.perform(get("/api/v1/reservations").param("refId", "chk-1-" + sku).header("Authorization", read))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String id = JsonMapper.builder().build().readTree(listed).get(0).get("id").asText();
        mvc.perform(post("/api/v1/reservations/" + id + "/convert").header("Authorization", write).contentType(MediaType.APPLICATION_JSON).content("{\"orderId\":\"ord-1\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.source").value("order")).andExpect(jsonPath("$.expiresAt").doesNotExist());
        mvc.perform(post("/api/v1/reservations/" + id + "/consume").header("Authorization", write))
            .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CONSUMED"));
        mvc.perform(get("/api/v1/inventory/" + sku + "/nodes/dc1").header("Authorization", read))
            .andExpect(jsonPath("$.onHand").value(1)).andExpect(jsonPath("$.reserved").value(0)).andExpect(jsonPath("$.available").value(1));
        mvc.perform(post("/api/v1/reservations/" + id + "/release").header("Authorization", write))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.detail").value("reservation_not_active"));
    }
}
