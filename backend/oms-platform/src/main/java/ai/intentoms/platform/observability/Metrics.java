package ai.intentoms.platform.observability;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.function.Supplier;

/** Thin helper so modules record consistently tagged timers/counters (NFR-10). */
@Component
public class Metrics {
    private final MeterRegistry registry;

    public Metrics(MeterRegistry registry) { this.registry = registry; }

    public MeterRegistry registry() { return registry; }

    public <T> T time(String name, Supplier<T> body, String... tags) {
        Timer.Sample s = Timer.start(registry);
        try { return body.get(); }
        finally { s.stop(Timer.builder(name).tags(tags).publishPercentiles(0.5, 0.95, 0.99).register(registry)); }
    }

    public void record(String name, Duration d, String... tags) {
        Timer.builder(name).tags(tags).publishPercentiles(0.5, 0.95, 0.99).register(registry).record(d);
    }

    public void count(String name, String... tags) { registry.counter(name, tags).increment(); }
    public void count(String name, double amount, String... tags) { registry.counter(name, tags).increment(amount); }
}
