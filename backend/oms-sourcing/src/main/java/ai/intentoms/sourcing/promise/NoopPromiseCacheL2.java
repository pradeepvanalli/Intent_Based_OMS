package ai.intentoms.sourcing.promise;

import ai.intentoms.sourcing.model.Promise;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.Optional;

/** Default L2: nothing. Registered only when no other {@link PromiseCacheL2} bean exists. */
public final class NoopPromiseCacheL2 implements PromiseCacheL2 {
    @Override public Optional<Promise> get(String key) { return Optional.empty(); }
    @Override public void put(String key, Promise promise, Duration ttl) { }
    @Override public String name() { return "none"; }

    @Configuration
    public static class Registration {
        @Bean
        @ConditionalOnMissingBean(PromiseCacheL2.class)
        public PromiseCacheL2 noopPromiseCacheL2() { return new NoopPromiseCacheL2(); }
    }
}
