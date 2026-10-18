package ai.intentoms.sourcing.api;

import ai.intentoms.learning.learner.PromiseBufferLearner;
import ai.intentoms.learning.model.FactKey;
import ai.intentoms.learning.model.SubjectType;
import ai.intentoms.learning.store.LearnedFactRepository;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** CX-02 ingest: {@code POST /api/v1/promise/conversion(s)} stores impressions for the promise-buffer learner. */
class PromiseConversionApiTest extends SourcingTestBase {
    @Autowired JwtService jwt;
    @Autowired WebApplicationContext ctx;
    @Autowired @Qualifier("springSecurityFilterChain") Filter securityChain;
    @Autowired TenantFilter tenantFilter;
    @Autowired PromiseBufferLearner learner;
    @Autowired LearnedFactRepository facts;
    MockMvc mvc;

    @BeforeEach
    void mockMvc() { mvc = MockMvcBuilders.webAppContextSetup(ctx).addFilters(securityChain, tenantFilter).build(); }

    private String bearer(String... permissions) {
        return "Bearer " + jwt.issue(new Principal(Principal.Type.USER, "storefront", tenant, Set.of(), Set.of(permissions), 3));
    }

    @Test
    void storefrontImpressionsAreStoredOnceAndTeachTheBuffer() throws Exception {
        String one = "{\"impressionId\":\"imp-1\",\"channel\":\"web\",\"promisedDays\":3,\"bufferDays\":1,\"converted\":true}";
        mvc.perform(post("/api/v1/promise/conversion").header(TenantFilter.HEADER, tenant).contentType(MediaType.APPLICATION_JSON).content(one))
            .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/promise/conversion").header("Authorization", bearer("promise.read")).contentType(MediaType.APPLICATION_JSON).content(one))
            .andExpect(status().isAccepted()).andExpect(jsonPath("$.stored").value(true));
        mvc.perform(post("/api/v1/promise/conversion").header("Authorization", bearer("promise.read")).contentType(MediaType.APPLICATION_JSON).content(one))
            .andExpect(status().isAccepted()).andExpect(jsonPath("$.stored").value(false));
        mvc.perform(post("/api/v1/promise/conversion").header("Authorization", bearer("promise.read")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"channel\":\"web\",\"promisedDays\":3,\"bufferDays\":99,\"converted\":true}"))
            .andExpect(status().isUnprocessableEntity());

        StringBuilder batch = new StringBuilder("[");
        for (int i = 0; i < 30; i++) batch.append(i == 0 ? "" : ",").append("{\"channel\":\"web\",\"promisedDays\":4,\"bufferDays\":1,\"converted\":").append(i < 20).append('}');
        batch.append(']');
        mvc.perform(post("/api/v1/promise/conversions").header("Authorization", bearer("promise.read")).contentType(MediaType.APPLICATION_JSON).content(batch.toString()))
            .andExpect(status().isAccepted()).andExpect(jsonPath("$.stored").value(30));

        ai.intentoms.platform.tenant.TenantContext.set(tenant); // the request filter unbinds the tenant when a request ends
        learner.sweepOnce();
        assertThat(facts.active(SubjectType.SEGMENT, "channel:web", FactKey.PROMISE_BUFFER_DAYS)).isPresent();
    }
}
