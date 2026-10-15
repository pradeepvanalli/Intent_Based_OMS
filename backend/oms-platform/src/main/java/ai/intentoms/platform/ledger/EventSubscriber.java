package ai.intentoms.platform.ledger;

/**
 * In-process consumer of ledger events, invoked by the {@link OutboxRelay} after commit, in per-aggregate order.
 * Implementations must be idempotent (the relay retries on failure). Return the event type prefixes you care about
 * ("order.", "line.transitioned"); "*" means everything.
 */
public interface EventSubscriber {
    java.util.List<String> eventTypePrefixes();
    void on(Event event);
    default String name() { return getClass().getSimpleName(); }
}
