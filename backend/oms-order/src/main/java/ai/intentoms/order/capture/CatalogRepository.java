package ai.intentoms.order.capture;

import ai.intentoms.order.model.CatalogItem;
import ai.intentoms.platform.jdbc.Jsonb;
import ai.intentoms.platform.json.Json;
import ai.intentoms.platform.tenant.TenantContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Table {@code catalog_item} (tenant_id, sku, name, attributes jsonb). */
@Repository
public class CatalogRepository {
    private final JdbcClient jdbc;

    public CatalogRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    public void upsert(CatalogItem item) {
        jdbc.sql("insert into catalog_item(tenant_id, sku, name, attributes) values (:t, :sku, :n, :a) on conflict (tenant_id, sku) do update set name = excluded.name, attributes = excluded.attributes, updated_at = now()")
            .param("t", TenantContext.require()).param("sku", item.sku()).param("n", item.name()).param("a", Jsonb.of(item.attributes())).update();
    }

    public Optional<CatalogItem> find(String sku) {
        return jdbc.sql("select sku, name, attributes::text as attributes from catalog_item where tenant_id = :t and sku = :sku").param("t", TenantContext.require()).param("sku", sku)
            .query().listOfRows().stream().findFirst().map(CatalogRepository::map);
    }

    public Map<String, CatalogItem> findAll(Collection<String> skus) {
        Map<String, CatalogItem> out = new LinkedHashMap<>();
        if (skus == null || skus.isEmpty()) return out;
        jdbc.sql("select sku, name, attributes::text as attributes from catalog_item where tenant_id = :t and sku in (:skus)").param("t", TenantContext.require()).param("skus", List.copyOf(skus))
            .query().listOfRows().forEach(r -> { CatalogItem i = map(r); out.put(i.sku(), i); });
        return out;
    }

    public List<CatalogItem> all() {
        return jdbc.sql("select sku, name, attributes::text as attributes from catalog_item where tenant_id = :t order by sku").param("t", TenantContext.require())
            .query().listOfRows().stream().map(CatalogRepository::map).toList();
    }

    private static CatalogItem map(Map<String, Object> r) { return new CatalogItem((String) r.get("sku"), (String) r.get("name"), Json.readMap((String) r.get("attributes"))); }
}
