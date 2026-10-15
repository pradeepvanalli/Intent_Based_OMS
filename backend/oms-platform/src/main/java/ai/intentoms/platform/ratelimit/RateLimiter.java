package ai.intentoms.platform.ratelimit;

import ai.intentoms.platform.error.RateLimitedException;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import org.springframework.stereotype.Component;

import java.time.Duration;

/** Token-bucket limits per tenant, agent or API key (NFR-09, FR-AGT-04). In-memory per instance; swap for Redis-backed buckets at scale. */
@Component
public class RateLimiter {
    private final Cache<String, Bucket> buckets = Caffeine.newBuilder().expireAfterAccess(Duration.ofMinutes(10)).maximumSize(100_000).build();

    public boolean tryAcquire(String key, long capacity, Duration refill) {
        Bucket b = buckets.get(key, k -> Bucket.builder().addLimit(Bandwidth.builder().capacity(capacity).refillGreedy(capacity, refill).build()).build());
        return b.tryConsume(1);
    }

    public void acquireOrThrow(String key, long capacity, Duration refill, String what) {
        if (!tryAcquire(key, capacity, refill)) throw new RateLimitedException("Rate limit exceeded for " + what);
    }
}
