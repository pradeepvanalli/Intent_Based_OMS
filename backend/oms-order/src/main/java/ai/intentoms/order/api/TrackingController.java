package ai.intentoms.order.api;

import ai.intentoms.order.tracking.TrackingService;
import ai.intentoms.order.tracking.TrackingTokenService;
import ai.intentoms.order.tracking.TrackingView;
import ai.intentoms.platform.config.TenantConfigStore;
import ai.intentoms.platform.ratelimit.RateLimiter;
import ai.intentoms.platform.security.ClientIp;
import ai.intentoms.platform.security.Permissions;
import ai.intentoms.platform.security.RequiresPermission;
import ai.intentoms.platform.security.SecurityProperties;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.time.Duration;
import java.time.Instant;

/**
 * Customer-facing order tracking. {@code POST /api/v1/orders/{id}/tracking-link} (order.read) mints a 30-day signed
 * link; {@code GET /public/track/{token}} is unauthenticated (public path, see {@code oms.security.public-paths}),
 * resolves the tenant from the token and is lightly rate limited per client address.
 */
@RestController
public class TrackingController {
    /** Tenant config key: absolute origin for links (e.g. {@code https://shop.example.com}); defaults to the request origin. */
    public static final String PUBLIC_BASE_URL = "tracking.public_base_url";
    static final long PUBLIC_PER_MINUTE = 60;

    public record TrackingLink(String url, String token, Instant expiresAt) {}

    private final TrackingService tracking;
    private final TenantConfigStore config;
    private final RateLimiter rateLimiter;
    private final SecurityProperties security;

    public TrackingController(TrackingService tracking, TenantConfigStore config, RateLimiter rateLimiter, SecurityProperties security) {
        this.tracking = tracking;
        this.config = config;
        this.rateLimiter = rateLimiter;
        this.security = security;
    }

    @PostMapping("/api/v1/orders/{id}/tracking-link")
    @RequiresPermission(Permissions.ORDER_READ)
    public TrackingLink link(@PathVariable String id, HttpServletRequest req) {
        TrackingTokenService.Issued issued = tracking.issue(id);
        String base = config.getString(PUBLIC_BASE_URL, null);
        String url = base != null && !base.isBlank()
            ? base.replaceAll("/+$", "") + "/track/" + issued.token()
            : ServletUriComponentsBuilder.fromRequestUri(req).replacePath("/track/" + issued.token()).replaceQuery(null).build().toUriString();
        return new TrackingLink(url, issued.token(), issued.expiresAt());
    }

    @GetMapping("/public/track/{token}")
    public TrackingView track(@PathVariable String token, HttpServletRequest req) {
        rateLimiter.acquireOrThrow("track:" + ClientIp.of(req, security.trustForwardedFor()), PUBLIC_PER_MINUTE, Duration.ofMinutes(1), "order tracking");
        return tracking.track(token);
    }
}
