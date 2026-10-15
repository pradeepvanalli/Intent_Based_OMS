package ai.intentoms.monitoring.playbook;

import ai.intentoms.platform.config.TenantConfigStore;
import ai.intentoms.platform.jdbc.Jsonb;
import ai.intentoms.platform.json.Json;
import ai.intentoms.platform.tenant.TenantContext;
import com.fasterxml.jackson.core.type.TypeReference;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Persistence of {@code playbook} definitions (FR-MON-04); spend limits live in tenant config {@value #SPEND_LIMITS_KEY}. */
@Repository
public class PlaybookRepository {
    public static final String SPEND_LIMITS_KEY = "playbooks.spend_limits";
    private static final TypeReference<List<Map<String, Object>>> STEPS = new TypeReference<>() {};
    private static final RowMapper<Playbook> MAPPER = (rs, i) -> fromJson(rs.getString("definition"));
    private final JdbcClient jdbc;
    private final TenantConfigStore config;

    public PlaybookRepository(JdbcClient jdbc, TenantConfigStore config) {
        this.jdbc = jdbc;
        this.config = config;
    }

    @SuppressWarnings("unchecked")
    static Playbook fromJson(String json) {
        Map<String, Object> m = Json.readMap(json);
        Object steps = m.get("steps");
        return new Playbook((String) m.get("name"), (String) m.get("description"), steps == null ? List.of() : Json.convert(steps, STEPS));
    }

    public List<Playbook> list() {
        return jdbc.sql("select definition::text as definition from playbook where tenant_id = :t order by name").param("t", TenantContext.require()).query(MAPPER).list();
    }

    public Optional<Playbook> find(String name) {
        return jdbc.sql("select definition::text as definition from playbook where tenant_id = :t and name = :n").param("t", TenantContext.require()).param("n", name).query(MAPPER).optional();
    }

    public Playbook upsert(Playbook p) {
        jdbc.sql("insert into playbook(tenant_id, name, definition) values (:t, :n, :d) on conflict (tenant_id, name) do update set definition = excluded.definition, updated_at = now()")
            .param("t", TenantContext.require()).param("n", p.name()).param("d", Jsonb.of(p.asMap())).update();
        return p;
    }

    public boolean delete(String name) {
        return jdbc.sql("delete from playbook where tenant_id = :t and name = :n").param("t", TenantContext.require()).param("n", name).update() > 0;
    }

    public Map<String, Object> spendLimits() { return config.getMap(SPEND_LIMITS_KEY); }

    public void setSpendLimits(Map<String, Object> limits) { config.set(SPEND_LIMITS_KEY, limits); }
}
