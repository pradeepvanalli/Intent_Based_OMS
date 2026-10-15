package ai.intentoms.sourcing.rates;

import ai.intentoms.platform.error.NotFoundException;
import ai.intentoms.platform.jdbc.Jsonb;
import ai.intentoms.platform.json.Json;
import ai.intentoms.platform.tenant.TenantContext;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;

/** The tenant's rate card ({@code shipping_rate_card}), parsed once and cached 30 s so the promise path never re-parses. */
@Repository
public class RateCardRepository {
    private final JdbcClient jdbc;
    private final Cache<String, Optional<RateCard>> cache = Caffeine.newBuilder().expireAfterWrite(Duration.ofSeconds(30)).maximumSize(1_000).build();

    public RateCardRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    public Optional<RateCard> find() {
        String t = TenantContext.require();
        return cache.get(t, k -> jdbc.sql("select definition::text from shipping_rate_card where tenant_id = :t").param("t", t)
            .query(String.class).optional().map(s -> RateCard.fromMap(Json.readMap(s))));
    }

    /** The tenant's rate card; a tenant without one cannot source or promise, which is surfaced as 404 {@code not_found}. */
    public RateCard current() { return find().orElseThrow(() -> new NotFoundException("No shipping rate card loaded for tenant " + TenantContext.require())); }

    public RateCard save(Map<String, Object> definition) {
        RateCard card = RateCard.fromMap(definition);
        String t = TenantContext.require();
        jdbc.sql("insert into shipping_rate_card(tenant_id, definition) values (:t, :d) on conflict (tenant_id) do update set definition = excluded.definition, updated_at = now()")
            .param("t", t).param("d", Jsonb.of(definition)).update();
        cache.invalidate(t);
        return card;
    }

    public void invalidate() { TenantContext.current().ifPresent(cache::invalidate); }
}
