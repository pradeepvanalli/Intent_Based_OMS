package ai.intentoms.inventory.service;

import ai.intentoms.inventory.config.InventoryConfigKeys;
import ai.intentoms.inventory.model.Reservation;
import ai.intentoms.inventory.model.ReservationLine;
import ai.intentoms.inventory.store.InventoryPositionStore;
import ai.intentoms.inventory.store.InventoryPositionStore.AtpRow;
import ai.intentoms.inventory.store.ReservationStore;
import ai.intentoms.inventory.store.ReservationStore.Row;
import ai.intentoms.platform.config.TenantConfigStore;
import ai.intentoms.platform.error.ConflictException;
import ai.intentoms.platform.error.NotFoundException;
import ai.intentoms.platform.error.ValidationException;
import ai.intentoms.platform.ids.Ids;
import ai.intentoms.platform.ledger.Actor;
import ai.intentoms.platform.ledger.EventStore;
import ai.intentoms.platform.ledger.NewEvent;
import ai.intentoms.platform.observability.Metrics;
import ai.intentoms.platform.security.AuthorizationService;
import ai.intentoms.platform.tenant.TenantContext;
import ai.intentoms.platform.time.TimeSource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Default {@link ReservationService} (FR-INV-02, NFR-06). Consistency model: a reservation is only ever inserted while the
 * transaction holds {@code select ... for update} on the (sku, node) on-hand row, and ATP is recomputed under that lock,
 * so reservations against one position are strictly serialised. Releases, expiries and conversions do not take the lock
 * because they only ever increase or preserve availability.
 */
@Service
public class DefaultReservationService implements ReservationService {
    private static final String INSUFFICIENT = "insufficient_atp";

    private final ReservationStore reservations;
    private final InventoryPositionStore positions;
    private final EventStore events;
    private final TenantConfigStore config;
    private final NetworkAtpCache cache;
    private final Metrics metrics;
    private final TimeSource time;
    private final AuthorizationService authz;

    public DefaultReservationService(ReservationStore reservations, InventoryPositionStore positions, EventStore events, TenantConfigStore config,
                                     NetworkAtpCache cache, Metrics metrics, TimeSource time, AuthorizationService authz) {
        this.reservations = reservations;
        this.positions = positions;
        this.events = events;
        this.config = config;
        this.cache = cache;
        this.metrics = metrics;
        this.time = time;
        this.authz = authz;
    }

    @Override
    @Transactional
    public Reservation reserve(String sku, String nodeId, int qty, String source, String refId, Duration ttl) {
        return reserveAll(List.of(new ReservationLine(sku, nodeId, qty)), source, refId, ttl).get(0);
    }

