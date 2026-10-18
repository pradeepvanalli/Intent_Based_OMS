package ai.intentoms.learning.store;

import ai.intentoms.platform.tenant.TenantContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * EX-04: per decision, the version that was live before the first promoted self-optimizing change. Written once (the
 * first promotion wins) so the persistent holdout always compares against what the tenant ran before learning.
 */
@Repository
public class LearningBaselineRepository {
    private final JdbcClient jdbc;
    public LearningBaselineRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    public void recordIfAbsent(String decision, int baselineVersion, String proposalId) {
        jdbc.sql("insert into learning_baseline(tenant_id, decision, baseline_version, proposal_id) values (:t, :d, :v, :p) on conflict (tenant_id, decision) do nothing")
            .param("t", TenantContext.require()).param("d", decision).param("v", baselineVersion).param("p", proposalId).update();
    }

    public Optional<Integer> baseline(String decision) {
        return jdbc.sql("select baseline_version from learning_baseline where tenant_id = :t and decision = :d")
            .param("t", TenantContext.require()).param("d", decision).query(Integer.class).optional();
    }

    public Map<String, Integer> all() {
        Map<String, Integer> out = new LinkedHashMap<>();
        jdbc.sql("select decision, baseline_version from learning_baseline where tenant_id = :t order by decision")
            .param("t", TenantContext.require()).query().listOfRows().forEach(r -> out.put((String) r.get("decision"), (Integer) r.get("baseline_version")));
        return out;
    }
}
