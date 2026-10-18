package ai.intentoms.learning.store;

import ai.intentoms.learning.model.FactKey;
import ai.intentoms.learning.model.LearnedFact;
import ai.intentoms.learning.model.SubjectType;
import ai.intentoms.platform.tenant.TenantContext;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;

/**
 * NF-01: active learned facts for live decision paths, cached so a decision reads facts from memory, not the
 * database. Entries live 15 seconds, so a newly learned value reaches live decisions within that time.
 */
@Component
public class LearnedFactCache {
    private final LearnedFactRepository facts;
    private final Cache<String, Optional<LearnedFact>> cache = Caffeine.newBuilder().expireAfterWrite(Duration.ofSeconds(15)).maximumSize(200_000).recordStats().build();

    public LearnedFactCache(LearnedFactRepository facts) { this.facts = facts; }

    public Optional<LearnedFact> active(SubjectType subjectType, String subjectId, FactKey key) {
        String k = TenantContext.require() + "|" + subjectType + "|" + subjectId + "|" + key;
        return cache.get(k, x -> facts.active(subjectType, subjectId, key));
    }

    public void invalidateAll() { cache.invalidateAll(); }

    public double hitRate() { return cache.stats().hitRate(); }
}
