package ai.intentoms.platform.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Browser hardening headers for every response (NFR-07/NFR-08): Content-Security-Policy tuned for the bundled single-page
 * UI, X-Content-Type-Options, Referrer-Policy, Permissions-Policy and (in production) Strict-Transport-Security.
 * Registered as a plain servlet filter at highest precedence so it covers both Spring Security chains (/api and /mcp|/ucp)
 * and static assets alike; Spring Security's own header writers only add headers that are not already present.
 * <p>
 * The SPA's index.html carries a tiny inline theme-bootstrap script; its sha256 is computed from {@code classpath:/static/index.html}
 * at startup and injected where the policy template says {@code {inline-script-hashes}}, so the policy never needs
 * {@code 'unsafe-inline'} for scripts. Swagger UI needs inline scripts and styles, so it gets a relaxed policy.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class SecurityHeadersFilter extends OncePerRequestFilter {
    private static final Logger log = LoggerFactory.getLogger(SecurityHeadersFilter.class);

    public static final String DEFAULT_CSP = "default-src 'self'; script-src 'self' {inline-script-hashes}; style-src 'self' 'unsafe-inline'; "
        + "img-src 'self' data: blob:; font-src 'self' data:; connect-src 'self'; object-src 'none'; base-uri 'self'; form-action 'self'; frame-ancestors 'none'";
    public static final String DEFAULT_SWAGGER_CSP = "default-src 'self'; script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline'; "
        + "img-src 'self' data: blob:; font-src 'self' data:; connect-src 'self'; object-src 'none'; base-uri 'self'; frame-ancestors 'none'";
    public static final String DEFAULT_PERMISSIONS_POLICY = "camera=(), microphone=(), geolocation=(), payment=(), usb=(), interest-cohort=()";
    public static final String HSTS_VALUE = "max-age=31536000; includeSubDomains";

    private static final Pattern INLINE_SCRIPT = Pattern.compile("<script(?![^>]*\\bsrc=)[^>]*>(.*?)</script>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);

    private final String csp;
    private final String swaggerCsp;
    private final String permissionsPolicy;
    private final boolean hsts;

    public SecurityHeadersFilter(SecurityProperties props, Environment env) {
        this.csp = props.contentSecurityPolicy().replace("{inline-script-hashes}", inlineScriptHashes("/static/index.html")).replaceAll("\\s{2,}", " ").trim();
        this.swaggerCsp = props.swaggerContentSecurityPolicy();
        this.permissionsPolicy = props.permissionsPolicy();
        this.hsts = props.hsts() != null ? props.hsts() : isProd(env, props);
    }

    static boolean isProd(Environment env, SecurityProperties props) {
        return props.enforceProduction() || env.acceptsProfiles(org.springframework.core.env.Profiles.of("prod"));
    }

    public String contentSecurityPolicy() { return csp; }
    public boolean hstsEnabled() { return hsts; }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain) throws ServletException, IOException {
        String uri = req.getRequestURI();
        boolean swagger = uri.startsWith("/swagger-ui") || uri.startsWith("/v3/api-docs");
        res.setHeader("Content-Security-Policy", swagger ? swaggerCsp : csp);
        res.setHeader("X-Content-Type-Options", "nosniff");
        res.setHeader("Referrer-Policy", "strict-origin-when-cross-origin");
        res.setHeader("Permissions-Policy", permissionsPolicy);
        res.setHeader("X-Frame-Options", "DENY");
        res.setHeader("Cross-Origin-Opener-Policy", "same-origin");
        if (hsts) res.setHeader("Strict-Transport-Security", HSTS_VALUE);
        chain.doFilter(req, res);
    }

    /** sha256 CSP source expressions for every inline (src-less) script in the given classpath HTML file; empty when absent. */
    static String inlineScriptHashes(String classpathHtml) {
        ClassPathResource r = new ClassPathResource(classpathHtml);
        if (!r.exists()) return "";
        try {
            String html = r.getContentAsString(StandardCharsets.UTF_8);
            Matcher m = INLINE_SCRIPT.matcher(html);
            List<String> out = new ArrayList<>();
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            while (m.find()) {
                String body = m.group(1);
                if (body.isBlank()) continue;
                out.add("'sha256-" + Base64.getEncoder().encodeToString(md.digest(body.getBytes(StandardCharsets.UTF_8))) + "'");
            }
            return String.join(" ", out);
        } catch (Exception e) {
            log.warn("Could not hash inline scripts in {}: {}", classpathHtml, e.toString());
            return "";
        }
    }
}
