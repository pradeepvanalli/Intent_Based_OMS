package ai.intentoms.platform.security;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** Claim → Principal mapping without any network: Keycloak, Entra and plain "roles" shapes (NFR-07). */
class OidcClaimMappingTest {
    private final OidcTokenAuthenticator auth = new OidcTokenAuthenticator(SecurityTestSupport.oidc("https://idp.example.com/realms/oms", "", "oms-api",
        Map.of("oms-admins", "admin", "OMS Ops", "ops_analyst", "3f1c2c4e-aaaa-bbbb-cccc-000000000001", "cs_rep|finance_controller", "/ops/warehouse", "store_associate")));

    @Test
    void keycloakRealmRolesAndMappingProduceOmsRolesAndPermissions() {
        Optional<Principal> p = auth.map(Map.of(
            "sub", "f81d4fae-7dec-11d0-a765-00a0c91e6bf6",
            "preferred_username", "alice",
            "tenant", "acme",
            "realm_access", Map.of("roles", List.of("offline_access", "oms-admins", "uma_authorization")),
            "roles", List.of("risk_analyst")));
        assertThat(p).isPresent();
        Principal pr = p.get();
        assertThat(pr.type()).isEqualTo(Principal.Type.USER);
        assertThat(pr.id()).isEqualTo("alice");
        assertThat(pr.tenantId()).isEqualTo("acme");
        assertThat(pr.roles()).containsExactlyInAnyOrder("admin", "risk_analyst");
        assertThat(pr.has("order.cancel")).isTrue();
        assertThat(pr.trustTier()).isEqualTo(3);
    }

    @Test
    void entraGroupsMapToMultipleRolesAndUnknownGroupsAreDropped() {
        Principal p = auth.map(Map.of("sub", "entra-oid", "tenant", "acme",
            "groups", List.of("3f1c2c4e-aaaa-bbbb-cccc-000000000001", "11111111-2222-3333-4444-555555555555"))).orElseThrow();
        assertThat(p.id()).isEqualTo("entra-oid");
        assertThat(p.roles()).containsExactlyInAnyOrder("cs_rep", "finance_controller");
        assertThat(p.has("refund.approve")).isTrue();
        assertThat(p.has("config.admin")).isFalse();
    }

    @Test
    void keycloakGroupPathsAndSpaceSeparatedScopesWork() {
        Principal p = auth.map(Map.of("sub", "bob", "tenant", "acme", "roles", "store_associate ops_analyst", "groups", List.of("/ops/warehouse"))).orElseThrow();
        assertThat(p.roles()).containsExactlyInAnyOrder("store_associate", "ops_analyst");
    }

    @Test
    void missingTenantIsRejectedUnlessDefaultTenantConfigured() {
        assertThat(auth.map(Map.of("sub", "bob", "roles", List.of("admin")))).isEmpty();
        var single = new OidcTokenAuthenticator(new SecurityProperties.Oidc("https://idp", "", "", List.of("sub"), "tenant", "acme", "roles", List.of(), Map.of(), java.time.Duration.ofSeconds(30)));
        assertThat(single.map(Map.of("sub", "bob", "roles", List.of("admin"))).orElseThrow().tenantId()).isEqualTo("acme");
        assertThat(single.map(Map.of("sub", "bob", "tenant", "other")).orElseThrow().tenantId()).isEqualTo("other");
    }

    @Test
    void slashPathsAndMissingSubjectAreHandled() {
        assertThat(OidcTokenAuthenticator.path(Map.of("resource_access", Map.of("oms", Map.of("roles", List.of("x")))), "/resource_access/oms/roles")).isEqualTo(List.of("x"));
        assertThat(OidcTokenAuthenticator.path(Map.of("a", 1), "a.b")).isNull();
        assertThat(auth.map(Map.of("tenant", "acme"))).isEmpty();
    }

    @Test
    void algorithmSniffingRoutesTokens() {
        String rs = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString("{\"alg\":\"RS256\",\"kid\":\"k1\"}".getBytes()) + ".e30.sig";
        String hs = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString("{\"alg\":\"HS256\"}".getBytes()) + ".e30.sig";
        assertThat(OidcTokenAuthenticator.looksAsymmetric(rs)).isTrue();
        assertThat(OidcTokenAuthenticator.looksAsymmetric(hs)).isFalse();
        assertThat(OidcTokenAuthenticator.looksAsymmetric("garbage")).isFalse();
    }

    @Test
    void disabledAuthenticatorNeverAuthenticates() {
        var off = new OidcTokenAuthenticator(SecurityTestSupport.oidc("", "", "", Map.of()));
        assertThat(off.enabled()).isFalse();
        assertThat(off.authenticate("x.y.z")).isEmpty();
    }
}
