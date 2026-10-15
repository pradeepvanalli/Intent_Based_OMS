package ai.intentoms.testing.security;

import ai.intentoms.platform.security.JwtService;
import ai.intentoms.platform.security.OidcTokenAuthenticator;
import ai.intentoms.platform.security.Principal;
import ai.intentoms.platform.security.SecurityProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** NFR-07: IdP-issued RS256 tokens are accepted via JWKS, mapped to OMS principals, and local HS256 tokens keep working. */
class OidcIntegrationTest extends SecurityHardeningTestBase {
    @Autowired MockMvc mvc;
    @Autowired JwtService localJwt;
    @Autowired OidcTokenAuthenticator oidc;
    @Autowired SecurityProperties props;

    @BeforeEach
    void anonymous() { SecurityContextHolder.clearContext(); }

    @Test
    void validIdpTokenIsMappedThroughKeycloakRealmRolesAndRoleMapping() throws Exception {
        assertThat(oidc.enabled()).isTrue();
        mvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + idpToken(b -> {})))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.type").value("USER"))
            .andExpect(jsonPath("$.id").value("alice"))
            .andExpect(jsonPath("$.tenantId").value(TENANT))
            .andExpect(jsonPath("$.roles[0]").value("admin"))
            .andExpect(jsonPath("$.permissions[0]").value("*"))
            .andExpect(jsonPath("$.trustTier").value(3));
    }

    @Test
    void plainRolesClaimAndMultipleMappingsCombine() throws Exception {
        String token = idpToken(b -> b.claim("preferred_username", null).claim("realm_access", Map.of("roles", List.of("OMS-CS"))).claim("roles", List.of("store_associate", "not-an-oms-role")));
        Principal p = oidc.authenticate(token).orElseThrow();
        assertThat(p.id()).isEqualTo("f81d4fae-7dec-11d0-a765-00a0c91e6bf6"); // falls back to sub
        assertThat(p.roles()).containsExactlyInAnyOrder("cs_rep", "store_associate");
        assertThat(p.has("refund.issue")).isTrue();
        assertThat(p.has("config.admin")).isFalse();
    }

    @Test
    void expiredTokenIsUnauthorized() throws Exception {
        String expired = idpToken(b -> b.expirationTime(Date.from(Instant.now().minus(Duration.ofMinutes(10)))).issueTime(Date.from(Instant.now().minus(Duration.ofMinutes(20)))));
        mvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + expired)).andExpect(status().isUnauthorized());
    }

    @Test
    void wrongAudienceWrongIssuerAndUnknownKeyAreUnauthorized() throws Exception {
        mvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + idpToken(b -> b.audience("another-api")))).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + idpToken(b -> b.issuer("https://evil.example.com")))).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + sign(ROGUE, b -> {}))).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer not.a.jwt")).andExpect(status().isUnauthorized());
    }

    @Test
    void tokenWithoutTenantIsRejected() throws Exception {
        mvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + idpToken(b -> b.claim("tenant", null)))).andExpect(status().isUnauthorized());
    }

    @Test
    void localHs256TokensStillWorkAlongsideOidc() throws Exception {
        Principal local = new Principal(Principal.Type.USER, "local-bob", TENANT, Set.of("ops_analyst"), Set.of("order.read"), 3);
        mvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + localJwt.issue(local)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.id").value("local-bob"));
        // and a tampered local token is still rejected
        mvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + localJwt.issue(local) + "x")).andExpect(status().isUnauthorized());
    }

    @Test
    void issuerDiscoveryWorksWithoutExplicitJwkSetUri() {
        var cfg = new SecurityProperties.Oidc(ISSUER, "", AUDIENCE, List.of("preferred_username", "sub"), "tenant", "", "roles",
            List.of("realm_access.roles"), Map.of("oms-admins", "admin"), Duration.ofSeconds(60));
        var discovered = new OidcTokenAuthenticator(cfg);
        Principal p = discovered.authenticate(idpToken(b -> {})).orElseThrow();
        assertThat(p.id()).isEqualTo("alice");
        assertThat(p.roles()).containsExactly("admin");
        assertThat(discovered.authenticate(idpToken(b -> b.audience("nope")))).isEmpty();
    }

    @Test
    void unreachableIdpDoesNotBreakRequestsOrStartup() {
        var cfg = new SecurityProperties.Oidc("http://localhost:1/realms/none", "", "", List.of("sub"), "tenant", "", "roles", List.of(), Map.of(), Duration.ofSeconds(60));
        var broken = new OidcTokenAuthenticator(cfg);
        assertThat(broken.enabled()).isTrue();
        assertThat(broken.authenticate(idpToken(b -> {}))).isEmpty();
    }
}
