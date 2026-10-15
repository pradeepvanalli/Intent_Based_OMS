package ai.intentoms.platform.time;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;

/** Injectable clock so simulations and tests can freeze time. */
@Component
public class TimeSource {
    private volatile Clock clock = Clock.systemUTC();
    public Instant now() { return clock.instant(); }
    public Clock clock() { return clock; }
    public void override(Clock c) { this.clock = c; }
    public void reset() { this.clock = Clock.systemUTC(); }
}