    @Override
    @Transactional
    public List<Reservation> reserveAll(List<ReservationLine> lines, String source, String refId, Duration ttl) {
        if (lines == null || lines.isEmpty()) throw new ValidationException("At least one reservation line is required");
        if (source == null || source.isBlank()) throw new ValidationException("source is required");
        if (refId == null || refId.isBlank()) throw new ValidationException("refId is required");
        for (ReservationLine l : lines) l.validate();
        String tenant = TenantContext.require();
        return metrics.time("oms.inventory.reserve", () -> {
            Instant now = time.now();
            Instant expiresAt = Reservation.SOURCE_ORDER.equals(source) || ttl == null || ttl.isZero() || ttl.isNegative() ? null : now.plus(ttl);
            boolean includeInbound = config.getBoolean(InventoryConfigKeys.INCLUDE_INBOUND, false);

            // Demand per (sku, node), locked in deterministic order so concurrent reserveAll calls cannot deadlock.
            Map<String, ReservationLine> demand = new LinkedHashMap<>();
            for (ReservationLine l : lines.stream().sorted(ReservationLine.LOCK_ORDER).toList())
                demand.merge(l.key(), l, (a, b) -> new ReservationLine(a.sku(), a.nodeId(), a.qty() + b.qty()));

            List<Map<String, Object>> shortfalls = new ArrayList<>();
            for (ReservationLine d : demand.values()) {
                int available = positions.lockOnHand(tenant, d.sku(), d.nodeId()).isEmpty() ? 0 : availableUnderLock(tenant, d, includeInbound);
                if (d.qty() > available) {
                    shortfalls.add(Map.of("sku", d.sku(), "nodeId", d.nodeId(), "available", available, "requested", d.qty()));
                }
            }
            if (!shortfalls.isEmpty()) {
                metrics.count("oms.inventory.insufficient", "source", source);
                Map<String, Object> details = new LinkedHashMap<>(shortfalls.get(0));
                if (shortfalls.size() > 1) details.put("lines", shortfalls);
                throw new ConflictException(INSUFFICIENT, details);
            }

            Actor actor = Actor.parse(authz.actorRef());
            List<Reservation> out = new ArrayList<>(lines.size());
            Set<String> skus = new LinkedHashSet<>();
            for (ReservationLine l : lines) {
                Reservation r = new Reservation(Ids.prefixed("rsv"), l.sku(), l.nodeId(), l.qty(), source, refId, expiresAt, Reservation.ACTIVE);
                String orderId = Reservation.SOURCE_ORDER.equals(source) ? refId : null;
                reservations.insert(tenant, r, orderId, now);
                events.append(InventoryEvents.RESERVATION, r.id(), new NewEvent(InventoryEvents.RESERVATION_CREATED, payload(r, orderId, Map.of()), actor));
                out.add(r);
                skus.add(l.sku());
            }
            cache.invalidateAll(tenant, skus);
            metrics.count("oms.inventory.reserved", "source", source);
            return out;
        }, "lines", lines.size() == 1 ? "single" : "multi");
    }

    /** ATP of one (sku, node) read while the on-hand row is locked by this transaction. */
    private int availableUnderLock(String tenant, ReservationLine d, boolean includeInbound) {
        List<AtpRow> rows = positions.atpRows(tenant, d.sku(), d.nodeId());
        return rows.isEmpty() ? 0 : DefaultInventoryService.available(rows.get(0), includeInbound);
    }

    @Override
    @Transactional
    public Reservation convert(String reservationId, String orderId) {
        if (orderId == null || orderId.isBlank()) throw new ValidationException("orderId is required");
        String tenant = TenantContext.require();
        Row row = lock(tenant, reservationId);
        Reservation r = row.reservation();
        if (!r.active()) throw notActive(r, "convert");
        if (Reservation.SOURCE_ORDER.equals(r.source()) && orderId.equals(row.orderId())) return r;
        if (row.orderId() != null && !orderId.equals(row.orderId()))
            throw new ConflictException("reservation_already_converted", Map.of("reservationId", r.id(), "orderId", row.orderId()));
        Instant now = time.now();
        reservations.convert(tenant, r.id(), orderId, now);
        Reservation converted = new Reservation(r.id(), r.sku(), r.nodeId(), r.qty(), Reservation.SOURCE_ORDER, r.refId(), null, Reservation.ACTIVE);
        events.append(InventoryEvents.RESERVATION, r.id(), new NewEvent(InventoryEvents.RESERVATION_CONVERTED, payload(converted, orderId, Map.of()), actor()));
        metrics.count("oms.inventory.converted");
        return converted;
    }

    @Override
    @Transactional
    public Reservation release(String reservationId, String reason) {
        String tenant = TenantContext.require();
        Row row = lock(tenant, reservationId);
        Reservation r = row.reservation();
        if (Reservation.RELEASED.equals(r.status()) || Reservation.EXPIRED.equals(r.status())) return r;
        if (Reservation.CONSUMED.equals(r.status())) throw notActive(r, "release");
        Instant now = time.now();
        reservations.setStatus(tenant, r.id(), Reservation.RELEASED, now);
        Reservation released = withStatus(r, Reservation.RELEASED);
        events.append(InventoryEvents.RESERVATION, r.id(), new NewEvent(InventoryEvents.RESERVATION_RELEASED,
            payload(released, row.orderId(), Map.of("reason", reason == null ? "" : reason)), actor()));
        cache.invalidate(tenant, r.sku());
        metrics.count("oms.inventory.released");
        return released;
    }

