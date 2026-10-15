package ai.intentoms.statemachine.service;

import ai.intentoms.platform.ids.Ids;
import ai.intentoms.platform.tenant.TenantContext;
import ai.intentoms.platform.time.TimeSource;
import ai.intentoms.statemachine.model.SlaTimer;
import ai.intentoms.statemachine.model.StateDef;
import ai.intentoms.statemachine.model.StateModel;
import ai.intentoms.statemachine.store.SlaTimerRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * Keeps one open SLA timer per (entity, state) while quantity sits in a state that declares an {@code sla} (FR-SM-08).
 * Called inside the transition transaction so timers and quantity never disagree.
 */
@Service
public class SlaTimerService {
    private final SlaTimerRepository repo;
    private final TimeSource time;

    public SlaTimerService(SlaTimerRepository repo, TimeSource time) {
        this.repo = repo;
        this.time = time;
    }

    /** Schedule the state's timer (no-op when the state has no SLA or a timer is already open). */
    public void onEntered(String entityType, String entityId, StateDef state) {
        if (state.sla() == null) return;
        if (!repo.open(entityType, entityId, state.id()).isEmpty()) return;
        Instant now = time.now();
        repo.insert(new SlaTimer(Ids.id(), TenantContext.require(), entityType, entityId, state.id(), now, now.plus(state.sla().maxAge()), state.sla().onBreach(), null, null));
    }

    /** Cancel open timers of a state the entity has fully left. */
    public void onLeft(String entityType, String entityId, String stateId) {
        repo.cancel(entityType, entityId, stateId, time.now());
    }

    /** After a version migration: add timers the new version requires, cancel those it dropped. */
    public void reconcile(String entityType, String entityId, StateModel model, Set<String> statesWithQty) {
        for (SlaTimer t : repo.open(entityType, entityId, null)) {
            boolean keep = statesWithQty.contains(t.state()) && model.state(t.state()).map(s -> s.sla() != null).orElse(false);
            if (!keep) repo.cancel(entityType, entityId, t.state(), time.now());
        }
        for (String s : statesWithQty) model.state(s).ifPresent(def -> onEntered(entityType, entityId, def));
    }

    public List<SlaTimer> open(String entityType, String entityId) { return repo.open(entityType, entityId, null); }
}
