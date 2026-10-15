package ai.intentoms.statemachine.service;

import ai.intentoms.platform.ledger.Actor;
import ai.intentoms.platform.ledger.Event;
import ai.intentoms.platform.ledger.EventStore;
import ai.intentoms.platform.ledger.NewEvent;
import ai.intentoms.platform.observability.Metrics;
import ai.intentoms.platform.tenant.TenantContext;
import ai.intentoms.platform.time.TimeSource;
import ai.intentoms.statemachine.HoldService;
import ai.intentoms.statemachine.SlaBreachedEvent;
import ai.intentoms.statemachine.config.StateMachineProperties;
import ai.intentoms.statemachine.model.SlaTimer;
import ai.intentoms.statemachine.store.SlaTimerRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Background sweep (every {@code oms.statemachine.sweep-ms}, default 5 s) that fires due SLA timers (FR-SM-08) and
 * releases expired holds (FR-SM-07). Each timer is processed in its own transaction: the row is locked with
 * {@code skip locked} so several nodes can sweep concurrently, an {@code sla.breached} event (payload: entityType,
 * entityId, state, onBreach, enteredAt, dueAt, ageSeconds) is appended to the entity's aggregate, the timer is marked
 * fired and {@link SlaBreachedEvent} is published for monitoring/playbooks.
 */
@Component
public class StateMachineSweeper {
    private static final Logger log = LoggerFactory.getLogger(StateMachineSweeper.class);

    private final SlaTimerRepository timers;
    private final EventStore events;
    private final ApplicationEventPublisher spring;
    private final HoldService holds;
    private final TimeSource time;
    private final Metrics metrics;
    private final StateMachineProperties props;
    private final TransactionTemplate tx;

    public StateMachineSweeper(SlaTimerRepository timers, EventStore events, ApplicationEventPublisher spring, HoldService holds, TimeSource time,
                               Metrics metrics, StateMachineProperties props, TransactionTemplate tx) {
        this.timers = timers;
        this.events = events;
        this.spring = spring;
        this.holds = holds;
        this.time = time;
        this.metrics = metrics;
        this.props = props;
        this.tx = tx;
    }

    @Scheduled(fixedDelayString = "${oms.statemachine.sweep-ms:5000}", initialDelayString = "${oms.statemachine.sweep-ms:5000}")
    public void sweep() {
        try { fireDueSlaTimers(); } catch (RuntimeException e) { log.warn("SLA sweep failed: {}", e.getMessage()); }
        try { holds.releaseExpired(); } catch (RuntimeException e) { log.warn("Hold expiry sweep failed: {}", e.getMessage()); }
    }

    /** Fire every due timer (up to {@code sweepBatch}); returns the number fired. Safe to call directly from tests and ops endpoints. */
    public int fireDueSlaTimers() {
        Instant now = time.now();
        List<SlaTimer> due = tx.execute(s -> timers.due(now, props.sweepBatch()));
        int fired = 0;
        for (SlaTimer t : due == null ? List.<SlaTimer>of() : due) {
            try {
                Boolean ok = TenantContext.supplyAs(t.tenantId(), () -> tx.execute(s -> fire(t, now)));
                if (Boolean.TRUE.equals(ok)) fired++;
            } catch (RuntimeException e) {
                log.warn("Failed to fire SLA timer {} ({} {} state {}): {}", t.id(), t.entityType(), t.entityId(), t.state(), e.getMessage());
            }
        }
        return fired;
    }

    private boolean fire(SlaTimer t, Instant now) {
        SlaTimer locked = timers.lockOpen(t.id()).orElse(null);
        if (locked == null) return false; // fired, cancelled or being handled by another node
        long age = Duration.between(locked.enteredAt(), now).getSeconds();
        var agg = Aggregates.of(locked.entityType(), locked.entityId());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("entityType", locked.entityType());
        payload.put("entityId", locked.entityId());
        payload.put("state", locked.state());
        payload.put("onBreach", locked.onBreach());
        payload.put("enteredAt", locked.enteredAt().toString());
        payload.put("dueAt", locked.dueAt().toString());
        payload.put("ageSeconds", age);
        payload.put("timerId", locked.id());
        List<Event> written = events.append(agg.type(), agg.id(), -1, List.of(new NewEvent("sla.breached", payload, Actor.system("sla-sweeper"), null, null,
            Map.of("entityType", locked.entityType(), "entityId", locked.entityId()))));
        timers.markFired(locked.id(), now);
        spring.publishEvent(new SlaBreachedEvent(locked.tenantId(), locked.entityType(), locked.entityId(), locked.state(), locked.onBreach(), age, written.get(0).id()));
        metrics.count("oms.statemachine.sla_breaches", "entity_type", locked.entityType(), "state", locked.state());
        return true;
    }
}