    @Override
    @Transactional
    public Reservation consume(String reservationId) {
        String tenant = TenantContext.require();
        Row row = lock(tenant, reservationId);
        Reservation r = row.reservation();
        if (Reservation.CONSUMED.equals(r.status())) return r;
        if (!r.active()) throw notActive(r, "consume");
        Instant now = time.now();
        positions.lockOnHand(tenant, r.sku(), r.nodeId());
        int onHandAfter = positions.addOnHand(tenant, r.sku(), r.nodeId(), -r.qty(), now);
        reservations.setStatus(tenant, r.id(), Reservation.CONSUMED, now);
        Reservation consumed = withStatus(r, Reservation.CONSUMED);
        events.append(InventoryEvents.RESERVATION, r.id(), new NewEvent(InventoryEvents.RESERVATION_CONSUMED,
            payload(consumed, row.orderId(), Map.of("onHandAfter", onHandAfter)), actor()));
        cache.invalidate(tenant, r.sku());
        metrics.count("oms.inventory.consumed");
        return consumed;
    }

    @Override
    public Reservation get(String reservationId) {
        return reservations.find(TenantContext.require(), reservationId).map(Row::reservation)
            .orElseThrow(() -> new NotFoundException("Reservation " + reservationId + " not found", Map.of("reservationId", reservationId)));
    }

    @Override
    public List<Reservation> forRef(String refId) {
        return reservations.forRef(TenantContext.require(), refId).stream().map(Row::reservation).toList();
    }

    @Override
    @Transactional
    public int expireDue() {
        String tenant = TenantContext.require();
        List<Row> expired = reservations.expireDue(tenant, time.now());
        if (expired.isEmpty()) return 0;
        Actor actor = Actor.system("reservation-expiry");
        Set<String> skus = new LinkedHashSet<>();
        for (Row row : expired) {
            Reservation r = row.reservation();
            events.append(InventoryEvents.RESERVATION, r.id(), new NewEvent(InventoryEvents.RESERVATION_EXPIRED, payload(r, row.orderId(), Map.of()), actor));
            skus.add(r.sku());
        }
        cache.invalidateAll(tenant, skus);
        metrics.count("oms.inventory.expired", expired.size());
        return expired.size();
    }

    private Row lock(String tenant, String id) {
        return reservations.lock(tenant, id).orElseThrow(() -> new NotFoundException("Reservation " + id + " not found", Map.of("reservationId", id)));
    }

    private static ConflictException notActive(Reservation r, String op) {
        return new ConflictException("reservation_not_active", Map.of("reservationId", r.id(), "status", r.status(), "operation", op));
    }

    private static Reservation withStatus(Reservation r, String status) {
        return new Reservation(r.id(), r.sku(), r.nodeId(), r.qty(), r.source(), r.refId(), r.expiresAt(), status);
    }

    private Actor actor() { return Actor.parse(authz.actorRef()); }

    /** Event payload: always sku/node/qty/ref plus whatever the lifecycle step adds. */
    static Map<String, Object> payload(Reservation r, String orderId, Map<String, Object> extra) {
        Map<String, Object> p = new HashMap<>();
        p.put("reservationId", r.id());
        p.put("sku", r.sku());
        p.put("nodeId", r.nodeId());
        p.put("qty", r.qty());
        p.put("source", r.source());
        p.put("refId", r.refId());
        p.put("status", r.status());
        if (orderId != null) p.put("orderId", orderId);
        if (r.expiresAt() != null) p.put("expiresAt", r.expiresAt().toString());
        p.putAll(extra);
        return p;
    }
}
