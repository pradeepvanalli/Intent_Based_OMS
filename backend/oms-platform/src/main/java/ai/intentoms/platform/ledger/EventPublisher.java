package ai.intentoms.platform.ledger;

/** External sink for CloudEvents (Kafka topic, webhook, onX). The relay fans out to every registered publisher. */
public interface EventPublisher {
    void publish(Event event);
    default String name() { return getClass().getSimpleName(); }
}
