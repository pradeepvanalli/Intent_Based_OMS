package ai.intentoms.orchestration.shipment;

import ai.intentoms.orchestration.integration.CarrierConnector;
import ai.intentoms.order.model.ShipmentSummary;
import ai.intentoms.order.service.ShipmentProvider;
import ai.intentoms.platform.ids.Ids;
import ai.intentoms.platform.ledger.Actor;
import ai.intentoms.platform.time.TimeSource;
import ai.intentoms.statemachine.StateMachine;
import ai.intentoms.statemachine.model.EntityStatus;
import ai.intentoms.statemachine.model.TransitionRequest;
import ai.intentoms.statemachine.model.TransitionResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Shipments (UC-05, FR-WF-05): created by the order workflow when the WMS reports {@code shipped}, moved by carrier scans
 * through the {@code shipment.standard} state model (label_created → in_transit → out_for_delivery → delivered, with the
 * exception loop). Also the order module's {@link ShipmentProvider} so the order view lists shipments (FR-CS-01).
 */
@Service
public class ShipmentService implements ShipmentProvider {
    private static final Logger log = LoggerFactory.getLogger(ShipmentService.class);
    private static final Actor ACTOR = Actor.system("orchestration");
    private final ShipmentRepository repo;
    private final StateMachine machine;
    private final CarrierConnector carrier;
    private final TimeSource time;

    public ShipmentService(ShipmentRepository repo, StateMachine machine, CarrierConnector carrier, TimeSource time) {
        this.repo = repo;
        this.machine = machine;
        this.carrier = carrier;
        this.time = time;
    }

    /**
     * Records a shipment and starts its lifecycle: {@code event.label_created} then {@code event.carrier_scan}
     * ({@code scan_type in_transit}). A missing tracking number is obtained from the {@link CarrierConnector}.
     */
    @Transactional
    public ShipmentRecord create(String orderId, String fulfilmentGroupId, String nodeId, String carrierName, String service, String tracking,
                                 List<ShipmentRecord.Line> lines, Integer transitDays, Actor actor) {
        Instant now = time.now();
        Instant eta = transitDays == null ? null : now.plus(Math.max(0, transitDays), ChronoUnit.DAYS);
        ShipmentRecord draft = new ShipmentRecord(Ids.prefixed("shp"), orderId, fulfilmentGroupId, nodeId, carrierName == null ? "MOCK" : carrierName, service, tracking, "created", lines, now, null, eta);
        if (tracking == null || tracking.isBlank()) draft = withTracking(draft, carrier.createLabel(draft));
        ShipmentRecord saved = repo.insert(draft);
        Map<String, Object> attrs = new LinkedHashMap<>();
        attrs.put("orderId", orderId);
        attrs.put("nodeId", nodeId);
        attrs.put("carrier", saved.carrier());
        attrs.put("tracking", saved.tracking());
        machine.initialize(ShipmentRecord.ENTITY, saved.id(), ShipmentRecord.MODEL, null, 1, attrs, actor == null ? ACTOR : actor);
        fire(saved.id(), "event.label_created", Map.of(), actor);
        fire(saved.id(), "event.carrier_scan", Map.of("event", Map.of("scan_type", "in_transit")), actor);
        ShipmentRecord out = repo.get(saved.id());
        log.info("Shipment {} created for order {} from {} ({} {}), tracking {}", out.id(), orderId, nodeId, out.carrier(), service, out.tracking());
        return out;
    }

    /**
     * Applies a carrier scan ({@code in_transit | out_for_delivery | delivered | exception | returned_to_sender}) to the
     * shipment's state machine and mirrors the dominant state onto the row. Unknown or non-applicable scans are recorded
     * as not applied and never throw.
     */
    @Transactional
    public TransitionResult applyCarrierEvent(String shipmentId, String type, Actor actor) {
        String trigger = switch (type) {
            case "in_transit" -> "event.carrier_scan";
            case "out_for_delivery" -> "event.out_for_delivery";
            case "delivered" -> "event.delivered";
            case "exception" -> "event.carrier_exception";
            case "returned_to_sender" -> "event.returned_to_sender";
            default -> null;
        };
        if (trigger == null) return TransitionResult.blocked(List.of("unknown carrier event " + type));
        Map<String, Object> vars = "event.carrier_scan".equals(trigger) ? Map.of("event", Map.of("scan_type", "in_transit")) : Map.of();
        return fire(shipmentId, trigger, vars, actor);
    }

    /** Marks the shipment delivered (idempotent). */
    @Transactional
    public ShipmentRecord markDelivered(String shipmentId, Actor actor) {
        ShipmentRecord s = repo.get(shipmentId);
        if (!"delivered".equals(s.status())) fire(shipmentId, "event.delivered", Map.of(), actor);
        return repo.get(shipmentId);
    }

    private TransitionResult fire(String shipmentId, String trigger, Map<String, Object> vars, Actor actor) {
        TransitionResult r = machine.fire(new TransitionRequest(ShipmentRecord.ENTITY, shipmentId, trigger, null, null, vars, actor == null ? ACTOR : actor, shipmentId, null));
        EntityStatus st = machine.status(ShipmentRecord.ENTITY, shipmentId);
        repo.updateStatus(shipmentId, st.state(), "delivered".equals(st.state()) ? time.now() : null);
        return r;
    }

    public ShipmentRecord get(String id) { return repo.get(id); }
    public Optional<ShipmentRecord> find(String id) { return repo.find(id); }
    public List<ShipmentRecord> forOrder(String orderId) { return repo.forOrder(orderId); }
    public List<ShipmentRecord> byTracking(String tracking) { return tracking == null || tracking.isBlank() ? List.of() : repo.byTracking(tracking); }

    @Override
    public List<ShipmentSummary> shipmentsFor(String orderId) { return repo.forOrder(orderId).stream().map(ShipmentRecord::summary).toList(); }

    private static ShipmentRecord withTracking(ShipmentRecord s, String tracking) {
        return new ShipmentRecord(s.id(), s.orderId(), s.fulfilmentGroupId(), s.nodeId(), s.carrier(), s.service(), tracking, s.status(), s.lines(), s.shippedAt(), s.deliveredAt(), s.eta());
    }
}
