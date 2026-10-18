package ai.intentoms.learning.store;

import ai.intentoms.learning.model.FactKey;
import ai.intentoms.learning.model.LearnedFact;
import ai.intentoms.learning.model.SubjectType;
import ai.intentoms.learning.model.TypedValue;
import ai.intentoms.platform.jdbc.Jsonb;
import ai.intentoms.platform.json.Json;
import ai.intentoms.platform.tenant.TenantContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Persistence for {@code learned_fact} (CI-01, FS-02/03, IU-01/05). Versioned, never updated in place: {@link
 * #supersede} closes the current active row's {@code valid_to} and {@link #insert} writes the next version. */
@Repository
public class LearnedFactRepository {
    private final JdbcClient jdbc;
    public LearnedFactRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    private ai.intentoms.learning.event.LearningEvents events;
    private ai.intentoms.platform.observability.Metrics metrics;

    /** GV-04 / NF-09: every written fact version is a {@code fact.learned} event and counts toward the facts-written
     * metric. Setter-injected so plain construction (tests) still works. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setEvents(ai.intentoms.learning.event.LearningEvents events, ai.intentoms.platform.observability.Metrics metrics) {
        this.events = events;
        this.metrics = metrics;
    }

    /** {@code PromotionService} writes its own {@code fact.learned} carrying the proposal id for promoted facts. */
    private static final String PROMOTION_WRITER = "promotion-service";

    private static final String COLS = "fact_id, subject_type, subject_id, fact_key, value::text as value, confidence, sample_size, learner, version, valid_from, valid_to, created_at";

    public void insert(LearnedFact f) {
        jdbc.sql("""
            insert into learned_fact(fact_id, tenant_id, subject_type, subject_id, fact_key, value, confidence, sample_size, learner, version, valid_from, valid_to, created_at)
            values (:id, :t, :st, :sid, :key, :val, :conf, :n, :learner, :v, :from, :to, :created)
            """)
            .param("id", f.factId()).param("t", TenantContext.require()).param("st", f.subjectType().name()).param("sid", f.subjectId()).param("key", f.factKey().name())
            .param("val", Jsonb.of(f.value())).param("conf", f.confidence()).param("n", f.sampleSize()).param("learner", f.learner()).param("v", f.version())
            .param("from", Timestamp.from(f.validFrom())).param("to", f.validTo() == null ? null : Timestamp.from(f.validTo())).param("created", Timestamp.from(f.createdAt()))
            .update();
        if (metrics != null) metrics.count("oms.learning.facts_written", "learner", String.valueOf(f.learner()), "key", f.factKey().name());
        if (events != null && !PROMOTION_WRITER.equals(f.learner())) {
            Map<String, Object> payload = new java.util.LinkedHashMap<>();
            payload.put("subjectType", f.subjectType().name());
            payload.put("subjectId", f.subjectId());
            payload.put("factKey", f.factKey().name());
            payload.put("version", f.version());
            payload.put("value", f.value());
            payload.put("confidence", f.confidence());
            payload.put("sampleSize", f.sampleSize());
            payload.put("learner", f.learner());
            events.factLearned(f.factId(), ai.intentoms.platform.ledger.Actor.system(String.valueOf(f.learner())), payload);
        }
    }

    @Transactional
    public void supersede(SubjectType subjectType, String subjectId, FactKey key, Instant at) {
        jdbc.sql("update learned_fact set valid_to = :at where tenant_id = :t and subject_type = :st and subject_id = :sid and fact_key = :key and valid_to is null")
            .param("at", Timestamp.from(at)).param("t", TenantContext.require()).param("st", subjectType.name()).param("sid", subjectId).param("key", key.name()).update();
    }

    public Optional<LearnedFact> active(SubjectType subjectType, String subjectId, FactKey key) {
        String t = TenantContext.require();
        return jdbc.sql("select " + COLS + " from learned_fact where tenant_id = :t and subject_type = :st and subject_id = :sid and fact_key = :key and valid_to is null order by version desc limit 1")
            .param("t", t).param("st", subjectType.name()).param("sid", subjectId).param("key", key.name()).query().listOfRows().stream().findFirst().map(LearnedFactRepository::hydrate);
    }

    public List<LearnedFact> activeForSubject(SubjectType subjectType, String subjectId) {
        String t = TenantContext.require();
        return jdbc.sql("select " + COLS + " from learned_fact where tenant_id = :t and subject_type = :st and subject_id = :sid and valid_to is null")
            .param("t", t).param("st", subjectType.name()).param("sid", subjectId).query().listOfRows().stream().map(LearnedFactRepository::hydrate).toList();
    }

    /** Every active fact for one key across all subjects of a type (CI-02 segmentation input, FD-07 drift sampling). */
    public List<LearnedFact> activeByKey(SubjectType subjectType, FactKey key, int limit) {
        String t = TenantContext.require();
        return jdbc.sql("select " + COLS + " from learned_fact where tenant_id = :t and subject_type = :st and fact_key = :key and valid_to is null order by created_at desc limit :lim")
            .param("t", t).param("st", subjectType.name()).param("key", key.name()).param("lim", limit).query().listOfRows().stream().map(LearnedFactRepository::hydrate).toList();
    }

    public List<LearnedFact> history(SubjectType subjectType, String subjectId, FactKey key, int limit) {
        String t = TenantContext.require();
        return jdbc.sql("select " + COLS + " from learned_fact where tenant_id = :t and subject_type = :st and subject_id = :sid and fact_key = :key order by version desc limit :lim")
            .param("t", t).param("st", subjectType.name()).param("sid", subjectId).param("key", key.name()).param("lim", limit).query().listOfRows().stream().map(LearnedFactRepository::hydrate).toList();
    }

    private static LearnedFact hydrate(Map<String, Object> r) {
        return new LearnedFact((String) r.get("fact_id"), SubjectType.valueOf((String) r.get("subject_type")), (String) r.get("subject_id"), FactKey.valueOf((String) r.get("fact_key")),
            Json.read((String) r.get("value"), TypedValue.class), (Double) r.get("confidence"), (Integer) r.get("sample_size"), (String) r.get("learner"), (Integer) r.get("version"),
            ts(r.get("valid_from")), ts(r.get("valid_to")), ts(r.get("created_at")));
    }

    private static Instant ts(Object o) { return o == null ? null : ((Timestamp) o).toInstant(); }
}
