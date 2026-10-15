package ai.intentoms.integrations.marketplace;

import ai.intentoms.integrations.config.IntegrationsConfigKeys;
import ai.intentoms.integrations.config.IntegrationsProperties;
import ai.intentoms.platform.config.TenantConfigStore;
import ai.intentoms.platform.json.Json;
import ai.intentoms.platform.observability.Metrics;
import ai.intentoms.platform.security.Principal;
import ai.intentoms.platform.security.PrincipalAuthentication;
import ai.intentoms.platform.tenant.TenantContext;
import ai.intentoms.platform.time.TimeSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Amazon SP-API order poller (FR-CAP-01; section 11 marketplaces). Off unless {@code oms.integrations.marketplace.amazon.enabled=true}
 * and LWA credentials are present. Every {@code pollInterval} it fetches orders created after the stored watermark
 * ({@code tenant_config marketplace.amazon.watermark}, first run = now − lookback), ingests each through
 * {@link MarketplaceIngestService} (idempotent on {@code AmazonOrderId}) and advances the watermark to the poll start
 * only when the whole page set succeeded. Runs as a SYSTEM principal of the configured tenant.
 */
@Component
@ConditionalOnProperty(prefix = "oms.integrations.marketplace.amazon", name = "enabled", havingValue = "true")
public class MarketplacePoller {
    private static final Logger log = LoggerFactory.getLogger(MarketplacePoller.class);
    public static final String MARKETPLACE = "amazon";
    private final IntegrationsProperties.Amazon props;
    private final AmazonSpApiClient client;
    private final MarketplaceIngestService ingest;
    private final TenantConfigStore config;
    private final TimeSource time;
    private final Metrics metrics;

    public MarketplacePoller(IntegrationsProperties props, AmazonSpApiClient client, MarketplaceIngestService ingest, TenantConfigStore config, TimeSource time, Metrics metrics) {
        this.props = props.marketplace().amazon();
        this.client = client;
        this.ingest = ingest;
        this.config = config;
        this.time = time;
        this.metrics = metrics;
        if (!this.props.credentialsPresent()) log.warn("Amazon SP-API poller enabled without LWA credentials; polls are skipped until clientId/clientSecret/refreshToken are set");
    }

    @Scheduled(fixedDelayString = "${oms.integrations.marketplace.amazon.poll-interval:PT5M}", initialDelayString = "PT30S")
    public void poll() {
        if (!props.credentialsPresent()) return;
        try { pollOnce(); } catch (RuntimeException e) { log.warn("Amazon SP-API poll failed: {}", e.getMessage()); metrics.count("oms.integrations.marketplace.poll_failed", "marketplace", MARKETPLACE); }
    }

    /** One poll under the configured tenant; returns a summary (orders seen / captured / replayed / failed). */
    public Map<String, Object> pollOnce() {
        return TenantContext.supplyAs(props.tenant(), () -> {
            var prev = SecurityContextHolder.getContext().getAuthentication();
            SecurityContextHolder.getContext().setAuthentication(new PrincipalAuthentication(new Principal(Principal.Type.SYSTEM, "amazon-poller", props.tenant(), Set.of("integration"), Set.of("order.create", "order.read"), 3)));
            try {
                Instant started = time.now();
                Instant since = watermark(started);
                List<Map<String, Object>> orders;
                try { orders = client.ordersCreatedAfter(since, started); }
                catch (HttpClientErrorException.TooManyRequests e) { log.info("SP-API throttled; retrying next interval"); return Map.of("throttled", true); }
                int captured = 0, replayed = 0, failed = 0;
                for (Map<String, Object> o : orders) {
                    try { if (ingest.ingest(MARKETPLACE, o).replayed()) replayed++; else captured++; }
                    catch (RuntimeException e) { failed++; log.warn("Amazon order {} rejected: {}", o.get("AmazonOrderId"), e.getMessage()); }
                }
                config.set(IntegrationsConfigKeys.AMAZON_WATERMARK, Json.write(started.toString()));
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("since", since.toString());
                out.put("seen", orders.size());
                out.put("captured", captured);
                out.put("replayed", replayed);
                out.put("failed", failed);
                log.info("Amazon SP-API poll: {}", out);
                return out;
            } finally { SecurityContextHolder.getContext().setAuthentication(prev); }
        });
    }

    Instant watermark(Instant now) {
        String w = config.getString(IntegrationsConfigKeys.AMAZON_WATERMARK, null);
        if (w != null && !w.isBlank()) try { return Instant.parse(w); } catch (Exception ignored) { /* fall through */ }
        return now.minus(props.lookback());
    }
}
