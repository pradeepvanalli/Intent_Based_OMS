package ai.intentoms.learning.governance;

import ai.intentoms.platform.json.Json;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/**
 * GV-03, global level: one switch that stops all learning for every tenant. A platform administrator flips it at runtime
 * (stored in {@code learning_global_setting}, shared by every instance); the {@code oms.learning.kill-switch} property
 * forces it on from startup for an operator who cannot reach the API. Read through a five-second cache, so a flip
 * reaches every instance within seconds and live decisions never query for it.
 */
@Component
public class GlobalLearningSwitch {
    static final String KEY = "learning.kill_switch";

    private final JdbcClient jdbc;
    private final boolean forcedByProperty;
    private final Cache<String, State> cache = Caffeine.newBuilder().expireAfterWrite(Duration.ofSeconds(5)).maximumSize(1).build();

    public GlobalLearningSwitch(JdbcClient jdbc, @Value("${oms.learning.kill-switch:false}") boolean forcedByProperty) {
        this.jdbc = jdbc;
        this.forcedByProperty = forcedByProperty;
    }

    /** {@code source}: "property" when forced at startup, "runtime" when set through the API, null when off. */
    public record State(boolean on, String source, String updatedBy, Instant updatedAt) {}

    public boolean on() { return state().on(); }

    public State state() {
        if (forcedByProperty) return new State(true, "property", "oms.learning.kill-switch", null);
        return cache.get(KEY, k -> load());
    }

    public State set(boolean on, String by) {
        jdbc.sql("""
            insert into learning_global_setting(setting_key, value, updated_by, updated_at) values (:k, cast(:v as jsonb), :by, now())
            on conflict (setting_key) do update set value = excluded.value, updated_by = excluded.updated_by, updated_at = excluded.updated_at
            """).param("k", KEY).param("v", Json.write(Map.of("on", on))).param("by", by).update();
        cache.invalidateAll();
        return state();
    }

    private State load() {
        return jdbc.sql("select value::text as value, updated_by, updated_at from learning_global_setting where setting_key = :k").param("k", KEY)
            .query().listOfRows().stream().findFirst().map(r -> {
                boolean on = Boolean.TRUE.equals(Json.readMap((String) r.get("value")).get("on"));
                return new State(on, on ? "runtime" : null, (String) r.get("updated_by"), ((Timestamp) r.get("updated_at")).toInstant());
            }).orElse(new State(false, null, null, null));
    }
}
