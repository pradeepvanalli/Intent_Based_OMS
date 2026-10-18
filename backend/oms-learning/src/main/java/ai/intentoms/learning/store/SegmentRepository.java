package ai.intentoms.learning.store;

import ai.intentoms.learning.model.Segment;
import ai.intentoms.platform.jdbc.Jsonb;
import ai.intentoms.platform.json.Json;
import ai.intentoms.platform.tenant.TenantContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;

/** Persistence for {@code segment} (CI-02): analyst-discovered, statistics-defined customer segments. */
@Repository
public class SegmentRepository {
    private final JdbcClient jdbc;
    public SegmentRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    public void insert(Segment s) {
        jdbc.sql("""
            insert into segment(segment_id, tenant_id, version, label, description, definition, size, created_by, created_at)
            values (:id, :t, :v, :label, :desc, :def, :size, :by, :created)
            """)
            .param("id", s.segmentId()).param("t", TenantContext.require()).param("v", s.version()).param("label", s.label()).param("desc", s.description())
            .param("def", Jsonb.of(s.definition())).param("size", s.size()).param("by", s.createdBy()).param("created", Timestamp.from(s.createdAt())).update();
    }

    public List<Segment> list() {
        String t = TenantContext.require();
        return jdbc.sql("select segment_id, version, label, description, definition::text as definition, size, created_by, created_at from segment where tenant_id = :t order by label, version desc")
            .param("t", t).query().listOfRows().stream().map(SegmentRepository::hydrate).toList();
    }

    private static Segment hydrate(Map<String, Object> r) {
        return new Segment((String) r.get("segment_id"), (Integer) r.get("version"), (String) r.get("label"), (String) r.get("description"),
            Json.readMap((String) r.get("definition")), (Integer) r.get("size"), (String) r.get("created_by"), ((Timestamp) r.get("created_at")).toInstant());
    }
}
