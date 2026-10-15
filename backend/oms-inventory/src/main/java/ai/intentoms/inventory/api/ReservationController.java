package ai.intentoms.inventory.api;

import ai.intentoms.inventory.model.Reservation;
import ai.intentoms.inventory.model.ReservationLine;
import ai.intentoms.inventory.service.ReservationService;
import ai.intentoms.platform.error.ValidationException;
import ai.intentoms.platform.security.Permissions;
import ai.intentoms.platform.security.RequiresPermission;
import com.fasterxml.jackson.core.type.TypeReference;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/** REST for reservations (FR-INV-02). {@code POST} honours the optional {@code Idempotency-Key} header. */
@RestController
@RequestMapping("/api/v1/reservations")
public class ReservationController {
    private static final TypeReference<List<Reservation>> RESERVATIONS = new TypeReference<>() {};

    /**
     * Either a single line ({@code sku, nodeId, qty}) or several {@code lines}; both forms reserve atomically and return
     * the created reservations. {@code ttlSeconds} is ignored when {@code source} is {@code order}.
     */
    public record ReserveRequest(@NotBlank String source, @NotBlank String refId, Long ttlSeconds, String sku, String nodeId, Integer qty, List<ReservationLine> lines) {
        List<ReservationLine> allLines() {
            List<ReservationLine> out = new ArrayList<>();
            if (lines != null) out.addAll(lines);
            if (sku != null || nodeId != null || qty != null) {
                if (sku == null || nodeId == null || qty == null) throw new ValidationException("sku, nodeId and qty must be given together");
                out.add(new ReservationLine(sku, nodeId, qty));
            }
            if (out.isEmpty()) throw new ValidationException("Provide sku/nodeId/qty or at least one line");
            return out;
        }
        Duration ttl() { return ttlSeconds == null ? null : Duration.ofSeconds(ttlSeconds); }
    }
    public record ReleaseRequest(String reason) {}
    public record ConvertRequest(@NotBlank String orderId) {}

    private final ReservationService reservations;
    private final IdempotentRequests idempotent;

    public ReservationController(ReservationService reservations, IdempotentRequests idempotent) {
        this.reservations = reservations;
        this.idempotent = idempotent;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresPermission(Permissions.INVENTORY_WRITE)
    public List<Reservation> reserve(@Valid @RequestBody ReserveRequest req, @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        List<ReservationLine> lines = req.allLines();
        return idempotent.execute("reservations", idempotencyKey, req, RESERVATIONS, () -> reservations.reserveAll(lines, req.source(), req.refId(), req.ttl()));
    }

    @GetMapping
    @RequiresPermission(Permissions.INVENTORY_READ)
    public List<Reservation> forRef(@RequestParam("refId") String refId) { return reservations.forRef(refId); }

    @GetMapping("/{id}")
    @RequiresPermission(Permissions.INVENTORY_READ)
    public Reservation get(@PathVariable String id) { return reservations.get(id); }

    @PostMapping("/{id}/release")
    @RequiresPermission(Permissions.INVENTORY_WRITE)
    public Reservation release(@PathVariable String id, @RequestBody(required = false) ReleaseRequest req) {
        return reservations.release(id, req == null ? null : req.reason());
    }

    @PostMapping("/{id}/convert")
    @RequiresPermission(Permissions.INVENTORY_WRITE)
    public Reservation convert(@PathVariable String id, @Valid @RequestBody ConvertRequest req) { return reservations.convert(id, req.orderId()); }

    @PostMapping("/{id}/consume")
    @RequiresPermission(Permissions.INVENTORY_WRITE)
    public Reservation consume(@PathVariable String id) { return reservations.consume(id); }
}
