package ai.intentoms.sourcing.promise;

import ai.intentoms.sourcing.model.Promise;

import java.time.Duration;
import java.util.Optional;

/**
 * Optional second-level promise cache shared across instances (FR-SRC-05, NFR-01). The module ships only this SPI and
 * {@link NoopPromiseCacheL2}; a deployment that sets {@code oms.sourcing.promise.redis-url} provides a Redis-backed
 * {@code @Bean} of this type (Spring Data Redis is deliberately not a dependency of this module). Implementations must
 * never throw on the read path: a miss or an error returns {@code Optional.empty()}.
 */
public interface PromiseCacheL2 {
    Optional<Promise> get(String key);

    void put(String key, Promise promise, Duration ttl);

    default String name() { return getClass().getSimpleName(); }
}
