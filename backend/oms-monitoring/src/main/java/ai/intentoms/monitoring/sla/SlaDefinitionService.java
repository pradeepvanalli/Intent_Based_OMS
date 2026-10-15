package ai.intentoms.monitoring.sla;

import ai.intentoms.platform.error.NotFoundException;
import ai.intentoms.platform.error.ValidationException;
import ai.intentoms.platform.ledger.Actor;
import ai.intentoms.platform.ledger.EventStore;
import ai.intentoms.platform.ledger.NewEvent;
import ai.intentoms.platform.security.AuthorizationService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * SLA definitions per state and order type (FR-MON-02). The state-model {@code sla:} blocks are the runtime timers
 * (FR-SM-08); this table is the business-facing catalogue the UI edits and the stuck-order sweeper reads
 * ({@code expectedNextEventWithin}, FR-MON-05). Changes are recorded on the {@code config} aggregate.
 */
@Service
public class SlaDefinitionService {
    /** Fallback windows when no definition carries {@code expectedNextEventWithin} for a state (FR-MON-05). */
    public static final Map<String, Duration> DEFAULT_NEXT_EVENT_WINDOWS = Map.ofEntries(
        Map.entry("created", Duration.ofMinutes(15)), Map.entry("validated", Duration.ofMinutes(30)), Map.entry("sourcing", Duration.ofHours(1)),
        Map.entry("awaiting_sourcing", Duration.ofHours(1)), Map.entry("released", Duration.ofHours(4)), Map.entry("picking", Duration.ofHours(6)),
        Map.entry("packed", Duration.ofHours(12)), Map.entry("partially_shipped", Duration.ofDays(3)), Map.entry("shipped", Duration.ofDays(7)),
        Map.entry("in_transit", Duration.ofDays(7)), Map.entry("partially_delivered", Duration.ofDays(7)), Map.entry("ready_for_pickup", Duration.ofDays(5)),
        Map.entry("backordered", Duration.ofDays(3)), Map.entry("on_hold", Duration.ofDays(3)));

    private final SlaDefinitionRepository repo;
    private final EventStore events;
    private final AuthorizationService authz;

    public SlaDefinitionService(SlaDefinitionRepository repo, EventStore events, AuthorizationService authz) {
        this.repo = repo;
        this.events = events;
        this.authz = authz;
    }

    public List<SlaDefinition> list() { return repo.list(); }

    public SlaDefinition get(String id) { return repo.find(id).orElseThrow(() -> new NotFoundException("SLA definition " + id + " not found")); }

    /** Exact (orderType, entity, state) match, then the {@code "*"} order type. */
    public Optional<SlaDefinition> find(String orderType, String entity, String state) {
        Optional<SlaDefinition> exact = orderType == null ? Optional.empty() : repo.find(orderType, entity, state);
        return exact.isPresent() ? exact : repo.find("*", entity, state);
    }

    /** Window inside which an open order in {@code state} must produce another ledger event (FR-MON-05). */
    public Optional<Duration> expectedNextEventWithin(String orderType, String state) {
        for (SlaDefinition d : repo.forState(orderType, state)) if (d.expectedNextEventWithin() != null) return Optional.of(d.expectedNextEventWithinDuration());
        return Optional.ofNullable(DEFAULT_NEXT_EVENT_WINDOWS.get(state));
    }

    @Transactional
    public SlaDefinition upsert(SlaDefinition d) {
        if (d.maxAge() == null && d.expectedNextEventWithin() == null) throw new ValidationException("maxAge or expectedNextEventWithin is required");
        if (d.onBreach() != null && !(d.onBreach().startsWith("playbook.") || d.onBreach().startsWith("decision.")))
            throw new ValidationException("onBreach must be playbook.<name> or decision.<name>", Map.of("onBreach", d.onBreach()));
        SlaDefinition saved = repo.upsert(d);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", saved.id());
        payload.put("orderType", saved.orderType());
        payload.put("entity", saved.entity());
        payload.put("state", saved.state());
        if (saved.maxAge() != null) payload.put("maxAge", saved.maxAge());
        if (saved.onBreach() != null) payload.put("onBreach", saved.onBreach());
        if (saved.expectedNextEventWithin() != null) payload.put("expectedNextEventWithin", saved.expectedNextEventWithin());
        events.append("config", "sla:" + saved.orderType() + ":" + saved.entity() + ":" + saved.state(), new NewEvent("sla.definition_changed", payload, Actor.parse(authz.actorRef())));
        return saved;
    }

    @Transactional
    public void delete(String id) {
        SlaDefinition d = get(id);
        repo.delete(id);
        events.append("config", "sla:" + d.orderType() + ":" + d.entity() + ":" + d.state(), new NewEvent("sla.definition_deleted", Map.of("id", id), Actor.parse(authz.actorRef())));
    }
}
