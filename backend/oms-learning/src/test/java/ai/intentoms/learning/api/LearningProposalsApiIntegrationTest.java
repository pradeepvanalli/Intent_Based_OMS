package ai.intentoms.learning.api;

import ai.intentoms.learning.model.ArtifactRef;
import ai.intentoms.learning.model.PolicyChange;
import ai.intentoms.learning.model.PolicyChangeset;
import ai.intentoms.learning.model.Proposal;
import ai.intentoms.learning.model.ProposalStatus;
import ai.intentoms.learning.store.ProposalRepository;
import ai.intentoms.platform.ids.Ids;
import ai.intentoms.platform.security.JwtService;
import ai.intentoms.platform.security.Permissions;
import ai.intentoms.platform.security.Principal;
import ai.intentoms.platform.tenant.TenantFilter;
import ai.intentoms.testing.PostgresTestBase;
import ai.intentoms.testing.TestApplication;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * R4 (audit 5): {@code GET /learning/proposals?status=...} must validate the status against the real enum (not
 * silently match nothing) and must let a caller page through a status with more rows than one page.
 */
@SpringBootTest(classes = TestApplication.class)
@ActiveProfiles("test")
class LearningProposalsApiIntegrationTest extends PostgresTestBase {
    @Autowired JwtService jwt;
    @Autowired WebApplicationContext ctx;
    @Autowired @Qualifier("springSecurityFilterChain") Filter securityChain;
    @Autowired TenantFilter tenantFilter;
    @Autowired ProposalRepository proposals;
    @Autowired ObjectMapper json;
    MockMvc mvc;

    @BeforeEach
    void mockMvc() { mvc = MockMvcBuilders.webAppContextSetup(ctx).addFilters(securityChain, tenantFilter).build(); }

    private String bearer() {
        return "Bearer " + jwt.issue(new Principal(Principal.Type.USER, "strategy", TENANT, Set.of(), Set.of(Permissions.LEARNING_READ), 3));
    }

    private Proposal draft(String targetName) {
        ArtifactRef target = new ArtifactRef("decision", targetName);
        PolicyChange change = new PolicyChange.ThresholdChange(targetName, "act", 0.7, 0.72);
        PolicyChangeset cs = new PolicyChangeset(target, 1, List.of(change), "test proposal", PolicyChangeset.RiskTier.T1);
        Proposal p = new Proposal(Ids.prefixed("prop"), cs, null, PolicyChangeset.RiskTier.T1, ProposalStatus.DRAFT, null, null, "system:test",
            Instant.now(), Instant.now(), null, null, null, null, null, null, null, null);
        proposals.insert(p);
        return p;
    }

    @SuppressWarnings("unchecked")
    private List<Object> body(MvcResult r) throws Exception { return json.readValue(r.getResponse().getContentAsString(), List.class); }

    @Test
    void anUnknownStatusIs422NotASilent200() throws Exception {
        mvc.perform(get("/api/v1/learning/proposals").header("Authorization", bearer()).header(TenantFilter.HEADER, TENANT)
                .param("status", "BOGUS"))
            .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void everyRealStatusIsAccepted() throws Exception {
        for (ProposalStatus s : ProposalStatus.values()) {
            mvc.perform(get("/api/v1/learning/proposals").header("Authorization", bearer()).header(TenantFilter.HEADER, TENANT)
                    .param("status", s.name().toLowerCase(java.util.Locale.ROOT))) // case-insensitive
                .andExpect(status().isOk());
        }
    }

    @Test
    void aSecondPageReturnsDifferentRowsThanTheFirstAndReportsTheTotal() throws Exception {
        String target = "page_test_" + Ids.shortId();
        List<String> ids = new java.util.ArrayList<>();
        for (int i = 0; i < 12; i++) ids.add(draft(target + "_" + i).proposalId());

        MvcResult page1 = mvc.perform(get("/api/v1/learning/proposals").header("Authorization", bearer()).header(TenantFilter.HEADER, TENANT)
                .param("status", "draft").param("limit", "5").param("offset", "0"))
            .andExpect(status().isOk()).andReturn();
        List<Object> firstPage = body(page1);
        assertThat(firstPage).hasSize(5);
        long total = Long.parseLong(page1.getResponse().getHeader("X-Total-Count"));
        assertThat(total).isGreaterThanOrEqualTo(12);
        assertThat(page1.getResponse().getHeader("X-Has-More")).isEqualTo("true");

        MvcResult page2 = mvc.perform(get("/api/v1/learning/proposals").header("Authorization", bearer()).header(TenantFilter.HEADER, TENANT)
                .param("status", "draft").param("limit", "5").param("offset", "5"))
            .andExpect(status().isOk()).andReturn();
        List<Object> secondPage = body(page2);
        assertThat(secondPage).hasSize(5);
        assertThat(secondPage).as("page 2 must not repeat page 1's rows").doesNotContainAnyElementsOf(firstPage);
    }
}
