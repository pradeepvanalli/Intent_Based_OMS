package ai.intentoms.platform.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class SecurityHeadersFilterTest {

    private static MockHttpServletResponse run(SecurityHeadersFilter f, String uri) throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", uri);
        req.setRequestURI(uri);
        MockHttpServletResponse res = new MockHttpServletResponse();
        f.doFilter(req, res, new MockFilterChain());
        return res;
    }

    @Test
    void setsHardeningHeadersWithoutHstsOutsideProduction() throws Exception {
        SecurityHeadersFilter f = new SecurityHeadersFilter(SecurityTestSupport.props(null, false), new MockEnvironment());
        MockHttpServletResponse res = run(f, "/orders/ord_1");
        assertThat(res.getHeader("Content-Security-Policy")).startsWith("default-src 'self'; script-src 'self'").contains("frame-ancestors 'none'").doesNotContain("{inline-script-hashes}");
        assertThat(res.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(res.getHeader("Referrer-Policy")).isEqualTo("strict-origin-when-cross-origin");
        assertThat(res.getHeader("Permissions-Policy")).contains("camera=()");
        assertThat(res.getHeader("X-Frame-Options")).isEqualTo("DENY");
        assertThat(res.getHeader("Strict-Transport-Security")).isNull();
        assertThat(f.hstsEnabled()).isFalse();
    }

    @Test
    void hstsOnInProdProfileOrWhenForced() throws Exception {
        MockEnvironment prod = new MockEnvironment();
        prod.setActiveProfiles("prod");
        assertThat(run(new SecurityHeadersFilter(SecurityTestSupport.props(null, false), prod), "/").getHeader("Strict-Transport-Security")).isEqualTo(SecurityHeadersFilter.HSTS_VALUE);
        assertThat(run(new SecurityHeadersFilter(SecurityTestSupport.props(true, false), new MockEnvironment()), "/").getHeader("Strict-Transport-Security")).isEqualTo(SecurityHeadersFilter.HSTS_VALUE);
        assertThat(run(new SecurityHeadersFilter(SecurityTestSupport.props(false, false), prod), "/").getHeader("Strict-Transport-Security")).isNull();
    }

    @Test
    void swaggerGetsRelaxedPolicy() throws Exception {
        SecurityHeadersFilter f = new SecurityHeadersFilter(SecurityTestSupport.props(null, false), new MockEnvironment());
        assertThat(run(f, "/swagger-ui/index.html").getHeader("Content-Security-Policy")).contains("script-src 'self' 'unsafe-inline'");
        assertThat(run(f, "/v3/api-docs").getHeader("Content-Security-Policy")).isEqualTo(SecurityHeadersFilter.DEFAULT_SWAGGER_CSP);
        assertThat(run(f, "/api/v1/orders").getHeader("Content-Security-Policy")).startsWith("default-src 'self'; script-src 'self' 'sha256-").doesNotContain("script-src 'self' 'unsafe-inline'");
    }

    @Test
    void inlineScriptHashesAreComputedFromHtml() {
        // src/test/resources/static/index.html mirrors the UI's theme bootstrap script
        String hashes = SecurityHeadersFilter.inlineScriptHashes("/static/index.html");
        assertThat(hashes).startsWith("'sha256-").endsWith("'");
        assertThat(SecurityHeadersFilter.inlineScriptHashes("/static/missing.html")).isEmpty();
    }
}
