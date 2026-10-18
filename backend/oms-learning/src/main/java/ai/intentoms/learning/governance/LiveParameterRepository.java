package ai.intentoms.learning.governance;

import ai.intentoms.platform.tenant.TenantContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Table {@code learned_parameter_live}: which learned parameter families are promoted for live use, and their bounds. */
@Repository
public class LiveParameterRepository {
    private final JdbcClient jdbc;
    public LiveParameterRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    public record LiveParameter(String parameter, String learnerKey, Double min, Double max, Double maxStep, String status,
                                String proposalId, String promotedBy, Instant promotedAt, String revokedBy, Instant revokedAt) {
        public boolean live() { return "LIVE".equals(status); }
    }

    private static final String COLS = "parameter, learner_key, min_value, max_value, max_step, status, proposal_id, promoted_by, promoted_at, revoked_by, revoked_at";

    public Optional<LiveParameter> find(String parameter) {
        return jdbc.sql("select " + COLS + " from learned_parameter_live where tenant_id = :t and parameter = :p")
            .param("t", TenantContext.require()).param("p", parameter).query().listOfRows().stream().map(LiveParameterRepository::hydrate).findFirst();
    }

    public List<LiveParameter> all() {
        return jdbc.sql("select " + COLS + " from learned_parameter_live where tenant_id = :t order by parameter")
            .param("t", TenantContext.require()).query().listOfRows().stream().map(LiveParameterRepository::hydrate).toList();
    }

    public void promote(String parameter, String learnerKey, Double min, Double max, Double maxStep, String proposalId, String by, Instant at) {
        jdbc.sql("""
            insert into learned_parameter_live(tenant_id, parameter, learner_key, min_value, max_value, max_step, status, proposal_id, promoted_by, promoted_at)
            values (:t, :p, :l, :min, :max, :step, 'LIVE', :prop, :by, :at)
            on conflict (tenant_id, parameter) do update set learner_key = :l, min_value = :min, max_value = :max, max_step = :step, status = 'LIVE',
              proposal_id = :prop, promoted_by = :by, promoted_at = :at, revoked_by = null, revoked_at = null
            """).param("t", TenantContext.require()).param("p", parameter).param("l", learnerKey).param("min", min).param("max", max).param("step", maxStep)
            .param("prop", proposalId).param("by", by).param("at", Timestamp.from(at)).update();
    }

    public boolean revoke(String parameter, String by, Instant at) {
        return jdbc.sql("update learned_parameter_live set status = 'REVOKED', revoked_by = :by, revoked_at = :at where tenant_id = :t and parameter = :p and status = 'LIVE'")
            .param("by", by).param("at", Timestamp.from(at)).param("t", TenantContext.require()).param("p", parameter).update() == 1;
    }

    private static LiveParameter hydrate(Map<String, Object> r) {
        return new LiveParameter((String) r.get("parameter"), (String) r.get("learner_key"), (Double) r.get("min_value"), (Double) r.get("max_value"),
            (Double) r.get("max_step"), (String) r.get("status"), (String) r.get("proposal_id"), (String) r.get("promoted_by"), ts(r.get("promoted_at")),
            (String) r.get("revoked_by"), ts(r.get("revoked_at")));
    }

    private static Instant ts(Object o) { return o == null ? null : ((Timestamp) o).toInstant(); }
}
