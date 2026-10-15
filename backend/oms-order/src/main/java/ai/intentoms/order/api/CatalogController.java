package ai.intentoms.order.api;

import ai.intentoms.order.capture.CatalogService;
import ai.intentoms.order.model.CatalogItem;
import ai.intentoms.platform.security.Permissions;
import ai.intentoms.platform.security.RequiresPermission;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** The tenant SKU catalogue used for item/price sanity, compliance flags and COGS (FR-CAP-05, FR-FIN-04). */
@RestController
@RequestMapping("/api/v1/catalog")
public class CatalogController {
    private final CatalogService catalog;

    public CatalogController(CatalogService catalog) { this.catalog = catalog; }

    @GetMapping
    @RequiresPermission(Permissions.ORDER_READ)
    public List<CatalogItem> all() { return catalog.all(); }

    @GetMapping("/{sku}")
    @RequiresPermission(Permissions.ORDER_READ)
    public CatalogItem get(@PathVariable String sku) { return catalog.get(sku); }

    @PutMapping("/{sku}")
    @RequiresPermission(Permissions.CONFIG_ADMIN)
    public CatalogItem upsert(@PathVariable String sku, @RequestBody CatalogItem item) { return catalog.upsert(new CatalogItem(sku, item.name(), item.attributes())); }
}
