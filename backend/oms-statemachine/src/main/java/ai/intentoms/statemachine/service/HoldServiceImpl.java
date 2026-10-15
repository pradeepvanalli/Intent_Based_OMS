package ai.intentoms.statemachine.service;

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
import ai.intentoms.statemachine.HoldService;
import ai.intentoms.statemachine.model.Hold;
import ai.intentoms.statemachine.store.EntityStateRepository;
import ai.intentoms.statemachine.store.HoldRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Hold overlay (FR-SM-07). Holds live in {@code hold}; every placement/release appends {@code hold.placed} /
 * {@code hold.released} to the scope's aggregate so the order timeline shows them. A hold on an order also blocks the
 * order's lines ({@code order_line} ids {@code "<orderId>:<lineId>"}); the same applies to {@code return}/{@code return_line}.
 */
@Service
public class HoldServiceImpl implements HoldService {
    private static final Logger log = LoggerFactory.getLogger(HoldServiceImpl.class);
    private static final Set<String> SCOPES = Set.of("order", "order_line", "return", "return_line", "shipment", "payment", "refund");

    private final HoldRepository holds;
    private final EventStore events;
    private final TimeSource time;
    private final AuthorizationService authz;
    private final Metrics metrics;
    private final TransactionTemplate tx;

    public HoldServiceImpl(HoldRepository holds, EventStore events, TimeSource time, AuthorizationService authz, Metrics metrics, TransactionTemplate tx) {
        this.holds = holds;
        this.events = events;
        this.time = time;
        this.authz = authz;
        this.metrics = metrics;
        this.tx = tx;
    }

    @Override
    @Transactional
    public Hold place(String type, String scopeType, String scopeId, Set<String> blocks, String reasonCode, String note, Duration ttl, Actor actor) {
        if (type == null || type.isBlank()) throw new ValidationException("hold type is required (fraud, payment, address, compliance, agent_verification, manual, ...)");
        if (scopeType == null || !SCOPES.contains(scopeType)) throw new ValidationException("hold scopeType must be one of " + new java.util.TreeSet<>(SCOPES) + ", got '" + scopeType + "'");
        if (scopeId == null || scopeId.isBlank()) throw new ValidationException("hold scopeId is required");
        if (blocks == null || blocks.isEmpty()) throw new ValidationException("hold must block at least one transition trigger or state ('*' blocks all)");
        if (ttl != null && (ttl.isNegative() || ttl.isZero())) throw new ValidationException("hold ttl must be positive");
        Actor who = actor != null ? actor : Actor.parse(authz.actorRef());
        Instant now = time.now();
        Hold h = new Hold(Ids.prefixed("hold"), type, scopeType, scopeId, blocks, reasonCode, note, ttl == null ? null : now.plus(ttl), who.ref(), now, null, null, null);
        holds.insert(h);
        var agg = Aggregates.of(scopeType, scopeId);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("holdId", h.id());
        payload.put("type", type);
        payload.put("scopeType", scopeType);
        payload.put("scopeId", scopeId);
        payload.put("blocks", new ArrayList<>(new java.util.TreeSet<>(blocks)));
        payload.put("reasonCode", reasonCode);
        payload.put("note", note);
        payload.put("expiresAt", h.expiresAt() == null ? null : h.expiresAt().toString());
        events.append(agg.type(), agg.id(), -1, List.of(new NewEvent("hold.placed", payload, who, null, null, Map.of("holdId", h.id()))));
        metrics.count("oms.statemachine.holds", "type", type, "op", "placed");
        return h;
    }

    @Override
    @Transactional
    public Hold release(String holdId, String resolution, Actor actor) {
        Hold h = holds.lock(holdId).orElseThrow(() -> new NotFoundException("Hold " + holdId + " not found"));
        if (h.resolvedAt() != null) throw new ConflictException("Hold " + holdId + " was already released (" + h.resolution() + ")");
        return resolve(h, resolution == null || resolution.isBlank() ? "released" : resolution, actor != null ? actor : Actor.parse(authz.actorRef()));
    }

    private Hold resolve(Hold h, String resolution, Actor who) {
        Instant now = time.now();
        holds.resolve(h.id(), who.ref(), now, resolution);
        var agg = Aggregates.of(h.scopeType(), h.scopeId());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("holdId", h.id());
        payload.put("type", h.type());
        payload.put("scopeType", h.scopeType());
        payload.put("scopeId", h.scopeId());
        payload.put("resolution", resolution);
        payload.put("blocks", new ArrayList<>(new java.util.TreeSet<>(h.blocksTransitions())));
        events.append(agg.type(), agg.id(), -1, List.of(new NewEvent("hold.released", payload, who, null, null, Map.of("holdId", h.id()))));
        metrics.count("oms.statemachine.holds", "type", h.type(), "op", resolution);
        return new Hold(h.id(), h.type(), h.scopeType(), h.scopeId(), h.blocksTransitions(), h.reasonCode(), h.note(), h.expiresAt(), h.placedBy(), h.placedAt(), who.ref(), now, resolution);
    }

    @Override
    public Hold get(String holdId) {
        return holds.find(holdId).orElseThrow(() -> new NotFoundException("Hold " + holdId + " not found"));
    }

    @Override
    public List<Hold> active(String scopeType, String scopeId) { return holds.active(scopeType, scopeId, time.now()); }

    /** Active holds on the scope plus, for line scopes, on the parent aggregate. */
    public List<Hold> activeIncludingParent(String scopeType, String scopeId) {
        List<Hold> out = new ArrayList<>(active(scopeType, scopeId));
        String parentType = EntityStateRepository.headerTypeOf(scopeType);
        String parentId = EntityStateRepository.parentIdOf(scopeType, scopeId);
        if (parentType != null && parentId != null) out.addAll(active(parentType, parentId));
        return out;
    }

    @Override
    public boolean blocks(String scopeType, String scopeId, String transitionTriggerOrState) {
        return !blocking(scopeType, scopeId, List.of(transitionTriggerOrState)).isEmpty();
    }

    @Override
    public List<Hold> blocking(String scopeType, String scopeId, Collection<String> triggersOrStates) {
        List<Hold> out = new ArrayList<>();
        for (Hold h : activeIncludingParent(scopeType, scopeId)) {
            for (String token : triggersOrStates) if (token != null && h.blocks(token)) { out.add(h); break; }
        }
        return out;
    }

    @Override
    public int releaseExpired() {
        Instant now = time.now();
        List<HoldRepository.TenantHold> expired = tx.execute(s -> holds.expired(now, 500));
        int released = 0;
        for (HoldRepository.TenantHold th : expired == null ? List.<HoldRepository.TenantHold>of() : expired) {
            try {
                TenantContext.runAs(th.tenantId(), () -> tx.executeWithoutResult(s -> {
                    Hold h = holds.lock(th.hold().id()).orElse(null);
                    if (h != null && h.resolvedAt() == null) resolve(h, "expired", Actor.system("hold-sweeper"));
                }));
                released++;
            } catch (RuntimeException e) {
                log.warn("Failed to expire hold {} for tenant {}: {}", th.hold().id(), th.tenantId(), e.getMessage());
            }
        }
        return released;
    }
}
