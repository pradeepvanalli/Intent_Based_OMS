package ai.intentoms.sourcing.api;

import ai.intentoms.platform.security.JwtService;
import ai.intentoms.platform.security.Principal;
import ai.intentoms.platform.tenant.TenantFilter;
import ai.intentoms.sourcing.SourcingTestBase;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.Set;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** REST contract of §3.7: public promise with tenant header, node permissions, sourcing endpoints returning 200 with a status field. */
class SourcingApiTest extends SourcingTestBase {
    @Autowired JwtService jwt;
    @Autowired WebApplicationContext ctx;
    @Autowired @Qualifier("springSecurityFilterChain") Filter securityChain;
    @Autowired TenantFilter tenantFilter;
    MockMvc mvc;

    @BeforeEach
    void mockMvc() { mvc = MockMvcBuilders.webAppContextSetup(ctx).addFilters(securityChain, tenantFilter).build(); }

    private String bearer(String... permissions) {
        return "Bearer " + jwt.issue(new Principal(Principal.Type.USER, "api-tester", tenant, Set.of(), Set.of(permissions), 3));
    }

    @Test
    void promiseIsPublicAndTenantKeyed() throws Exception {
        stock("TEE-BLK-M", "DC-NJ", 10);
        String body = "{\"lines\":[{\"sku\":\"TEE-BLK-M\",\"qty\":1}],\"destinationZip\":\"10001\",\"destinationState\":\"NY\",\"channel\":\"web\",\"shipMethod\":\"ground\"}";
        mvc.perform(post("/api/v1/promise").header(TenantFilter.HEADER, tenant).contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.fromNodeId").value("DC-NJ"))
            .andExpect(jsonPath("$.fromCache").value(false))
            .andExpect(jsonPath("$.alternatives").isArray());
        mvc.perform(post("/api/v1/promise").contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isUnprocessableEntity());
        mvc.perform(get("/api/v1/promise/cache")).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/promise/cache").header("Authorization", bearer("order.read"))).andExpect(status().isOk()).andExpect(jsonPath("$.ttlSeconds").value(30));
        mvc.perform(post("/api/v1/promise").header(TenantFilter.HEADER, tenant).contentType(MediaType.APPLICATION_JSON).content("{\"lines\":[],\"destinationZip\":\"10001\"}"))
            .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void nodeEndpointsEnforcePermissions() throws Exception {
        mvc.perform(get("/api/v1/nodes").header("Authorization", bearer("order.read"))).andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(8)));
        mvc.perform(get("/api/v1/nodes/DC-NJ").header("Authorization", bearer("order.read"))).andExpect(status().isOk()).andExpect(jsonPath("$.node.id").value("DC-NJ"));
        mvc.perform(get("/api/v1/nodes")).andExpect(status().isUnauthorized());
        String node = "{\"name\":\"Test\",\"type\":\"dc\",\"lat\":40.0,\"lon\":-75.0,\"state\":\"PA\",\"capabilities\":[\"ship\"],\"dailyCapacity\":100,\"cutoff\":\"16:00\",\"timezone\":\"America/New_York\"}";
        mvc.perform(put("/api/v1/nodes/DC-PA").header("Authorization", bearer("order.read")).contentType(MediaType.APPLICATION_JSON).content(node)).andExpect(status().isForbidden());
        mvc.perform(put("/api/v1/nodes/DC-PA").header("Authorization", bearer("config.admin")).contentType(MediaType.APPLICATION_JSON).content(node))
            .andExpect(status().isOk()).andExpect(jsonPath("$.id").value("DC-PA")).andExpect(jsonPath("$.active").value(true));
        mvc.perform(post("/api/v1/nodes/DC-PA/health").header("Authorization", bearer("config.admin")).contentType(MediaType.APPLICATION_JSON).content("{\"backlog\":5}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.health.backlog").value(5));
        mvc.perform(post("/api/v1/nodes/DC-PA/penalty").header("Authorization", bearer("config.admin")).contentType(MediaType.APPLICATION_JSON).content("{\"sku\":\"X\",\"delta\":0.3,\"reason\":\"test\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.penalties", hasSize(1)));
        mvc.perform(get("/api/v1/shipping-rates").header("Authorization", bearer("order.read"))).andExpect(status().isOk()).andExpect(jsonPath("$.serviceCodes", hasSize(4)));
        mvc.perform(get("/api/v1/shipping-rates/quote").param("service", "ground").param("distanceKm", "100").param("weightKg", "1").header("Authorization", bearer("order.read")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.transitDays").value(1));
    }

    @Test
    void sourcingEndpointsReturnStatusField() throws Exception {
        stock("TEE-BLK-M", "DC-NJ", 10);
        var o = order("marketplace", null, NEW_YORK, line("L1", "TEE-BLK-M", 1, 28, 7.5));
        var missing = order("web", null, NEW_YORK, line("L1", "DRONE-MINI", 1, 499, 210)); // created up front: TenantFilter clears the thread's tenant after each request
        mvc.perform(get("/api/v1/orders/" + o.id() + "/sourcing/options").header("Authorization", bearer("order.read"))).andExpect(status().isOk()).andExpect(jsonPath("$[0].policy").exists());
        mvc.perform(post("/api/v1/orders/" + o.id() + "/sourcing").header("Authorization", bearer("order.read"))).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/orders/" + o.id() + "/sourcing").header("Authorization", bearer("order.modify")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("SOURCED")).andExpect(jsonPath("$.plan.packages", hasSize(1)));
        mvc.perform(get("/api/v1/orders/" + o.id() + "/sourcing/plans").header("Authorization", bearer("order.read"))).andExpect(status().isOk()).andExpect(jsonPath("$[0].chosen").exists());
        mvc.perform(post("/api/v1/orders/" + o.id() + "/resource").header("Authorization", bearer("order.modify")).contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"store_reject\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("NO_FEASIBLE_NODE"));   // the only stocked node was rejected
        mvc.perform(post("/api/v1/orders/" + missing.id() + "/sourcing").header("Authorization", bearer("order.modify")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("NO_FEASIBLE_NODE"));
    }
}
