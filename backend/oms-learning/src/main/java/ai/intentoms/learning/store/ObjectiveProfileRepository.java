package ai.intentoms.learning.store;

import ai.intentoms.learning.model.ObjectiveProfile;
import ai.intentoms.learning.model.ObjectiveWeights;
import ai.intentoms.platform.jdbc.Jsonb;
import ai.intentoms.platform.json.Json;
import ai.intentoms.platform.tenant.TenantContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.Map;
import java.util.Optional;

/** Persistence for {@code objective_profile} (§7). Only one version per tenant is ever APPROVED (live) at a time. */
@Repository
public class ObjectiveProfileRepository {
    private final JdbcClient jdbc;
    public ObjectiveProfileRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    private static final String COLS = "version, status, weights::text as weights, constraints::text as constraints, approved_by, approved_at, created_by, created_at";

    public void insert(ObjectiveProfile p) {
        jdbc.sql("""
            insert into objective_profile(tenant_id, version, status, weights, constraints, approved_by, approved_at, created_by, created_at)
            values (:t, :v, :status, :w, :c, :by, :at, :cb, :created)
            """)
            .param("t", TenantContext.require()).param("v", p.version()).param("status", p.status().name()).param("w", Jsonb.of(p.weights()))
            .param("c", Jsonb.of(p.constraints())).param("by", p.approvedBy()).param("at", p.approvedAt() == null ? null : Timestamp.from(p.approvedAt()))
            .param("cb", p.createdBy()).param("created", Timestamp.from(p.createdAt())).update();
    }

    public Optional<ObjectiveProfile> live() {
        String t = TenantContext.require();
        return jdbc.sql("select " + COLS + " from objective_profile where tenant_id = :t and status = 'APPROVED' order by version desc limit 1")
            .param("t", t).query().listOfRows().stream().findFirst().map(ObjectiveProfileRepository::hydrate);
    }

    public int nextVersion() {
        String t = TenantContext.require();
        Integer max = jdbc.sql("select max(version) from objective_profile where tenant_id = :t").param("t", t).query(Integer.class).optional().orElse(null);
        return max == null ? 1 : max + 1;
    }

    public void approve(int version, String by, java.time.Instant at) {
        String t = TenantContext.require();
        jdbc.sql("update objective_profile set status = 'RETIRED' where tenant_id = :t and status = 'APPROVED'").param("t", t).update();
        jdbc.sql("update objective_profile set status = 'APPROVED', approved_by = :by, approved_at = :at where tenant_id = :t and version = :v")
            .param("by", by).param("at", Timestamp.from(at)).param("t", t).param("v", version).update();
    }

    private static ObjectiveProfile hydrate(Map<String, Object> r) {
        return new ObjectiveProfile((Integer) r.get("version"), ObjectiveProfile.Status.valueOf((String) r.get("status")),
            Json.read((String) r.get("weights"), ObjectiveWeights.class), Json.readMap((String) r.get("constraints")), (String) r.get("approved_by"),
            ts(r.get("approved_at")), (String) r.get("created_by"), ts(r.get("created_at")));
    }

    private static java.time.Instant ts(Object o) { return o == null ? null : ((Timestamp) o).toInstant(); }
}
