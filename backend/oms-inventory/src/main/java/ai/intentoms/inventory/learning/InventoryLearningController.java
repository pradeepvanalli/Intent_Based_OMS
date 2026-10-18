package ai.intentoms.inventory.learning;

import ai.intentoms.learning.governance.ParameterResolver;
import ai.intentoms.learning.model.FactKey;
import ai.intentoms.learning.model.LearnedFact;
import ai.intentoms.learning.model.SubjectType;
import ai.intentoms.learning.model.TypedValue;
import ai.intentoms.learning.store.LearnedFactRepository;
import ai.intentoms.platform.error.ValidationException;
import ai.intentoms.platform.security.Permissions;
import ai.intentoms.platform.security.RequiresPermission;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Inventory-learning surface: supplier receipts in (IU-05), and what the inventory learners currently believe out
 * (stock age, transfer suggestions, supplier reliability, and whether each learned parameter is live).
 */
@RestController
public class InventoryLearningController {
    private final InventoryLearningRepository repo;
    private final LearnedFactRepository facts;
    private final ParameterResolver resolver;
    private final LearnedInventoryParameters learned;

    public InventoryLearningController(InventoryLearningRepository repo, LearnedFactRepository facts, ParameterResolver resolver, LearnedInventoryParameters learned) {
        this.repo = repo;
        this.facts = facts;
        this.resolver = resolver;
        this.learned = learned;
    }

    /** One ASN line as received. {@code asnId} makes re-sends idempotent; times default to now. */
    public record ReceiptRequest(@NotBlank String supplierId, String asnId, @NotBlank String sku, @NotBlank String nodeId, int expectedQty, int receivedQty,
                                 Instant expectedAt, Instant receivedAt) {}

    public record ReceiptBatch(List<ReceiptRequest> receipts) {}

    @PostMapping("/api/v1/inventory/inbound-receipts")
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresPermission(Permissions.INVENTORY_WRITE)
    public Map<String, Object> receipts(@RequestBody ReceiptBatch body) {
        if (body == null || body.receipts() == null || body.receipts().isEmpty()) throw new ValidationException("receipts must not be empty");
        Instant now = Instant.now();
        int recorded = 0, duplicates = 0;
        for (ReceiptRequest r : body.receipts()) {
            if (r.supplierId() == null || r.supplierId().isBlank() || r.sku() == null || r.sku().isBlank() || r.nodeId() == null || r.nodeId().isBlank())
                throw new ValidationException("supplierId, sku and nodeId are required on every receipt");
            if (r.expectedQty() < 0 || r.receivedQty() < 0) throw new ValidationException("quantities must be >= 0", Map.of("sku", r.sku()));
            var id = repo.insertReceipt(new InventoryLearningRepository.Receipt(r.supplierId(), r.asnId(), r.sku(), r.nodeId(), r.expectedQty(), r.receivedQty(),
                r.expectedAt() == null ? now : r.expectedAt(), r.receivedAt() == null ? now : r.receivedAt()));
            if (id.isPresent()) recorded++; else duplicates++;
        }
        return Map.of("recorded", recorded, "duplicates", duplicates);
    }

    @GetMapping("/api/v1/inventory/learning/stock-age")
    @RequiresPermission(Permissions.INVENTORY_READ)
    public List<Map<String, Object>> stockAge(@RequestParam(value = "limit", defaultValue = "100") int limit) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (LearnedFact f : facts.activeByKey(SubjectType.SKU_NODE, FactKey.STOCK_AGE_DAYS, 100_000)) {
            String[] parts = f.subjectId().split("\\|", 2);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("sku", parts[0]);
            m.put("nodeId", parts.length > 1 ? parts[1] : "");
            m.put("days", number(f));
            m.put("units", f.sampleSize());
            m.put("asOf", f.validFrom());
            out.add(m);
        }
        out.sort(Comparator.comparingDouble((Map<String, Object> m) -> ((Number) m.get("days")).doubleValue()).reversed());
        return out.subList(0, Math.min(Math.max(1, limit), out.size()));
    }

    @GetMapping("/api/v1/inventory/learning/transfers")
    @RequiresPermission(Permissions.INVENTORY_READ)
    public List<Map<String, Object>> transfers() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (LearnedFact f : facts.activeByKey(SubjectType.SKU, FactKey.TRANSFER_RECOMMENDATION, 100_000)) {
            if (!(f.value() instanceof TypedValue.ListValue l)) continue;
            List<Map<String, Object>> moves = new ArrayList<>();
            for (String label : l.values()) {
                int gt = label.indexOf('>'), colon = label.lastIndexOf(':');
                if (gt < 0 || colon < gt) continue;
                moves.add(Map.of("fromNode", label.substring(0, gt), "toNode", label.substring(gt + 1, colon), "units", Integer.parseInt(label.substring(colon + 1))));
            }
            out.add(Map.of("sku", f.subjectId(), "transfers", moves, "recommendOnly", true, "asOf", f.validFrom()));
        }
        return out;
    }

    @GetMapping("/api/v1/inventory/learning/suppliers")
    @RequiresPermission(Permissions.INVENTORY_READ)
    public List<Map<String, Object>> suppliers() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (LearnedFact f : facts.activeByKey(SubjectType.SUPPLIER, FactKey.INBOUND_RELIABILITY, 10_000)) {
            out.add(Map.of("supplierId", f.subjectId(), "reliability", number(f), "receipts", f.sampleSize(), "asOf", f.validFrom(),
                "servedInAtp", learned.inboundReliability(f.subjectId())));
        }
        return out;
    }

    /** Which learned inventory parameters live paths are using right now, and the reservation TTL actually applied. */
    @GetMapping("/api/v1/inventory/learning/parameters")
    @RequiresPermission(Permissions.INVENTORY_READ)
    public Map<String, Object> parameters() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(LearnedInventoryParameters.SAFETY_STOCK, Map.of("live", resolver.isLive(LearnedInventoryParameters.SAFETY_STOCK)));
        m.put(LearnedInventoryParameters.INBOUND_RELIABILITY, Map.of("live", resolver.isLive(LearnedInventoryParameters.INBOUND_RELIABILITY)));
        Map<String, Object> ttl = new LinkedHashMap<>();
        ttl.put("live", resolver.isLive(LearnedInventoryParameters.RESERVATION_TTL));
        ttl.put("appliedMinutes", learned.defaultReservationTtl().map(d -> d.toSeconds() / 60.0).orElse(null));
        facts.active(SubjectType.SEGMENT, LearnedInventoryParameters.TENANT_SEGMENT, FactKey.RESERVATION_TTL_MINUTES).ifPresent(f -> ttl.put("learnedMinutes", number(f)));
        m.put(LearnedInventoryParameters.RESERVATION_TTL, ttl);
        return m;
    }

    private static double number(LearnedFact f) { return f.value() instanceof TypedValue.NumberValue n ? n.value() : 0.0; }
}
