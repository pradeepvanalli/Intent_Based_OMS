package ai.intentoms.decision.model;

import java.time.Duration;

/** Result cache by input hash with a TTL (FR-DEC-11); only LIVE evaluations populate or read it. */
public record CacheDef(Duration ttl) {
    public CacheDef {
        if (ttl == null || ttl.isNegative() || ttl.isZero()) throw new IllegalArgumentException("cache.ttl must be a positive ISO-8601 duration");
    }
}
