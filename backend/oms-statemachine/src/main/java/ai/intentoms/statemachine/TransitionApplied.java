package ai.intentoms.statemachine;

import java.util.List;

/**
 * Spring application event published (inside the transaction) for every applied transition so other modules can run the
 * declared {@code actions} (reserve_inventory, send_release_to_wms, ...) and react to state changes (FR-SM-03).
 * Listen with {@code @EventListener} or {@code @TransactionalEventListener}.
 */
public record TransitionApplied(String tenant, String entityType, String entityId, String fromState, String toState, int qty,
                                List<String> actions, String trigger, String eventId, String correlationId) {}
