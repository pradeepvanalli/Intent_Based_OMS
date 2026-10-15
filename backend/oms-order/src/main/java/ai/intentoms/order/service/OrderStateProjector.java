package ai.intentoms.order.service;

import ai.intentoms.platform.tenant.TenantContext;
import ai.intentoms.statemachine.StateMachine;
import ai.intentoms.statemachine.TransitionApplied;
import ai.intentoms.statemachine.model.EntityStatus;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Keeps the denormalised {@code orders.status} and {@code order_line.status} columns equal to the state machine's dominant
 * state (FR-SM-05 roll-up, FR-MON-01 pipeline queries). {@link TransitionApplied} is published inside the transition's
 * transaction on the same thread, so this update commits with the transition and never races the optimistic order version
 * (status is derived data; the version is not bumped).
 */
@Component
public class OrderStateProjector {
    private final JdbcClient jdbc;
    private final StateMachine stateMachine;

    public OrderStateProjector(JdbcClient jdbc, StateMachine stateMachine) {
        this.jdbc = jdbc;
        this.stateMachine = stateMachine;
    }

    @EventListener
    public void on(TransitionApplied t) {
        if (t.entityType() == null) return;
        switch (t.entityType()) {
            case "order" -> TenantContext.runAs(t.tenant(), () ->
                jdbc.sql("update orders set status = :s, updated_at = now() where tenant_id = :t and id = :id and status <> :s")
                    .param("s", t.toState()).param("t", t.tenant()).param("id", t.entityId()).update());
            case "order_line" -> TenantContext.runAs(t.tenant(), () -> {
                int colon = t.entityId().indexOf(':');
                if (colon <= 0) return;
                String orderId = t.entityId().substring(0, colon), lineId = t.entityId().substring(colon + 1);
                EntityStatus status = stateMachine.status("order_line", t.entityId());
                jdbc.sql("update order_line set status = :s where tenant_id = :t and order_id = :o and line_id = :l")
                    .param("s", status.state()).param("t", t.tenant()).param("o", orderId).param("l", lineId).update();
            });
            default -> { }
        }
    }
}
