package ai.intentoms.order.service;

import ai.intentoms.order.capture.CaptureRequest;
import ai.intentoms.order.model.Note;
import ai.intentoms.order.model.Order;
import ai.intentoms.order.model.OrderQuery;
import ai.intentoms.order.model.OrderSummary;
import ai.intentoms.order.model.OrderSummaryView;
import ai.intentoms.order.model.OrderTimelineEntry;
import ai.intentoms.order.model.OrderView;
import ai.intentoms.platform.jdbc.Page;
import ai.intentoms.platform.ledger.NewEvent;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.function.UnaryOperator;

/**
 * Order capture and the canonical order (ARCHITECTURE §3.6; FR-CAP-01, 02, 04, 05, 06, 07; FR-CS-01).
 * All methods are scoped to {@code TenantContext.require()}.
 */
public interface OrderService {
    /** Outcome of a capture: the order and whether it was an idempotent replay (200 rather than 201). */
    record CaptureResult(Order order, boolean replayed) {}

    /**
     * Captures an order in one transaction (FR-CAP-01, FR-CAP-04): idempotent on (channel, channelOrderId) and on the
     * optional {@code Idempotency-Key}; runs the validation pipeline (FR-CAP-05, 422 with {@code issues} when blocked);
     * enriches lines from the catalogue; quotes tax and totals (FR-FIN-02); computes the margin snapshot (FR-FIN-04);
     * records explicit intent or marks it unclear (FR-CAP-06, resolved by the workflow); stores agent provenance
     * (FR-CAP-07); initialises the header and line state machines from the order type's models (FR-CAP-03); places holds
     * requested by validation; appends {@code order.captured}; starts the order type's workflow. Never calls a model (GR-07).
     */
    Order capture(CaptureRequest req, String idempotencyKey);

    /** {@link #capture} plus the replay flag the REST layer needs for its status code. */
    CaptureResult captureDetailed(CaptureRequest req, String idempotencyKey);

    Order get(String orderId);

    Optional<Order> byChannelRef(String channel, String channelOrderId);

    Page<OrderSummary> search(OrderQuery q);

    /** {@link #search} enriched with the list extras (total, nodeIds, held, atRisk, riskScore, promiseDate, agentId); {@code atRisk} filters on risk. */
    Page<OrderSummaryView> searchView(OrderQuery q, Boolean atRisk);

    /** One order view across channels (FR-CS-01). */
    OrderView view(String orderId);

    /** Merged timeline only (ledger + workflow + decisions), sorted by time. */
    List<OrderTimelineEntry> timeline(String orderId);

    /** Optimistic update (version check) with the ledger event appended in the same transaction. */
    Order update(String orderId, UnaryOperator<Order> fn, NewEvent event);

    Order addNote(String orderId, Note note);

    /** Re-computes the margin snapshot with the costs sourcing knows (FR-FIN-04, FR-SRC-04); appends {@code order.margin_recomputed}. */
    Order recomputeMargin(String orderId, BigDecimal shippingCost, BigDecimal labourCost, BigDecimal packagingCost);

    /** Records that a below-floor plan was approved through human task {@code taskId} (GR-01, FR-SRC-04); appends {@code order.margin_approved}. */
    Order markBelowFloorApproval(String orderId, String taskId);
}
