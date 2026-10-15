package ai.intentoms.inventory.service;

import ai.intentoms.inventory.config.InventoryProperties;
import ai.intentoms.platform.observability.Metrics;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.stereotype.Component;

import java.util.Collection;

/**
 * Short-TTL in-memory cache for network availability (NFR-01: 20k availability reads/s; promise path). Entries are keyed
 * per (tenant, sku) so a set lookup composes from individual entries and every write to a sku (feed, adjust, reserve,
 * release, expire, consume) can invalidate exactly the affected keys. Entries hold the raw per-sku totals both with and
 * without inbound supply so tenant-config changes (include_inbound, channel_buffer) take effect on the next read.
 */
@Component
public class NetworkAtpCache {
    /** Per-sku network totals (sum over nodes of max(0, node available)). */
    public record Entry(int excludingInbound, int includingInbound) {
        public int total(boolean includeInbound) { return includeInbound ? includingInbound : excludingInbound; }
    }

    private final Cache<String, Entry> cache;
    private final Metrics metrics;

    public NetworkAtpCache(InventoryProperties props, Metrics metrics) {
        this.metrics = metrics;
        this.cache = Caffeine.newBuilder().expireAfterWrite(props.networkCacheTtl()).maximumSize(500_000).build();
    }

    public Entry get(String tenant, String sku) {
        Entry e = cache.getIfPresent(key(tenant, sku));
        metrics.count("oms.inventory.network_cache", "result", e == null ? "miss" : "hit");
        return e;
    }

    public void put(String tenant, String sku, Entry e) { cache.put(key(tenant, sku), e); }

    public void invalidate(String tenant, String sku) { cache.invalidate(key(tenant, sku)); }

    public void invalidateAll(String tenant, Collection<String> skus) {
        for (String sku : skus) cache.invalidate(key(tenant, sku));
    }

    private static String key(String tenant, String sku) { return tenant + "|" + sku; }
}
