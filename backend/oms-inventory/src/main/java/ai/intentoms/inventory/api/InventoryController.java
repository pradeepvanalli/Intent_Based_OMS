package ai.intentoms.inventory.api;

import ai.intentoms.inventory.model.Atp;
import ai.intentoms.inventory.model.InventoryPosition;
import ai.intentoms.inventory.model.ReconciliationReport;
import ai.intentoms.inventory.service.InventoryService;
import ai.intentoms.platform.error.ValidationException;
import ai.intentoms.platform.ledger.Actor;
import ai.intentoms.platform.security.AuthorizationService;
import ai.intentoms.platform.security.Permissions;
import ai.intentoms.platform.security.RequiresPermission;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** REST for ATP, feeds and reconciliation (FR-INV-01, 03, 04). */
@RestController
@RequestMapping("/api/v1/inventory")
public class InventoryController {
    /** Upper bound on skus per network call so one request cannot monopolise the promise path. */
    static final int MAX_NETWORK_SKUS = 500;

    public record FeedRequest(@NotBlank String source, @NotEmpty List<InventoryPosition> positions) {}
    public record FeedResponse(int positions, int nodes) {}
    public record AdjustRequest(@NotBlank String sku, @NotBlank String nodeId, int delta, @NotBlank String reason) {}
    public record ReconcileRequest(@NotEmpty List<InventoryPosition> positions) {}

    private final InventoryService inventory;
    private final AuthorizationService authz;

    public InventoryController(InventoryService inventory, AuthorizationService authz) {
        this.inventory = inventory;
        this.authz = authz;
    }

    /** Network availability for a comma-separated sku list; the promise path calls this (NFR-01). */
    @GetMapping("/network")
    @RequiresPermission({Permissions.INVENTORY_READ, Permissions.PROMISE_READ})
    public Map<String, Integer> network(@RequestParam("skus") String skus) {
        Set<String> set = new LinkedHashSet<>();
        for (String s : skus.split(",")) if (!s.isBlank()) set.add(s.trim());
        if (set.isEmpty()) throw new ValidationException("skus must list at least one sku");
        if (set.size() > MAX_NETWORK_SKUS) throw new ValidationException("At most " + MAX_NETWORK_SKUS + " skus per request", Map.of("requested", set.size()));
        return inventory.networkAtp(set);
    }

    @GetMapping("/{sku}")
    @RequiresPermission(Permissions.INVENTORY_READ)
    public List<Atp> atp(@PathVariable String sku) { return inventory.atp(sku); }

    @GetMapping("/{sku}/nodes/{nodeId}")
    @RequiresPermission(Permissions.INVENTORY_READ)
    public Atp atp(@PathVariable String sku, @PathVariable String nodeId) { return inventory.atp(sku, nodeId); }

    @PostMapping("/feed")
    @RequiresPermission(Permissions.INVENTORY_WRITE)
    public FeedResponse feed(@Valid @RequestBody FeedRequest req) {
        inventory.upsert(req.positions(), req.source());
        return new FeedResponse(req.positions().size(), (int) req.positions().stream().map(InventoryPosition::nodeId).distinct().count());
    }

    @PostMapping("/adjust")
    @RequiresPermission(Permissions.INVENTORY_WRITE)
    public Atp adjust(@Valid @RequestBody AdjustRequest req) {
        inventory.adjust(req.sku(), req.nodeId(), req.delta(), req.reason(), Actor.parse(authz.actorRef()));
        return inventory.atp(req.sku(), req.nodeId());
    }

    @PostMapping("/reconcile")
    @RequiresPermission(Permissions.INVENTORY_WRITE)
    public ReconciliationReport reconcile(@Valid @RequestBody ReconcileRequest req) { return inventory.reconcile(req.positions()); }
}
