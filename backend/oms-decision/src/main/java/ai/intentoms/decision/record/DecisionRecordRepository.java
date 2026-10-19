package ai.intentoms.decision.record;

import ai.intentoms.decision.model.Answer;
import ai.intentoms.decision.model.Band;
import ai.intentoms.decision.model.DecisionRecord;
import ai.intentoms.decision.model.Layer;
import ai.intentoms.decision.model.Mode;
import ai.intentoms.platform.jdbc.Jsonb;
import ai.intentoms.platform.json.Json;
import ai.intentoms.platform.tenant.TenantContext;
import com.fasterxml.jackson.core.type.TypeReference;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * decision_record persistence (FR-DEC-06). Rows are inserted once and are append-only in the database (§9.1
 * decision_log, migration V405): a trigger refuses any change to the decision itself. What arrives later, overrides
 * (GR-13) and labels (FR-DEC-10), is appended to {@code decision_annotation} by {@link #annotate}, the one method that
 * also refreshes the record's labels and override columns, which are a read cache of those annotations.
 */
@Repository
public class DecisionRecordRepository {
    private static final TypeReference<Map<String, Answer>> ANSWERS = new TypeReference<>() {};
    private static final TypeReference<List<String>> STRINGS = new TypeReference<>() {};
    private final JdbcClient jdbc;

    public DecisionRecordRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    static final String COLS = "id, decision, version, mode, subject_type, subject_id, correlation_id, layer, band, action, value::text as value, answers::text as answers, confidence, provider, " +
        "model_version, input_hash, state::text as state, input::text as input, rules_fired::text as rules_fired, reason_codes::text as reason_codes, latency_ms, cost_usd, input_tokens, requires_human, " +
        "escalation_id, actor, details::text as details, overridden_value::text as overridden_value, override_reason, overridden_by, overridden_at, labels::text as labels, created_at, " +
        "context_features::text as context_features, arms::text as arms, chosen_arm, propensity, policy_version";

    static final RowMapper<DecisionRecord> MAPPER = (rs, i) -> new DecisionRecord(
        rs.getString("id"), rs.getString("decision"), rs.getInt("version"), Mode.valueOf(rs.getString("mode")), rs.getString("subject_type"), rs.getString("subject_id"), rs.getString("correlation_id"),
        Layer.valueOf(rs.getString("layer")), Band.valueOf(rs.getString("band")), rs.getString("action"), readValue(rs.getString("value")), Json.read(rs.getString("answers"), ANSWERS),
        rs.getDouble("confidence"), rs.getString("provider"), rs.getString("model_version"), rs.getString("input_hash"), Json.readMap(rs.getString("state")), Json.readMap(rs.getString("input")),
        Json.read(rs.getString("rules_fired"), STRINGS), Json.read(rs.getString("reason_codes"), STRINGS), rs.getLong("latency_ms"), rs.getDouble("cost_usd"), rs.getInt("input_tokens"),
        rs.getBoolean("requires_human"), rs.getString("escalation_id"), rs.getString("actor"), Json.readMap(rs.getString("details")), readValue(rs.getString("overridden_value")),
        rs.getString("override_reason"), rs.getString("overridden_by"), ts(rs.getTimestamp("overridden_at")), Json.readMap(rs.getString("labels")), rs.getTimestamp("created_at").toInstant(),
        rs.getString("context_features") == null ? Map.of() : Json.readMap(rs.getString("context_features")), rs.getString("arms") == null ? List.of() : Json.read(rs.getString("arms"), STRINGS),
        rs.getString("chosen_arm"), rs.getDouble("propensity"), rs.getObject("policy_version") == null ? null : rs.getInt("policy_version"));

    private static Object readValue(String json) { return json == null ? null : Json.read(json, Object.class); }
    private static Instant ts(Timestamp t) { return t == null ? null : t.toInstant(); }

    public void insert(DecisionRecord r) {
        jdbc.sql("""
            insert into decision_record(id, tenant_id, decision, version, mode, subject_type, subject_id, correlation_id, layer, band, action, value, answers, confidence, provider, model_version,
              input_hash, state, input, rules_fired, reason_codes, latency_ms, cost_usd, input_tokens, requires_human, escalation_id, actor, details, labels, created_at,
              context_features, arms, chosen_arm, propensity, policy_version)
            values (:id, :t, :d, :v, :mode, :st, :sid, :corr, :layer, :band, :action, :value, :answers, :conf, :prov, :model, :hash, :state, :input, :rules, :reasons, :lat, :cost, :tok, :human, :esc, :actor, :details, :labels, :created,
              :ctx, :arms, :chosen, :propensity, :pv)
            """)
            .param("id", r.id()).param("t", TenantContext.require()).param("d", r.decision()).param("v", r.version()).param("mode", r.mode().name()).param("st", r.subjectType())
            .param("sid", r.subjectId()).param("corr", r.correlationId()).param("layer", r.layer().name()).param("band", r.band().name()).param("action", r.action())
            .param("value", r.value() == null ? null : Jsonb.of(Json.write(r.value()))).param("answers", Jsonb.of(r.answers())).param("conf", r.confidence()).param("prov", r.provider()).param("model", r.modelVersion())
            .param("hash", r.inputHash()).param("state", Jsonb.of(r.state())).param("input", Jsonb.of(r.input())).param("rules", Jsonb.of(r.rulesFired())).param("reasons", Jsonb.of(r.reasonCodes()))
            .param("lat", r.latencyMs()).param("cost", r.costUsd()).param("tok", r.inputTokens()).param("human", r.requiresHuman()).param("esc", r.escalationId()).param("actor", r.actor())
            .param("details", Jsonb.of(r.details())).param("labels", Jsonb.of(r.labels() == null ? Map.of() : r.labels())).param("created", Timestamp.from(r.createdAt()))
            .param("ctx", Jsonb.of(r.contextFeatures() == null ? Map.of() : r.contextFeatures())).param("arms", Jsonb.of(r.arms() == null ? List.of() : r.arms()))
            .param("chosen", r.chosenArm()).param("propensity", r.propensity()).param("pv", r.policyVersion())
            .update();
    }

    public Optional<DecisionRecord> find(String id) {
        return jdbc.sql("select " + COLS + " from decision_record where tenant_id = :t and id = :id").param("t", TenantContext.require()).param("id", id).query(MAPPER).optional();
    }

    public List<DecisionRecord> forSubject(String subjectType, String subjectId) {
        return jdbc.sql("select " + COLS + " from decision_record where tenant_id = :t and subject_type = :st and subject_id = :sid order by created_at desc")
            .param("t", TenantContext.require()).param("st", subjectType).param("sid", subjectId).query(MAPPER).list();
    }

    /** Recent LIVE records of one definition version with their inputs (impact diffs, FR-PRM-03). */
    public List<DecisionRecord> recentLive(String decision, int version, Instant since, int limit) {
        return jdbc.sql("select " + COLS + " from decision_record where tenant_id = :t and decision = :d and version = :v and mode = 'LIVE' and created_at >= :since order by created_at desc limit :lim")
            .param("t", TenantContext.require()).param("d", decision).param("v", version).param("since", Timestamp.from(since)).param("lim", limit).query(MAPPER).list();
    }

    /** Records with at least one label in a window (calibration monitoring, GR-12). */
    public List<DecisionRecord> labelledBetween(String decision, int version, Instant from, Instant to) {
        return jdbc.sql("select " + COLS + " from decision_record where tenant_id = :t and decision = :d and version = :v and mode = 'LIVE' and labels <> '{}'::jsonb and created_at >= :f and created_at < :to order by created_at")
            .param("t", TenantContext.require()).param("d", decision).param("v", version).param("f", Timestamp.from(from)).param("to", Timestamp.from(to)).query(MAPPER).list();
    }

    public Map<String, Integer> bandMixBetween(String decision, int version, Instant from, Instant to) {
        var rows = jdbc.sql("select band, count(*) as n from decision_record where tenant_id = :t and decision = :d and version = :v and mode = 'LIVE' and created_at >= :f and created_at < :to group by band")
            .param("t", TenantContext.require()).param("d", decision).param("v", version).param("f", Timestamp.from(from)).param("to", Timestamp.from(to)).query().listOfRows();
        Map<String, Integer> out = new java.util.LinkedHashMap<>();
        for (Band b : Band.values()) out.put(b.name(), 0);
        rows.forEach(r -> out.put((String) r.get("band"), ((Number) r.get("n")).intValue()));
        return out;
    }

    /** Distinct (decision, version) pairs with LIVE records in a window. */
    public List<int[]> activeVersions(String decision, Instant from) {
        return jdbc.sql("select distinct version from decision_record where tenant_id = :t and decision = :d and mode = 'LIVE' and created_at >= :f order by version")
            .param("t", TenantContext.require()).param("d", decision).param("f", Timestamp.from(from)).query((rs, i) -> new int[]{rs.getInt("version")}).list();
    }

    public List<String> decisionsWithRecords(Instant since) {
        return jdbc.sql("select distinct decision from decision_record where tenant_id = :t and created_at >= :f order by decision")
            .param("t", TenantContext.require()).param("f", Timestamp.from(since)).query(String.class).list();
    }

    public List<String> tenantsWithRecords(Instant since) {
        return jdbc.sql("select distinct tenant_id from decision_record where created_at >= :f order by tenant_id").param("f", Timestamp.from(since)).query(String.class).list();
    }

    /** A person replaced the decision's value (GR-13): appended as an override annotation. */
    public void override(String id, Object newValue, String reason, String by, Instant at) {
        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("value", newValue);
        payload.put("reason", reason);
        payload.put("by", by);
        payload.put("at", at == null ? null : at.toString());
        annotate(id, "override", "override", payload, by, at,
            "overridden_value = :v, override_reason = :r, overridden_by = :by, overridden_at = :at",
            Map.of("v", Jsonb.of(Json.write(newValue)), "r", reason == null ? "" : reason, "by", by == null ? "" : by, "at", Timestamp.from(at == null ? Instant.now() : at)));
    }

    /** A label (FR-DEC-10): appended as a label annotation; the record's labels cache merges it. */
    public void label(String id, String label, Map<String, Object> details) {
        Map<String, Object> payload = details == null ? Map.of() : details;
        Object by = payload.get("resolvedBy") != null ? payload.get("resolvedBy") : payload.get("by");
        annotate(id, "label", label, payload, by == null ? null : String.valueOf(by), Instant.now(), "labels = labels || :l", Map.of("l", Jsonb.of(Map.of(label, payload))));
    }

    /**
     * The one write path after insert (§9.1 append-only): appends the annotation and refreshes the record's cache
     * columns in the same statement. The statement sets {@code oms.decision_annotation} for its own transaction first,
     * which is what the decision_record trigger lets through, and only for the cache columns.
     */
    private void annotate(String id, String kind, String name, Map<String, Object> payload, String by, Instant at, String cacheSet, Map<String, Object> cacheParams) {
        var spec = jdbc.sql("""
            with allow as (select set_config('oms.decision_annotation', 'on', true) as on_),
                 ann as (insert into decision_annotation(annotation_id, tenant_id, decision_record_id, kind, name, payload, created_by, created_at)
                         select :aid, :t, :id, :kind, :name, :payload, :createdBy, :createdAt
                         where exists (select 1 from decision_record where tenant_id = :t and id = :id) returning 1)
            update decision_record set %s from allow where tenant_id = :t and id = :id and allow.on_ = 'on'
            """.formatted(cacheSet))
            .param("aid", ai.intentoms.platform.ids.Ids.prefixed("ann")).param("t", TenantContext.require()).param("id", id).param("kind", kind).param("name", name)
            .param("payload", Jsonb.of(payload)).param("createdBy", by).param("createdAt", Timestamp.from(at == null ? Instant.now() : at));
        for (Map.Entry<String, Object> e : cacheParams.entrySet()) spec = spec.param(e.getKey(), e.getValue());
        spec.update();
    }

    /** Every annotation of one record, oldest first (labels, overrides and human resolutions as they were appended). */
    public List<Map<String, Object>> annotations(String id) {
        return jdbc.sql("select annotation_id, kind, name, payload::text as payload, created_by, created_at from decision_annotation where tenant_id = :t and decision_record_id = :id order by created_at, annotation_id")
            .param("t", TenantContext.require()).param("id", id).query().listOfRows().stream().map(r -> {
                Map<String, Object> m = new java.util.LinkedHashMap<>(r);
                m.put("payload", Json.readMap((String) r.get("payload")));
                return m;
            }).toList();
    }
}
