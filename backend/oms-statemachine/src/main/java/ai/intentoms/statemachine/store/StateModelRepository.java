package ai.intentoms.statemachine.store;

import ai.intentoms.platform.jdbc.Jsonb;
import ai.intentoms.platform.tenant.TenantContext;
import ai.intentoms.statemachine.model.StateModel;
import ai.intentoms.statemachine.model.StateModelParser;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Persistence of published model versions in {@code state_model} (FR-SM-06, FR-SM-09). Rows are immutable once written. */
@Repository
public class StateModelRepository {
    private final JdbcClient jdbc;

    /** Re-parse from the stored YAML so the Java model is always exactly what was published. */
    private static final RowMapper<StateModel> MAPPER = (rs, i) -> StateModelParser.parse(rs.getString("yaml"));

    public StateModelRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    public void insert(StateModel m, String publishedBy, String reason, Instant at) {
        jdbc.sql("""
            insert into state_model(tenant_id, model_id, version, entity, yaml, parsed, published_at, published_by, reason)
            values (:t, :m, :v, :e, :y, :p, :at, :by, :r)
            """)
            .param("t", TenantContext.require()).param("m", m.id()).param("v", m.version()).param("e", m.entity() == null ? "" : m.entity())
            .param("y", m.yaml()).param("p", Jsonb.of(m.toMap())).param("at", Timestamp.from(at)).param("by", publishedBy).param("r", reason)
            .update();
    }

    public Optional<StateModel> find(String modelId, int version) {
        return jdbc.sql("select yaml from state_model where tenant_id = :t and model_id = :m and version = :v")
            .param("t", TenantContext.require()).param("m", modelId).param("v", version).query(MAPPER).optional();
    }

    public Optional<Integer> latestVersion(String modelId) {
        return jdbc.sql("select max(version) from state_model where tenant_id = :t and model_id = :m")
            .param("t", TenantContext.require()).param("m", modelId).query(Integer.class).optional();
    }

    public boolean exists(String modelId, int version) {
        return jdbc.sql("select count(*) from state_model where tenant_id = :t and model_id = :m and version = :v")
            .param("t", TenantContext.require()).param("m", modelId).param("v", version).query(Long.class).single() > 0;
    }

    public List<StateModel> versions(String modelId) {
        return jdbc.sql("select yaml from state_model where tenant_id = :t and model_id = :m order by version")
            .param("t", TenantContext.require()).param("m", modelId).query(MAPPER).list();
    }

    public List<String> modelIds() {
        return jdbc.sql("select distinct model_id from state_model where tenant_id = :t order by model_id")
            .param("t", TenantContext.require()).query(String.class).list();
    }

    /** Publication metadata for the API (who/when/why). */
    public record PublishInfo(String modelId, int version, String entity, Instant publishedAt, String publishedBy, String reason) {}

    public List<PublishInfo> publishInfos(String modelId) {
        return jdbc.sql("select model_id, version, entity, published_at, published_by, reason from state_model where tenant_id = :t and model_id = :m order by version")
            .param("t", TenantContext.require()).param("m", modelId)
            .query((rs, i) -> new PublishInfo(rs.getString("model_id"), rs.getInt("version"), rs.getString("entity"),
                rs.getTimestamp("published_at").toInstant(), rs.getString("published_by"), rs.getString("reason")))
            .list();
    }
}
