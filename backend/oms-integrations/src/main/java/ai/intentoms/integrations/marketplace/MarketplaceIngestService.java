package ai.intentoms.integrations.marketplace;

import ai.intentoms.integrations.config.IntegrationsConfigKeys;
import ai.intentoms.order.capture.CaptureRequest;
import ai.intentoms.order.service.OrderService;
import ai.intentoms.platform.config.TenantConfigStore;
import ai.intentoms.platform.json.Json;
import ai.intentoms.platform.observability.Metrics;
import ai.intentoms.platform.time.TimeSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Shared ingestion path for the marketplace webhook and the SP-API poller (FR-CAP-01, FR-CAP-04): map → capture with
 * idempotency key {@code marketplace:<externalOrderId>} → record in {@code marketplace_import}. Replays return the
 * existing order ({@code replayed=true}); mapping or validation failures are recorded as {@code failed} and rethrown so
 * the caller answers 422.
 */
@Service
public class MarketplaceIngestService {
    private static final Logger log = LoggerFactory.getLogger(MarketplaceIngestService.class);
    private final OrderService orders;
    private final MarketplaceImportRepository imports;
    private final TenantConfigStore config;
    private final TimeSource time;
    private final Metrics metrics;

    public MarketplaceIngestService(OrderService orders, MarketplaceImportRepository imports, TenantConfigStore config, TimeSource time, Metrics metrics) {
        this.orders = orders;
        this.imports = imports;
        this.config = config;
        this.time = time;
        this.metrics = metrics;
    }

    public OrderService.CaptureResult ingest(String marketplace, Map<String, Object> payload) {
        String externalId = MarketplaceOrderMapper.externalOrderId(payload);
        String orderType = config.getString(IntegrationsConfigKeys.marketplaceOrderType(marketplace), CaptureRequest.DEFAULT_ORDER_TYPE);
        String hash = Json.sha256(payload);
        try {
            CaptureRequest req = MarketplaceOrderMapper.map(marketplace, payload, orderType);
            OrderService.CaptureResult r = orders.captureDetailed(req, marketplace + ":" + externalId);
            imports.record(marketplace, externalId, r.order().id(), r.replayed() ? "replayed" : "captured", null, hash, time.now());
            metrics.count("oms.integrations.marketplace.ingested", "marketplace", marketplace, "replay", String.valueOf(r.replayed()));
            log.info("Marketplace {} order {} → OMS order {}{}", marketplace, externalId, r.order().id(), r.replayed() ? " [replayed]" : "");
            return r;
        } catch (RuntimeException e) {
            imports.record(marketplace, externalId, null, "failed", e.getMessage(), hash, time.now());
            metrics.count("oms.integrations.marketplace.failed", "marketplace", marketplace);
            throw e;
        }
    }
}
