package ai.intentoms.order.capture;

import ai.intentoms.order.model.CatalogItem;
import ai.intentoms.platform.error.NotFoundException;
import ai.intentoms.platform.tenant.TenantContext;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Tenant SKU catalogue (FR-CAP-05 item and price sanity, FR-FIN-04 COGS, compliance flags). Items are cached per tenant
 * for 5 s so capture never pays a round trip per line on the hot path (NFR-01); {@link #upsert} invalidates.
 */
@Service
public class CatalogService {
    private final CatalogRepository repo;
    private final Cache<String, Optional<CatalogItem>> cache = Caffeine.newBuilder().expireAfterWrite(Duration.ofSeconds(5)).maximumSize(100_000).build();

    public CatalogService(CatalogRepository repo) { this.repo = repo; }

    public Optional<CatalogItem> find(String sku) {
        if (sku == null) return Optional.empty();
        return cache.get(TenantContext.require() + "|" + sku, k -> repo.find(sku));
    }

    /** @throws NotFoundException when the sku is unknown to the tenant */
    public CatalogItem get(String sku) { return find(sku).orElseThrow(() -> new NotFoundException("Unknown sku " + sku, Map.of("sku", String.valueOf(sku)))); }

    /** Items for the given skus; unknown skus are absent from the result. */
    public Map<String, CatalogItem> findAll(Collection<String> skus) {
        Map<String, CatalogItem> out = new LinkedHashMap<>();
        if (skus == null) return out;
        for (String sku : skus) find(sku).ifPresent(i -> out.put(sku, i));
        return out;
    }

    public List<CatalogItem> all() { return repo.all(); }

    public CatalogItem upsert(CatalogItem item) {
        if (item.sku() == null || item.sku().isBlank()) throw new ai.intentoms.platform.error.ValidationException("catalog item sku is required");
        repo.upsert(item);
        cache.invalidate(TenantContext.require() + "|" + item.sku());
        return item;
    }
}
