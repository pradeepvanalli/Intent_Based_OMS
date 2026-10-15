package ai.intentoms.order.api;

import ai.intentoms.order.capture.CaptureRequest;
import ai.intentoms.order.model.Note;
import ai.intentoms.order.model.Order;
import ai.intentoms.order.model.OrderQuery;
import ai.intentoms.order.model.OrderSummaryView;
import ai.intentoms.order.model.OrderTimelineEntry;
import ai.intentoms.order.model.OrderView;
import ai.intentoms.order.modification.Feasibility;
import ai.intentoms.order.modification.Modification;
import ai.intentoms.order.modification.OrderModificationService;
import ai.intentoms.order.service.IdempotentRequests;
import ai.intentoms.order.service.OrderService;
import ai.intentoms.platform.error.ValidationException;
import ai.intentoms.platform.jdbc.Page;
import ai.intentoms.platform.security.Permissions;
import ai.intentoms.platform.security.RequiresPermission;
import ai.intentoms.statemachine.HoldService;
import ai.intentoms.statemachine.model.Hold;
import com.fasterxml.jackson.core.type.TypeReference;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * REST surface of the order module (ARCHITECTURE §3.6, UI contract items 4 and 5): capture, search, order view, timeline,
 * modifications, cancel, holds and notes. Mutations honour the optional {@code Idempotency-Key} header.
 */
