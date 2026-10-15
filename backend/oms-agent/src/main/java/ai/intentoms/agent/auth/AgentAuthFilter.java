package ai.intentoms.agent.auth;

import ai.intentoms.agent.config.AgentProperties;
import ai.intentoms.agent.registry.AgentIdentity;
import ai.intentoms.agent.registry.AgentRegistry;
import ai.intentoms.platform.json.Json;
import ai.intentoms.platform.observability.Metrics;
import ai.intentoms.platform.ratelimit.RateLimiter;
import ai.intentoms.platform.security.Principal;
import ai.intentoms.platform.security.PrincipalAuthentication;
import ai.intentoms.platform.security.SecurityContextHolderUtil;
import ai.intentoms.platform.tenant.TenantContext;
import ai.intentoms.platform.tenant.TenantFilter;
import ai.intentoms.platform.time.TimeSource;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Authenticates agents on {@code /mcp} and {@code /ucp/**} (FR-AGT-03, FR-AGT-04, GR-14). Two paths:
 * <ol>
 *   <li>Signed requests: headers {@code X-Agent-Id}, {@code X-Agent-Timestamp}, {@code X-Agent-Signature} with
 *       HMAC-SHA256(secret, timestamp\nMETHOD\npath\nsha256(body)); the timestamp must be within ±{@code oms.agent.signature-window}.
 *       Unknown agent, stale timestamp or bad signature → 401 problem detail.</li>
 *   <li>API key / JWT: resolved earlier by the platform {@code AuthenticationFilter}; any principal of type AGENT is then
 *       re-based on the registry row so current limits and tier apply.</li>
 * </ol>
 * Agents are rate limited with their {@code ratePerMinute} (429) and suspended agents are rejected (401). The identity is
 * exposed to controllers as request attribute {@link #ATTR_IDENTITY}. People (USER principals) pass through untouched and
 * use the same tools under the same permission model (FR-AGT-06).
 */
@Component
public class AgentAuthFilter extends OncePerRequestFilter {
    public static final String ATTR_IDENTITY = AgentAuthFilter.class.getName() + ".identity";
    private static final Logger log = LoggerFactory.getLogger(AgentAuthFilter.class);

    private final AgentRegistry registry;
    private final RateLimiter rateLimiter;
    private final AgentProperties props;
    private final TimeSource time;
    private final Metrics metrics;

    public AgentAuthFilter(AgentRegistry registry, RateLimiter rateLimiter, AgentProperties props, TimeSource time, Metrics metrics) {
        this.registry = registry;
        this.rateLimiter = rateLimiter;
        this.props = props;
        this.time = time;
        this.metrics = metrics;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain) throws ServletException, IOException {
        HttpServletRequest request = req;
        String agentHeader = req.getHeader(HmacSigner.HEADER_AGENT);
        if (agentHeader != null && !agentHeader.isBlank() && SecurityContextHolder.getContext().getAuthentication() == null) {
            CachedBodyRequest cached;
            try { cached = new CachedBodyRequest(req, props.maxBodyBytes()); }
            catch (IOException e) { problem(res, 413, "payload_too_large", e.getMessage()); return; }
            request = cached;
            Optional<String> failure = authenticateSigned(cached, agentHeader.trim());
            if (failure.isPresent()) {
                metrics.count("oms.agent.auth", "outcome", "rejected");
                problem(res, 401, "unauthorized", failure.get());
                return;
            }
            metrics.count("oms.agent.auth", "outcome", "signed");
        }

        Optional<Principal> principal = SecurityContextHolderUtil.currentPrincipal();
        if (principal.isPresent() && principal.get().isAgent()) {
            Principal p = principal.get();
            Optional<AgentIdentity> identity = TenantContext.supplyAs(p.tenantId(), () -> registry.find(p.id()));
            if (identity.isPresent()) {
                AgentIdentity a = identity.get();
                if (!a.active()) { problem(res, 401, "agent_suspended", "Agent " + a.id() + " is suspended"); return; }
                Integer rpm = a.limits().ratePerMinute();
                if (rpm != null && !rateLimiter.tryAcquire("agent:" + p.tenantId() + ":" + a.id(), rpm, Duration.ofMinutes(1))) {
                    metrics.count("oms.agent.auth", "outcome", "rate_limited");
                    TenantContext.runAs(p.tenantId(), () -> registry.recordActivity(a.id(), "http", "rate_limited", null, 0));
                    problem(res, 429, "rate_limited", "Agent " + a.id() + " exceeded " + rpm + " requests per minute");
                    return;
                }
                Principal rebased = TenantContext.supplyAs(p.tenantId(), () -> registry.principalFor(a));
                SecurityContextHolder.getContext().setAuthentication(new PrincipalAuthentication(rebased));
                request.setAttribute(ATTR_IDENTITY, a);
                TenantContext.runAs(p.tenantId(), () -> registry.touch(a.id()));
            }
        }
        chain.doFilter(request, res);
    }

    /** Verifies the HMAC headers and sets the security context; returns a failure message otherwise. */
    private Optional<String> authenticateSigned(CachedBodyRequest req, String agentId) {
        String ts = req.getHeader(HmacSigner.HEADER_TIMESTAMP);
        String sig = req.getHeader(HmacSigner.HEADER_SIGNATURE);
        if (ts == null || sig == null) return Optional.of("X-Agent-Timestamp and X-Agent-Signature are required with X-Agent-Id");
        if (!HmacSigner.withinWindow(ts, time.now(), props.signatureWindow())) return Optional.of("X-Agent-Timestamp is outside the accepted window of " + props.signatureWindow().toSeconds() + " s");
        String tenant = req.getHeader(TenantFilter.HEADER);
        if (tenant == null || tenant.isBlank()) {
            List<String> tenants = registry.tenantsOf(agentId);
            if (tenants.size() != 1) return Optional.of(tenants.isEmpty() ? "Unknown agent " + agentId : "Agent id is registered in several tenants; send X-Tenant-Id");
            tenant = tenants.get(0);
        }
        String path = req.getRequestURI();
        String method = req.getMethod();
        String bodyHash = HmacSigner.sha256Hex(req.body());
        return TenantContext.supplyAs(tenant, () -> {
            Optional<AgentIdentity> identity = registry.find(agentId);
            if (identity.isEmpty()) return Optional.of("Unknown agent " + agentId);
            String secret = registry.hmacSecret(agentId);
            if (!HmacSigner.verify(secret, ts, method, path, bodyHash, sig)) {
                log.info("Rejected signature for agent {} on {} {}", agentId, method, path);
                return Optional.of("Invalid request signature");
            }
            SecurityContextHolder.getContext().setAuthentication(new PrincipalAuthentication(registry.principalFor(identity.get())));
            return Optional.empty();
        });
    }

    static void problem(HttpServletResponse res, int status, String code, String detail) throws IOException {
        res.setStatus(status);
        res.setContentType("application/problem+json");
        res.setCharacterEncoding(StandardCharsets.UTF_8.name());
        Map<String, Object> pd = new LinkedHashMap<>();
        pd.put("type", "about:blank");
        pd.put("title", code);
        pd.put("status", status);
        pd.put("detail", detail);
        pd.put("code", code);
        res.getWriter().write(Json.write(pd));
        res.getWriter().flush();
    }
}
