package ai.intentoms.learning.store;

import ai.intentoms.learning.model.Direction;
import ai.intentoms.learning.model.Hypothesis;
import ai.intentoms.platform.jdbc.Jsonb;
import ai.intentoms.platform.json.Json;
import ai.intentoms.platform.tenant.TenantContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Persistence for {@code hypothesis} (the analyst LLM's output, §6.1). */
@Repository
public class HypothesisRepository {
    private final JdbcClient jdbc;
    public HypothesisRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    private ai.intentoms.learning.event.LearningEvents events;

    /** GV-04: every inserted hypothesis is recorded as {@code hypothesis.raised}. Setter-injected so plain
     * construction (tests) still works. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setEvents(ai.intentoms.learning.event.LearningEvents events) { this.events = events; }

    private static final String COLS = "hypothesis_id, scope::text as scope, target_metric, direction, lever::text as lever, prior_confidence, " +
        "evidence_refs::text as evidence_refs, rationale, status, created_at";

    public void insert(Hypothesis h) {
        jdbc.sql("""
            insert into hypothesis(hypothesis_id, tenant_id, scope, target_metric, direction, lever, prior_confidence, evidence_refs, rationale, status, created_at)
            values (:id, :t, :scope, :metric, :dir, :lever, :conf, :evidence, :rationale, :status, :created)
            """)
            .param("id", h.hypothesisId()).param("t", TenantContext.require()).param("scope", Jsonb.of(h.scope())).param("metric", h.targetMetric())
            .param("dir", h.expectedDirection().name()).param("lever", Jsonb.of(h.lever())).param("conf", h.priorConfidence())
            .param("evidence", Jsonb.of(h.evidence())).param("rationale", h.rationale()).param("status", h.status().name()).param("created", Timestamp.from(h.createdAt()))
            .update();
        if (events != null) {
            Map<String, Object> payload = new java.util.LinkedHashMap<>();
            payload.put("scope", h.scope());
            payload.put("targetMetric", h.targetMetric());
            payload.put("direction", h.expectedDirection().name());
            payload.put("priorConfidence", h.priorConfidence());
            payload.put("lever", h.lever());
            events.hypothesisRaised(h.hypothesisId(), ai.intentoms.platform.ledger.Actor.system("learning"), payload);
        }
    }

    public List<Hypothesis> byStatus(String status, int limit) {
        String t = TenantContext.require();
        return jdbc.sql("select " + COLS + " from hypothesis where tenant_id = :t and status = :s order by created_at desc limit :lim")
            .param("t", t).param("s", status).param("lim", limit).query().listOfRows().stream().map(HypothesisRepository::hydrate).toList();
    }

    public Optional<Hypothesis> find(String id) {
        String t = TenantContext.require();
        return jdbc.sql("select " + COLS + " from hypothesis where tenant_id = :t and hypothesis_id = :id").param("t", t).param("id", id)
            .query().listOfRows().stream().findFirst().map(HypothesisRepository::hydrate);
    }

    public void updateStatus(String id, Hypothesis.Status status) {
        jdbc.sql("update hypothesis set status = :s where tenant_id = :t and hypothesis_id = :id")
            .param("s", status.name()).param("t", TenantContext.require()).param("id", id).update();
    }

    private static final com.fasterxml.jackson.core.type.TypeReference<List<String>> STRINGS = new com.fasterxml.jackson.core.type.TypeReference<>() {};

    private static Hypothesis hydrate(Map<String, Object> r) {
        return new Hypothesis((String) r.get("hypothesis_id"), Json.readMap((String) r.get("scope")), (String) r.get("target_metric"),
            Direction.valueOf((String) r.get("direction")), Json.readMap((String) r.get("lever")), (Double) r.get("prior_confidence"),
            Json.read((String) r.get("evidence_refs"), STRINGS), (String) r.get("rationale"), Hypothesis.Status.valueOf((String) r.get("status")),
            ((Timestamp) r.get("created_at")).toInstant());
    }
}
