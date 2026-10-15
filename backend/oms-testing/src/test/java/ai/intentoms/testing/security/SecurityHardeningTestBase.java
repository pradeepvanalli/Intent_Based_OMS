package ai.intentoms.testing.security;

import ai.intentoms.testing.PostgresTestBase;
import ai.intentoms.testing.TestApplication;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Instant;
import java.util.Date;
import java.util.Map;
import java.util.function.Consumer;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;

/**
 * Shared context for the security hardening integration tests: a WireMock "identity provider" serving OIDC discovery
 * and a JWKS for a freshly generated RSA key, wired into {@code oms.security.oidc.*}; forwarded-for trust so tests can
 * pick their own client IPs for rate limiting. One Spring context is shared by every subclass.
 */
@SpringBootTest(classes = TestApplication.class, properties = {
    "oms.security.trust-forwarded-for=true",
    "oms.security.login-rate-limit=10",
    "oms.security.login-rate-window=1m"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
public abstract class SecurityHardeningTestBase extends PostgresTestBase {
    public static final String AUDIENCE = "oms-api";
    public static final String KEY_ID = "test-key-1";
    protected static final WireMockServer IDP;
    protected static final RSAKey RSA;
    protected static final RSAKey ROGUE;
    protected static final String ISSUER;

    static {
        try {
            RSA = new RSAKeyGenerator(2048).keyID(KEY_ID).generate();
            ROGUE = new RSAKeyGenerator(2048).keyID("rogue").generate();
        } catch (Exception e) { throw new IllegalStateException(e); }
        IDP = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        IDP.start();
        ISSUER = "http://localhost:" + IDP.port() + "/realms/oms";
        String jwks = new JWKSet(RSA.toPublicJWK()).toString();
        IDP.stubFor(get(urlEqualTo("/realms/oms/protocol/openid-connect/certs")).willReturn(aResponse().withHeader("Content-Type", "application/json").withBody(jwks)));
        IDP.stubFor(get(urlEqualTo("/realms/oms/.well-known/openid-configuration")).willReturn(aResponse().withHeader("Content-Type", "application/json").withBody(
            "{\"issuer\":\"" + ISSUER + "\",\"jwks_uri\":\"" + ISSUER + "/protocol/openid-connect/certs\",\"authorization_endpoint\":\"" + ISSUER + "/auth\",\"token_endpoint\":\"" + ISSUER + "/token\",\"subject_types_supported\":[\"public\"],\"id_token_signing_alg_values_supported\":[\"RS256\"],\"response_types_supported\":[\"code\"]}")));
        Runtime.getRuntime().addShutdownHook(new Thread(IDP::stop));
    }

    @DynamicPropertySource
    static void oidcProps(DynamicPropertyRegistry r) {
        r.add("oms.security.oidc.issuer-uri", () -> ISSUER);
        r.add("oms.security.oidc.jwk-set-uri", () -> ISSUER + "/protocol/openid-connect/certs");
        r.add("oms.security.oidc.audience", () -> AUDIENCE);
        r.add("oms.security.oidc.role-mapping.oms-admins", () -> "admin");
        r.add("oms.security.oidc.role-mapping.[OMS-CS]", () -> "cs_rep");
    }

    /** A token signed by the IdP key with sane defaults; {@code customise} adjusts claims (expiry, audience, roles…). */
    protected static String idpToken(Consumer<JWTClaimsSet.Builder> customise) {
        return sign(RSA, customise);
    }

    protected static String sign(RSAKey key, Consumer<JWTClaimsSet.Builder> customise) {
        try {
            Instant now = Instant.now();
            JWTClaimsSet.Builder b = new JWTClaimsSet.Builder()
                .issuer(ISSUER).subject("f81d4fae-7dec-11d0-a765-00a0c91e6bf6").audience(AUDIENCE)
                .issueTime(Date.from(now)).expirationTime(Date.from(now.plusSeconds(300)))
                .claim("preferred_username", "alice").claim("tenant", TENANT)
                .claim("realm_access", Map.of("roles", java.util.List.of("offline_access", "oms-admins")));
            customise.accept(b);
            SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).type(com.nimbusds.jose.JOSEObjectType.JWT).build(), b.build());
            jwt.sign(new RSASSASigner(key));
            return jwt.serialize();
        } catch (Exception e) { throw new IllegalStateException(e); }
    }
}
