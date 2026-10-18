package ai.intentoms.learning.store;

import ai.intentoms.learning.model.ObjectiveProfile;
import ai.intentoms.learning.model.ObjectiveWeights;
import ai.intentoms.platform.jdbc.Jsonb;
import ai.intentoms.platform.json.Json;
import ai.intentoms.platform.tenant.TenantContext;
import com.fasterxml.jackson.core.type.TypeReference;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Persistence for {@code objective_profile} (§7). Only one version per tenant is ever APPROVED (live) at a time. */
@Repository
public class ObjectiveProfileRepository {
    private static final TypeReference<Map<String, String>> STRING_MAP = new TypeReference<>() {};
    private final JdbcClient jdbc;
    public ObjectiveProfileRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    private static final String COLS = "version, status, weights::text as weights, constraints::text as constraints, approved_by, approved_at, created_by, created_at, " +
        "source_text, draft_method, rationale::text as rationale";

    public void insert(ObjectiveProfile p) {
        jdbc.sql("""
            insert into objective_profile(tenant_id, version, status, weights, constraints, approved_by, approved_at, created_by, created_at, source_text, draft_method, rationale)
            values (:t, :v, :status, :w, :c, :by, :at, :cb, :created, :src, :method, :rationale)
            """)
            .param("t", TenantContext.require()).param("v", p.version()).param("status", p.status().name()).param("w", Jsonb.of(p.weights()))
            .param("c", Jsonb.of(p.constraints())).param("by", p.approvedBy()).param("at", p.approvedAt() == null ? null : Timestamp.from(p.approvedAt()))
            .param("cb", p.createdBy()).param("created", Timestamp.from(p.createdAt())).param("src", p.sourceText()).param("method", p.draftMethod())
            .param("rationale", Jsonb.of(p.rationale() == null ? Map.of() : p.rationale())).update();
    }

    public Optional<ObjectiveProfile> live() {
        String t = TenantContext.require();
        return jdbc.sql("select " + COLS + " from objective_profile where tenant_id = :t and status = 'APPROVED' order by version desc limit 1")
            .param("t", t).query().listOfRows().stream().findFirst().map(ObjectiveProfileRepository::hydrate);
    }

    public Optional<ObjectiveProfile> find(int version) {
        return jdbc.sql("select " + COLS + " from objective_profile where tenant_id = :t and version = :v")
            .param("t", TenantContext.require()).param("v", version).query().listOfRows().stream().findFirst().map(ObjectiveProfileRepository::hydrate);
    }

    /** XP-07: drafts waiting for a second person's approval, newest first. */
    public List<ObjectiveProfile> drafts() {
        return jdbc.sql("select " + COLS + " from objective_profile where tenant_id = :t and status = 'DRAFT' order by version desc")
            .param("t", TenantContext.require()).query().listOfRows().stream().map(ObjectiveProfileRepository::hydrate).toList();
    }

    /** Every version, newest first (XP-02 baseline and audit). */
    public List<ObjectiveProfile> history(int limit) {
        return jdbc.sql("select " + COLS + " from objective_profile where tenant_id = :t order by version desc limit :lim")
            .param("t", TenantContext.require()).param("lim", limit).query().listOfRows().stream().map(ObjectiveProfileRepository::hydrate).toList();
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
        String rationale = (String) r.get("rationale");
        return new ObjectiveProfile((Integer) r.get("version"), ObjectiveProfile.Status.valueOf((String) r.get("status")),
            Json.read((String) r.get("weights"), ObjectiveWeights.class), Json.readMap((String) r.get("constraints")), (String) r.get("approved_by"),
            ts(r.get("approved_at")), (String) r.get("created_by"), ts(r.get("created_at")), (String) r.get("source_text"), (String) r.get("draft_method"),
            rationale == null || rationale.isBlank() ? Map.of() : Json.read(rationale, STRING_MAP));
    }

    private static java.time.Instant ts(Object o) { return o == null ? null : ((Timestamp) o).toInstant(); }
}