@RestController
@RequestMapping("/api/v1/orders")
public class OrderController {
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};
    private static final TypeReference<Hold> HOLD = new TypeReference<>() {};

    public record ModificationRequest(Modification modification, String reason) {}
    public record CancelRequest(String reason) {}
    public record HoldRequest(@NotBlank String type, @NotEmpty Set<String> blocks, String reasonCode, String note, String ttl) {}
    public record NoteRequest(@NotBlank String text, String source, String author, Boolean customerVisible) {}

    private final OrderService orders;
    private final OrderModificationService modifications;
    private final HoldService holds;
    private final IdempotentRequests idempotent;

    public OrderController(OrderService orders, OrderModificationService modifications, HoldService holds, IdempotentRequests idempotent) {
        this.orders = orders;
        this.modifications = modifications;
        this.holds = holds;
        this.idempotent = idempotent;
    }

    /** Capture (FR-CAP-01/04): 201 + Location for a new order, 200 when the request replays an existing capture. */
    @PostMapping
    @RequiresPermission(Permissions.ORDER_CREATE)
    public ResponseEntity<Order> capture(@RequestBody CaptureRequest req, @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey, UriComponentsBuilder uri) {
        OrderService.CaptureResult r = orders.captureDetailed(req, idempotencyKey);
        if (r.replayed()) return ResponseEntity.ok(r.order());
        return ResponseEntity.created(uri.path("/api/v1/orders/{id}").buildAndExpand(r.order().id()).toUri()).body(r.order());
    }

    /** Search with the UI filters: q, status (comma list), channel, orderType, nodeId, customerRef, placedFrom, placedTo, atRisk, ageBucket, offset, limit. */
    @GetMapping
    @RequiresPermission(Permissions.ORDER_READ)
    public Page<OrderSummaryView> search(@RequestParam(required = false) String q, @RequestParam(required = false) String status, @RequestParam(required = false) String channel,
                                         @RequestParam(required = false) String orderType, @RequestParam(required = false) String nodeId, @RequestParam(required = false) String customerRef,
                                         @RequestParam(required = false) String placedFrom, @RequestParam(required = false) String placedTo, @RequestParam(required = false) Boolean atRisk,
                                         @RequestParam(required = false) String ageBucket, @RequestParam(required = false) Boolean agentOrders, @RequestParam(required = false) Boolean belowFloor,
                                         @RequestParam(defaultValue = "0") int offset, @RequestParam(defaultValue = "50") int limit) {
        OrderQuery query = OrderQueryMapper.map(q, status, channel, orderType, nodeId, customerRef, placedFrom, placedTo, ageBucket, agentOrders, belowFloor, offset, limit, Instant.now());
        return orders.searchView(query, atRisk);
    }

    @GetMapping("/{id}")
    @RequiresPermission(Permissions.ORDER_READ)
    public Order get(@PathVariable String id) { return orders.get(id); }

    @GetMapping("/{id}/view")
    @RequiresPermission(Permissions.ORDER_READ)
    public OrderView view(@PathVariable String id) { return orders.view(id); }

    @GetMapping("/{id}/timeline")
    @RequiresPermission(Permissions.ORDER_READ)
    public List<OrderTimelineEntry> timeline(@PathVariable String id) { return orders.timeline(id); }

    @PostMapping("/{id}/modifications/check")
    @RequiresPermission(Permissions.ORDER_READ)
    public Feasibility check(@PathVariable String id, @RequestBody Modification m) { return modifications.check(id, m); }

    @PostMapping("/{id}/modifications")
    @RequiresPermission(value = {Permissions.ORDER_MODIFY, Permissions.ORDER_CANCEL})
    public Order modify(@PathVariable String id, @RequestBody ModificationRequest req, @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        if (req == null || req.modification() == null) throw new ValidationException("modification is required");
        return idempotent.execute("orders.modify:" + id, idempotencyKey, req, new TypeReference<Order>() {}, () -> modifications.apply(id, req.modification(), req.reason(), null));
    }

    @PostMapping("/{id}/cancel")
    @RequiresPermission(Permissions.ORDER_CANCEL)
    public Order cancel(@PathVariable String id, @RequestBody(required = false) CancelRequest req, @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        String reason = req == null ? null : req.reason();
        return idempotent.execute("orders.cancel:" + id, idempotencyKey, Map.of("reason", String.valueOf(reason)), new TypeReference<Order>() {},
            () -> modifications.apply(id, new Modification.CancelOrder(), reason, null));
    }

    /** Places a hold on the order (scope {@code order}; also blocks its lines) (FR-SM-07). */
    @PostMapping("/{id}/holds")
    @RequiresPermission(Permissions.ORDER_HOLD)
    public ResponseEntity<Hold> hold(@PathVariable String id, @RequestBody HoldRequest req, @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        orders.get(id);
        Duration ttl = null;
        if (req.ttl() != null && !req.ttl().isBlank()) {
            try { ttl = Duration.parse(req.ttl()); } catch (DateTimeParseException e) { throw new ValidationException("ttl must be an ISO-8601 duration such as PT24H"); }
        }
        Duration finalTtl = ttl;
        Hold h = idempotent.execute("orders.hold:" + id, idempotencyKey, req, HOLD, () -> holds.place(req.type(), "order", id, req.blocks(), req.reasonCode(), req.note(), finalTtl, null));
        return ResponseEntity.status(HttpStatus.CREATED).body(h);
    }

    @PostMapping("/{id}/notes")
    @RequiresPermission(Permissions.ORDER_MODIFY)
    public Order addNote(@PathVariable String id, @RequestBody NoteRequest req) {
        return orders.addNote(id, new Note(req.source(), req.author(), req.text(), null, Boolean.TRUE.equals(req.customerVisible()), null));
    }

    /** Maps the UI's flat query parameters onto {@link OrderQuery}. */
    static final class OrderQueryMapper {
        /** UI age buckets: {@code < 1h, 1–4h, 4–24h, 1–3d, > 3d} (also accepted as lt1h, 1-4h, 4-24h, 1-3d, gt3d). */
        static OrderQuery map(String q, String status, String channel, String orderType, String nodeId, String customerRef, String placedFrom, String placedTo, String ageBucket,
                              Boolean agentOrders, Boolean belowFloor, int offset, int limit, Instant now) {
            List<String> statuses = status == null || status.isBlank() ? null : Arrays.stream(status.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
            Instant from = parse(placedFrom, "placedFrom"), to = parse(placedTo, "placedTo");
            if (ageBucket != null && !ageBucket.isBlank()) {
                Duration[] bounds = bucket(ageBucket);
                Instant bucketTo = bounds[0] == null ? null : now.minus(bounds[0]);   // younger bound → latest placed_at
                Instant bucketFrom = bounds[1] == null ? null : now.minus(bounds[1]); // older bound → earliest placed_at
                if (bucketFrom != null && (from == null || bucketFrom.isAfter(from))) from = bucketFrom;
                if (bucketTo != null && (to == null || bucketTo.isBefore(to))) to = bucketTo;
            }
            return new OrderQuery(statuses, blank(channel), blank(orderType), blank(customerRef), blank(nodeId), from, to, blank(q), agentOrders, belowFloor, offset, limit);
        }

        static Duration[] bucket(String b) {
            String k = b.trim().replace('–', '-').replace(" ", "").toLowerCase();
            return switch (k) {
                case "<1h", "lt1h" -> new Duration[]{null, Duration.ofHours(1)};
                case "1-4h" -> new Duration[]{Duration.ofHours(1), Duration.ofHours(4)};
                case "4-24h" -> new Duration[]{Duration.ofHours(4), Duration.ofHours(24)};
                case "1-3d", "24-72h" -> new Duration[]{Duration.ofHours(24), Duration.ofHours(72)};
                case ">3d", "gt3d" -> new Duration[]{Duration.ofHours(72), null};
                default -> throw new ValidationException("Unknown ageBucket '" + b + "' (expected < 1h, 1–4h, 4–24h, 1–3d, > 3d)");
            };
        }

        private static Instant parse(String v, String field) {
            if (v == null || v.isBlank()) return null;
            try { return Instant.parse(v); }
            catch (DateTimeParseException e) {
                try { return java.time.LocalDate.parse(v).atStartOfDay(java.time.ZoneOffset.UTC).toInstant(); }
                catch (DateTimeParseException e2) { throw new ValidationException(field + " must be an ISO-8601 instant or date"); }
            }
        }

        private static String blank(String s) { return s == null || s.isBlank() ? null : s.trim(); }
    }
}
