package ai.intentoms.statemachine.service;

import ai.intentoms.platform.cel.CelEngine;
import ai.intentoms.platform.config.TenantConfigStore;
import ai.intentoms.platform.error.ConflictException;
import ai.intentoms.platform.error.InvalidTransitionException;
import ai.intentoms.platform.error.NotFoundException;
import ai.intentoms.platform.error.ValidationException;
import ai.intentoms.platform.ledger.Actor;
import ai.intentoms.platform.ledger.Event;
import ai.intentoms.platform.ledger.EventStore;
import ai.intentoms.platform.ledger.NewEvent;
import ai.intentoms.platform.observability.Metrics;
import ai.intentoms.platform.security.AuthorizationService;
import ai.intentoms.platform.tenant.TenantContext;
import ai.intentoms.platform.time.TimeSource;
import ai.intentoms.statemachine.StateMachine;
import ai.intentoms.statemachine.StateModelRegistry;
import ai.intentoms.statemachine.TransitionApplied;
import ai.intentoms.statemachine.model.EntityStatus;
import ai.intentoms.statemachine.model.Hold;
import ai.intentoms.statemachine.model.QuantityBucket;
import ai.intentoms.statemachine.model.StateDef;
import ai.intentoms.statemachine.model.StateModel;
import ai.intentoms.statemachine.model.TransitionDef;
import ai.intentoms.statemachine.model.TransitionRequest;
import ai.intentoms.statemachine.model.TransitionResult;
import ai.intentoms.statemachine.store.EntityStateRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * The statechart engine (FR-SM-01..03, FR-SM-05, FR-SM-07, FR-SM-08, FR-SM-11).
 * <p>
 * {@link #fire} runs in one transaction: lock the entity row → match transitions from the current state(s) by trigger →
 * reject candidates blocked by holds, missing permission or failing guards (first candidate that passes wins, declaration
 * order) → move quantity buckets → recompute the dominant state → append one {@code <aggregate>.transitioned} ledger event
 * per move (payload keys: entityType, entityId, from, to, qty, trigger, transitionId, guards, actions, modelId, modelVersion,
 * before, after, permission) → schedule/cancel SLA timers → roll the header up when a *_line entity moved → publish
 * {@link TransitionApplied}. Lock order is always entity row → header row → aggregate_version, so concurrent line/header
 * transitions cannot deadlock.
 */
@Service
public class StateMachineImpl implements StateMachine {
    private static final Logger log = LoggerFactory.getLogger(StateMachineImpl.class);
    /** Trigger recorded on header transitions derived from line quantities (FR-SM-05). */
    public static final String ROLLUP_TRIGGER = "rollup";

    private final StateModelRegistry registry;
    private final EntityStateRepository entities;
    private final HoldServiceImpl holds;
    private final SlaTimerService slaTimers;
    private final RollupEvaluator rollups;
    private final CelEngine cel;
    private final TenantConfigStore tenantConfig;
    private final AuthorizationService authz;
    private final EventStore events;
    private final ApplicationEventPublisher spring;
    private final Metrics metrics;
    private final TimeSource time;

    public StateMachineImpl(StateModelRegistry registry, EntityStateRepository entities, HoldServiceImpl holds, SlaTimerService slaTimers, RollupEvaluator rollups,
                            CelEngine cel, TenantConfigStore tenantConfig, AuthorizationService authz, EventStore events, ApplicationEventPublisher spring,
                            Metrics metrics, TimeSource time) {
        this.registry = registry;
        this.entities = entities;
        this.holds = holds;
        this.slaTimers = slaTimers;
        this.rollups = rollups;
        this.cel = cel;
        this.tenantConfig = tenantConfig;
        this.authz = authz;
        this.events = events;
        this.spring = spring;
        this.metrics = metrics;
        this.time = time;
    }

    // ---------------------------------------------------------------- initialize

    @Override
    @Transactional
    public EntityStatus initialize(String entityType, String entityId, String modelId, Integer version, int qty, Map<String, Object> attributes, Actor actor) {
        if (entityType == null || entityType.isBlank() || entityId == null || entityId.isBlank()) throw new ValidationException("entityType and entityId are required");
        StateModel model = version == null ? registry.latest(modelId) : registry.get(modelId, version);
        if (model.entity() != null && !model.entity().isBlank() && !model.entity().equals(entityType))
            throw new ValidationException("State model " + model.id() + " is defined for entity '" + model.entity() + "', not '" + entityType + "'");
        if (entities.find(entityType, entityId).isPresent()) throw new ConflictException("Entity " + entityType + "/" + entityId + " is already initialised");
        int units = model.quantityLevel() ? qty : 1;
        if (units < 1) throw new ValidationException("qty must be at least 1 for quantity-level model " + model.id());
        StateDef init = model.initial();
        Instant now = time.now();
        Map<String, Object> attrs = attributes == null ? Map.of() : attributes;
        entities.insert(entityType, entityId, model.id(), model.version(), init.id(), attrs, now);
        entities.putBucket(entityType, entityId, init.id(), units, now);
        slaTimers.onEntered(entityType, entityId, init);
        Actor who = actor != null ? actor : Actor.parse(authz.actorRef());
        var agg = Aggregates.of(entityType, entityId);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("entityType", entityType);
        payload.put("entityId", entityId);
        payload.put("modelId", model.id());
        payload.put("modelVersion", model.version());
        payload.put("state", init.id());
        payload.put("qty", units);
        events.append(agg.type(), agg.id(), -1, List.of(new NewEvent(agg.type() + ".state_initialized", payload, who, null, null, meta(entityType, entityId))));
        metrics.count("oms.statemachine.initialized", "entity_type", entityType);
        return new EntityStatus(entityType, entityId, model.id(), model.version(), init.id(), List.of(new QuantityBucket(init.id(), units, now)), attrs);
    }

    // ---------------------------------------------------------------- fire

    @Override
    @Transactional
    public TransitionResult fire(TransitionRequest req) {
        return metrics.time("oms.statemachine.transition", () -> doFire(req), "entity_type", String.valueOf(req.entityType()), "trigger", String.valueOf(req.trigger()));
    }

    private record Move(String from, TransitionDef transition, int qty) {}

    private TransitionResult doFire(TransitionRequest req) {
        if (req.trigger() == null || req.trigger().isBlank()) throw new ValidationException("trigger is required");
        EntityStateRepository.Row row = entities.lock(req.entityType(), req.entityId())
            .orElseThrow(() -> new NotFoundException("Entity " + req.entityType() + "/" + req.entityId() + " has no state"));
        StateModel model = registry.get(row.modelId(), row.modelVersion());
        Map<String, Integer> before = QuantityBuckets.toMap(entities.buckets(req.entityType(), req.entityId()));
        Actor actor = req.actor() != null ? req.actor() : Actor.parse(authz.actorRef());
        List<String> blocked = new ArrayList<>();

        // 1. Source states
        List<String> sources = new ArrayList<>();
        if (!model.quantityLevel() || req.fromState() == null) {
            for (StateDef s : model.states()) {
                if (before.getOrDefault(s.id(), 0) > 0 && !model.transitionsFrom(s.id(), req.trigger()).isEmpty()) sources.add(s.id());
            }
            if (sources.isEmpty()) {
                blocked.add("no transition from " + new TreeSet<>(before.keySet()) + " on trigger '" + req.trigger() + "'");
                return finishBlocked(req, blocked);
            }
            if (model.quantityLevel() && req.qty() != null && sources.size() > 1) {
                blocked.add("ambiguous source: quantity sits in " + sources + "; specify fromState when passing qty");
                return finishBlocked(req, blocked);
            }
        } else {
            if (before.getOrDefault(req.fromState(), 0) <= 0) {
                blocked.add("no quantity in state '" + req.fromState() + "' (current: " + before + ")");
                return finishBlocked(req, blocked);
            }
            if (model.transitionsFrom(req.fromState(), req.trigger()).isEmpty()) {
                blocked.add("no transition from '" + req.fromState() + "' on trigger '" + req.trigger() + "'");
                return finishBlocked(req, blocked);
            }
            sources.add(req.fromState());
        }

        // 2. Candidate selection per source: holds → permission → guards; first passing candidate wins.
        Set<String> tokens = new LinkedHashSet<>();
        tokens.add("*");
        tokens.add(req.trigger());
        for (String s : sources) for (TransitionDef t : model.transitionsFrom(s, req.trigger())) tokens.add(t.to());
        List<Hold> relevantHolds = holds.blocking(req.entityType(), req.entityId(), tokens);
        List<Hold> allHolds = holds.activeIncludingParent(req.entityType(), req.entityId());
        Map<String, Object> tenantMap = req.vars().containsKey("tenant") ? null : tenantConfig.celTenantMap();
        List<Move> moves = new ArrayList<>();
        for (String source : sources) {
            int available = before.get(source);
            int qty = model.quantityLevel() && req.qty() != null ? req.qty() : available;
            if (qty <= 0) { blocked.add("qty must be positive, got " + qty); continue; }
            if (qty > available) { blocked.add("insufficient quantity in state '" + source + "': have " + available + ", requested " + qty); continue; }
            TransitionDef chosen = null;
            for (TransitionDef t : model.transitionsFrom(source, req.trigger())) {
                List<String> reasons = evaluate(t, req, actor, model, before, source, qty, relevantHolds, allHolds, tenantMap);
                if (reasons.isEmpty()) { chosen = t; break; }
                blocked.addAll(reasons);
            }
            if (chosen != null) moves.add(new Move(source, chosen, qty));
        }
        if (moves.isEmpty()) return finishBlocked(req, blocked);

        // 3. Lock the header before touching the ledger (lock order: entity → header → aggregate_version).
        String headerType = EntityStateRepository.headerTypeOf(req.entityType());
        String parentId = row.parentId();
        EntityStateRepository.Row header = headerType != null && parentId != null ? entities.lock(headerType, parentId).orElse(null) : null;

        // 4. Apply moves
        Instant now = time.now();
        Map<String, Integer> after = before;
        List<NewEvent> newEvents = new ArrayList<>(moves.size());
        var agg = Aggregates.of(req.entityType(), req.entityId());
        for (Move m : moves) {
            Map<String, Integer> prev = after;
            after = QuantityBuckets.move(after, m.from(), m.transition().to(), m.qty());
            entities.putBucket(req.entityType(), req.entityId(), m.from(), after.getOrDefault(m.from(), 0), now);
            if (!m.from().equals(m.transition().to())) entities.putBucket(req.entityType(), req.entityId(), m.transition().to(), after.getOrDefault(m.transition().to(), 0), now);
            if (m.from().equals(m.transition().to())) {            // self-transition restarts the state's SLA clock
                slaTimers.onLeft(req.entityType(), req.entityId(), m.from());
                model.state(m.from()).ifPresent(def -> slaTimers.onEntered(req.entityType(), req.entityId(), def));
            } else {
                if (prev.getOrDefault(m.transition().to(), 0) == 0) model.state(m.transition().to()).ifPresent(def -> slaTimers.onEntered(req.entityType(), req.entityId(), def));
                if (after.getOrDefault(m.from(), 0) == 0) slaTimers.onLeft(req.entityType(), req.entityId(), m.from());
            }
            newEvents.add(new NewEvent(agg.type() + ".transitioned", transitionPayload(req.entityType(), req.entityId(), m.from(), m.transition().to(), m.qty(), m.transition(), model, prev, after),
                actor, req.causationId(), req.correlationId(), meta(req.entityType(), req.entityId())));
        }
        String dominant = QuantityBuckets.dominant(model, after);
        if (!dominant.equals(row.state())) entities.updateState(req.entityType(), req.entityId(), dominant, now);
        List<Event> written = events.append(agg.type(), agg.id(), -1, newEvents);

        // 5. Header roll-up (FR-SM-05)
        if (header != null) rollupHeader(header, req.entityType(), model, actor, written.get(0).id(), req.correlationId());

        // 6. Notify in-process listeners
        String tenant = TenantContext.require();
        for (int i = 0; i < moves.size(); i++) {
            Move m = moves.get(i);
            spring.publishEvent(new TransitionApplied(tenant, req.entityType(), req.entityId(), m.from(), m.transition().to(), m.qty(), m.transition().actions(),
                req.trigger(), written.get(i).id(), written.get(i).correlationId()));
        }
        metrics.count("oms.statemachine.transitions", "entity_type", req.entityType(), "trigger", req.trigger(), "applied", "true");

        List<String> actions = new ArrayList<>();
        LinkedHashSet<String> froms = new LinkedHashSet<>(), tos = new LinkedHashSet<>();
        int total = 0;
        for (Move m : moves) {
            froms.add(m.from());
            tos.add(m.transition().to());
            total += m.qty();
            for (String a : m.transition().actions()) if (!actions.contains(a)) actions.add(a);
        }
        return new TransitionResult(true, String.join(",", froms), String.join(",", tos), total, actions, written.get(0).id(), blocked);
    }

    private TransitionResult finishBlocked(TransitionRequest req, List<String> blocked) {
        metrics.count("oms.statemachine.transitions", "entity_type", req.entityType(), "trigger", req.trigger(), "applied", "false");
        if (req.strict()) throw new InvalidTransitionException("Transition '" + req.trigger() + "' not applicable to " + req.entityType() + "/" + req.entityId(),
            Map.of("entityType", req.entityType(), "entityId", req.entityId(), "trigger", req.trigger(), "blockedBy", blocked));
        return TransitionResult.blocked(blocked);
    }

    /** Returns the reasons the candidate cannot fire: hold ids, "permission:<p>", failing guard texts. Empty = fireable. */
    private List<String> evaluate(TransitionDef t, TransitionRequest req, Actor actor, StateModel model, Map<String, Integer> buckets, String source, int qty,
                                  List<Hold> relevantHolds, List<Hold> allHolds, Map<String, Object> tenantMap) {
        List<String> reasons = new ArrayList<>();
        for (Hold h : relevantHolds) if (h.blocks(req.trigger()) || h.blocks(t.to())) reasons.add(h.id());
        model.state(t.to()).ifPresent(target -> {
            for (Hold h : allHolds) if (target.blocksIfHeld().contains(h.type()) && !reasons.contains(h.id())) reasons.add(h.id());
        });
        if (!reasons.isEmpty()) return reasons;
        if (t.isApi() && t.permission() != null && requiresPermissionCheck(actor) && !authz.has(t.permission())) {
            reasons.add("permission:" + t.permission());
            return reasons;
        }
        if (t.guards().isEmpty()) return reasons;
        Map<String, Object> vars = guardVars(req, model, buckets, source, t, qty, allHolds, tenantMap);
        for (String g : t.guards()) {
            try {
                if (!cel.evalBool(g, vars)) reasons.add(g);
            } catch (CelEngine.CelException e) {
                reasons.add(g + " [error: " + e.getMessage() + "]");
            }
        }
        return reasons;
    }

    private static boolean requiresPermissionCheck(Actor actor) {
        return actor != null && ("user".equals(actor.type()) || "agent".equals(actor.type()));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> guardVars(TransitionRequest req, StateModel model, Map<String, Integer> buckets, String source, TransitionDef t, int qty,
                                          List<Hold> activeHolds, Map<String, Object> tenantMap) {
        Map<String, Object> vars = new HashMap<>(req.vars());
        if (tenantMap != null) vars.put("tenant", tenantMap);
        vars.put("state", QuantityBuckets.celStateMap(model, buckets));
        vars.putIfAbsent("now", time.now());
        List<Object> holdList = new ArrayList<>(activeHolds.size());
        for (Hold h : activeHolds) {
            Map<String, Object> hm = new LinkedHashMap<>();
            hm.put("id", h.id());
            hm.put("type", h.type());
            hm.put("blocks", new ArrayList<>(h.blocksTransitions()));
            hm.put("reason_code", h.reasonCode() == null ? "" : h.reasonCode());
            hm.put("scope_type", h.scopeType());
            holdList.add(hm);
        }
        Map<String, Object> ctx = new LinkedHashMap<>();
        if (vars.get("ctx") instanceof Map<?, ?> given) ctx.putAll((Map<String, Object>) given);
        ctx.put("entity_type", req.entityType());
        ctx.put("entity_id", req.entityId());
        ctx.put("trigger", req.trigger());
        ctx.put("transition", t.id());
        ctx.put("from", source);
        ctx.put("to", t.to());
        ctx.put("qty", (long) qty);
        ctx.put("total", (long) QuantityBuckets.total(buckets));
        ctx.put("model_id", model.id());
        ctx.put("model_version", (long) model.version());
        ctx.put("holds", holdList);
        vars.put("ctx", ctx);
        var agg = Aggregates.of(req.entityType(), req.entityId());
        if ("order".equals(agg.type()) || "return".equals(agg.type())) {
            Map<String, Object> root = new LinkedHashMap<>();
            if (vars.get(agg.type()) instanceof Map<?, ?> given) root.putAll((Map<String, Object>) given);
            root.putIfAbsent("id", agg.id());
            root.putIfAbsent("holds", holdList);
            vars.put(agg.type(), root);
        }
        return vars;
    }

    private static Map<String, Object> transitionPayload(String entityType, String entityId, String from, String to, int qty, TransitionDef t, StateModel model,
                                                         Map<String, Integer> before, Map<String, Integer> after) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("entityType", entityType);
        p.put("entityId", entityId);
        p.put("from", from);
        p.put("to", to);
        p.put("qty", qty);
        p.put("trigger", t.trigger());
        p.put("transitionId", t.id());
        p.put("guards", t.guards());
        p.put("actions", t.actions());
        p.put("modelId", model.id());
        p.put("modelVersion", model.version());
        p.put("before", new LinkedHashMap<>(before));
        p.put("after", new LinkedHashMap<>(after));
        if (t.permission() != null) p.put("permission", t.permission());
        return p;
    }

    private static Map<String, Object> meta(String entityType, String entityId) {
        return Map.of("entityType", entityType, "entityId", entityId);
    }

    // ---------------------------------------------------------------- roll-up

    /** Recompute the header state from all its lines and, when it changes, move the header with trigger {@value #ROLLUP_TRIGGER}. */
    private void rollupHeader(EntityStateRepository.Row header, String lineType, StateModel lineModel, Actor actor, String causationId, String correlationId) {
        StateModel headerModel = registry.get(header.modelId(), header.modelVersion());
        if (headerModel.rollup().isEmpty()) return;
        Map<String, Integer> childQty = entities.childQtyByState(lineType, header.entityId());
        List<String> zeroFill = lineModel.states().stream().map(StateDef::id).toList();
        List<String> childTerminal = lineModel.states().stream().filter(StateDef::terminal).map(StateDef::id).toList();
        String derived = rollups.evaluate(headerModel, childQty, zeroFill, childTerminal);
        if (derived == null || derived.equals(header.state()) || !headerModel.hasState(derived)) {
            if (derived != null && !headerModel.hasState(derived)) log.warn("Roll-up for {} {} derived '{}' which {} v{} does not declare; header left at '{}'",
                header.entityType(), header.entityId(), derived, headerModel.id(), headerModel.version(), header.state());
            return;
        }
        Map<String, Integer> before = QuantityBuckets.toMap(entities.buckets(header.entityType(), header.entityId()));
        Instant now = time.now();
        Map<String, Integer> after = new LinkedHashMap<>();
        int total = Math.max(1, QuantityBuckets.total(before));
        after.put(derived, total);
        for (String s : before.keySet()) if (!s.equals(derived)) { entities.putBucket(header.entityType(), header.entityId(), s, 0, now); slaTimers.onLeft(header.entityType(), header.entityId(), s); }
        entities.putBucket(header.entityType(), header.entityId(), derived, total, now);
        headerModel.state(derived).ifPresent(def -> slaTimers.onEntered(header.entityType(), header.entityId(), def));
        entities.updateState(header.entityType(), header.entityId(), derived, now);
        TransitionDef synthetic = new TransitionDef(header.state() + "->" + derived + "#" + ROLLUP_TRIGGER, List.of(header.state()), derived, ROLLUP_TRIGGER, List.of(), List.of(), null, Map.of());
        var agg = Aggregates.of(header.entityType(), header.entityId());
        Map<String, Object> payload = transitionPayload(header.entityType(), header.entityId(), header.state(), derived, total, synthetic, headerModel, before, after);
        payload.put("childStateQty", childQty);
        List<Event> written = events.append(agg.type(), agg.id(), -1, List.of(new NewEvent(agg.type() + ".transitioned", payload, actor, causationId, correlationId, meta(header.entityType(), header.entityId()))));
        spring.publishEvent(new TransitionApplied(TenantContext.require(), header.entityType(), header.entityId(), header.state(), derived, total, List.of(), ROLLUP_TRIGGER,
            written.get(0).id(), written.get(0).correlationId()));
        metrics.count("oms.statemachine.rollups", "entity_type", header.entityType());
    }

    @Override
    public String rollup(String modelId, int version, Map<String, Integer> childStateQty) {
        StateModel parent = registry.get(modelId, version);
        return rollups.evaluate(parent, childStateQty == null ? Map.of() : childStateQty);
    }

    // ---------------------------------------------------------------- queries

    @Override
    public EntityStatus status(String entityType, String entityId) {
        EntityStateRepository.Row row = entities.find(entityType, entityId).orElseThrow(() -> new NotFoundException("Entity " + entityType + "/" + entityId + " has no state"));
        return toStatus(row, entities.buckets(entityType, entityId));
    }

    @Override
    public List<EntityStatus> statuses(String entityType, Collection<String> entityIds) {
        List<EntityStateRepository.Row> rows = entities.findAll(entityType, entityIds);
        Map<String, List<QuantityBucket>> buckets = entities.bucketsFor(entityType, entityIds);
        List<EntityStatus> out = new ArrayList<>(rows.size());
        for (EntityStateRepository.Row r : rows) out.add(toStatus(r, buckets.getOrDefault(r.entityId(), List.of())));
        return out;
    }

    private static EntityStatus toStatus(EntityStateRepository.Row row, List<QuantityBucket> buckets) {
        return new EntityStatus(row.entityType(), row.entityId(), row.modelId(), row.modelVersion(), row.state(), buckets, row.attributes());
    }

    @Override
    public List<TransitionDef> available(String entityType, String entityId, Map<String, Object> vars) {
        EntityStateRepository.Row row = entities.find(entityType, entityId).orElseThrow(() -> new NotFoundException("Entity " + entityType + "/" + entityId + " has no state"));
        StateModel model = registry.get(row.modelId(), row.modelVersion());
        Map<String, Integer> buckets = QuantityBuckets.toMap(entities.buckets(entityType, entityId));
        Actor actor = Actor.parse(authz.actorRef());
        List<Hold> allHolds = holds.activeIncludingParent(entityType, entityId);
        Map<String, Object> safeVars = vars == null ? Map.of() : vars;
        Map<String, Object> tenantMap = safeVars.containsKey("tenant") ? null : tenantConfig.celTenantMap();
        LinkedHashSet<TransitionDef> out = new LinkedHashSet<>();
        for (StateDef s : model.states()) {
            int qty = buckets.getOrDefault(s.id(), 0);
            if (qty <= 0) continue;
            for (TransitionDef t : model.transitionsFrom(s.id())) {
                TransitionRequest probe = new TransitionRequest(entityType, entityId, t.trigger(), null, s.id(), safeVars, actor, null, null);
                if (evaluate(t, probe, actor, model, buckets, s.id(), qty, allHolds, allHolds, tenantMap).isEmpty()) out.add(t);
            }
        }
        return new ArrayList<>(out);
    }
}
