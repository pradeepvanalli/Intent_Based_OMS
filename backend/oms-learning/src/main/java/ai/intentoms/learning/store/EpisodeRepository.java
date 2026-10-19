package ai.intentoms.learning.store;

import ai.intentoms.learning.model.Episode;
import ai.intentoms.learning.model.EpisodeStatus;
import ai.intentoms.platform.error.NotFoundException;
import ai.intentoms.platform.jdbc.Jsonb;
import ai.intentoms.platform.json.Json;
import ai.intentoms.platform.tenant.TenantContext;
import com.fasterxml.jackson.core.type.TypeReference;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Persistence for {@code episode} (OC-02): one row per decision point, see {@link ai.intentoms.learning.model.DecisionPoints}. */
@Repository
public class EpisodeRepository {
    private static final TypeReference<Map<String, Double>> REWARD_MAP = new TypeReference<>() {};
    private final JdbcClient jdbc;
    public EpisodeRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    private static final String COLS = "episode_id, decision_id, decision_name, order_id, line_id, fulfillment_group_id, status, reward, " +
        "reward_components::text as reward_components, policy_version, created_at, matured_at, subject_type, subject_id, last_decision_id, evaluation_count";

    /** Exactly the first evaluation of a decision point inserts; a later one moves {@code last_decision_id} and counts
     * it. Returns true when a new episode was opened. A redelivered event for the latest record changes nothing. */
    @Transactional
    public boolean upsertDecisionPoint(Episode e) {
        int inserted = jdbc.sql("""
            insert into episode(episode_id, tenant_id, decision_id, decision_name, order_id, line_id, fulfillment_group_id, status, reward, reward_components,
                                policy_version, created_at, matured_at, subject_type, subject_id, last_decision_id, evaluation_count, decision_point_key)
            values (:id, :t, :did, :dname, :order, :line, :fg, :status, :reward, :comp, :pv, :created, :matured, :st, :sid, :did, 1, :key)
            on conflict (tenant_id, decision_point_key) do nothing
            """)
            .param("id", e.episodeId()).param("t", TenantContext.require()).param("did", e.decisionId()).param("dname", e.decisionName())
            .param("order", e.orderId()).param("line", e.lineId()).param("fg", e.fulfillmentGroupId()).param("status", e.status().name())
            .param("reward", e.reward()).param("comp", Jsonb.of(e.rewardComponents() == null ? Map.of() : e.rewardComponents())).param("pv", e.policyVersion())
            .param("created", Timestamp.from(e.createdAt())).param("matured", e.maturedAt() == null ? null : Timestamp.from(e.maturedAt()))
            .param("st", e.subjectType()).param("sid", e.subjectId()).param("key", e.decisionPointKey())
            .update();
        if (inserted == 1) return true;
        jdbc.sql("""
            update episode set last_decision_id = :did, evaluation_count = evaluation_count + 1
            where tenant_id = :t and decision_point_key = :key and decision_id <> :did and last_decision_id is distinct from :did
            """).param("did", e.decisionId()).param("t", TenantContext.require()).param("key", e.decisionPointKey()).update();
        return false;
    }

    /** Plain insert for callers that build an episode outright (tests, reference data). Idempotent on the decision point. */
    public Episode insert(Episode e) {
        upsertDecisionPoint(e);
        return e;
    }

    public Optional<Episode> find(String episodeId) {
        return one("select " + COLS + " from episode where tenant_id = :t and episode_id = :id", Map.of("id", episodeId));
    }

    /** The episode a decision record belongs to: its own (as anchor) or the one whose decision point it re-evaluated. */
    public Optional<Episode> findByDecisionId(String decisionId) {
        return one("select " + COLS + " from episode where tenant_id = :t and (decision_id = :did or last_decision_id = :did) limit 1", Map.of("did", decisionId));
    }

    public Optional<Episode> findByDecisionPoint(String decisionPointKey) {
        return one("select " + COLS + " from episode where tenant_id = :t and decision_point_key = :k", Map.of("k", decisionPointKey));
    }

    public List<Episode> forOrder(String orderId) {
        return many("select " + COLS + " from episode where tenant_id = :t and order_id = :o order by created_at", Map.of("o", orderId));
    }

    /** Episodes whose decision was about this subject (a return, a mandate…), for outcomes that carry no order id. */
    public List<Episode> forSubject(String subjectType, String subjectId) {
        return many("select " + COLS + " from episode where tenant_id = :t and subject_type = :st and subject_id = :sid order by created_at",
            Map.of("st", subjectType, "sid", subjectId));
    }

    public List<Episode> forDecisionSince(String decisionName, Instant since, int limit) {
        return many("select " + COLS + " from episode where tenant_id = :t and decision_name = :d and created_at >= :since order by created_at desc limit :lim",
            Map.of("d", decisionName, "since", Timestamp.from(since), "lim", limit));
    }

    /**
     * Episodes that need their reward (re)computed: open or provisional, and either an outcome arrived since the last
     * computation or the longest maturity window has passed. Newest work first is never starved by old idle rows,
     * because idle rows simply don't match.
     */
    public List<Episode> dueForReward(Instant matureBefore, int limit) {
        return many("""
            select %s from episode e
            where e.tenant_id = :t and e.status in ('OPEN','PROVISIONAL')
              and (e.created_at < :mature
                   or e.outcome_version > e.reward_outcome_version
                   or exists (select 1 from outcome o where o.tenant_id = e.tenant_id and o.episode_id = e.episode_id
                              and (e.reward_computed_at is null or o.created_at > e.reward_computed_at)))
            order by e.created_at limit :lim
            """.formatted(COLS.replace("episode_id,", "e.episode_id,")), Map.of("mature", Timestamp.from(matureBefore), "lim", limit));
    }

    /** OC-07: of the episodes old enough that an outcome should have arrived, how many have at least one. */
    public long[] coverage(String decisionName, Instant since, Instant observedBefore) {
        var row = jdbc.sql("""
            select count(*) as eligible,
                   count(*) filter (where exists (select 1 from outcome o where o.tenant_id = e.tenant_id and o.episode_id = e.episode_id)) as covered
            from episode e
            where e.tenant_id = :t and e.decision_name = :d and e.created_at >= :since and e.created_at < :obs
            """).param("t", TenantContext.require()).param("d", decisionName).param("since", Timestamp.from(since)).param("obs", Timestamp.from(observedBefore))
            .query().singleRow();
        return new long[]{((Number) row.get("eligible")).longValue(), ((Number) row.get("covered")).longValue()};
    }

    public long countSince(String decisionName, Instant since) {
        return jdbc.sql("select count(*) from episode where tenant_id = :t and decision_name = :d and created_at >= :since")
            .param("t", TenantContext.require()).param("d", decisionName).param("since", Timestamp.from(since)).query(Long.class).single();
    }

    @Transactional
    public void updateStatusAndReward(String episodeId, EpisodeStatus status, Double reward, Map<String, Double> components, Instant maturedAt) {
        updateStatusAndReward(episodeId, status, reward, components, maturedAt, null);
    }

    /** As above, recording the outcome version the reward was computed from (read before the outcomes). */
    @Transactional
    public void updateStatusAndReward(String episodeId, EpisodeStatus status, Double reward, Map<String, Double> components, Instant maturedAt, Long outcomeVersion) {
        int n = jdbc.sql("""
            update episode set status = :s, reward = :r, reward_components = :c, matured_at = :m, reward_computed_at = now(),
                   reward_outcome_version = coalesce(:v, reward_outcome_version) where tenant_id = :t and episode_id = :id
            """)
            .param("s", status.name()).param("r", reward).param("c", Jsonb.of(components == null ? Map.of() : components))
            .param("m", maturedAt == null ? null : Timestamp.from(maturedAt)).param("v", outcomeVersion).param("t", TenantContext.require()).param("id", episodeId).update();
        if (n == 0) throw new NotFoundException("Episode " + episodeId + " not found");
    }

    /** An outcome of the episode was written (inserted or merged): its reward is due again, whenever that write commits. */
    public void outcomeChanged(String episodeId) {
        jdbc.sql("update episode set outcome_version = outcome_version + 1 where tenant_id = :t and episode_id = :id")
            .param("t", TenantContext.require()).param("id", episodeId).update();
    }

    /** The episode's outcome version as committed now; read before the outcomes a reward is computed from. */
    public long outcomeVersion(String episodeId) {
        return jdbc.sql("select outcome_version from episode where tenant_id = :t and episode_id = :id")
            .param("t", TenantContext.require()).param("id", episodeId).query(Long.class).optional().orElse(0L);
    }

    private Optional<Episode> one(String sql, Map<String, Object> params) { return many(sql, params).stream().findFirst(); }

    private List<Episode> many(String sql, Map<String, Object> params) {
        var spec = jdbc.sql(sql).param("t", TenantContext.require());
        for (var p : params.entrySet()) spec = spec.param(p.getKey(), p.getValue());
        return spec.query().listOfRows().stream().map(EpisodeRepository::hydrate).toList();
    }

    private static Episode hydrate(Map<String, Object> r) {
        String comp = (String) r.get("reward_components");
        Number count = (Number) r.get("evaluation_count");
        return new Episode((String) r.get("episode_id"), (String) r.get("decision_id"), (String) r.get("decision_name"), (String) r.get("order_id"), (String) r.get("line_id"),
            (String) r.get("fulfillment_group_id"), EpisodeStatus.valueOf((String) r.get("status")), (Double) r.get("reward"),
            comp == null || comp.isBlank() ? Map.of() : Json.read(comp, REWARD_MAP), (Integer) r.get("policy_version"), ts(r.get("created_at")), ts(r.get("matured_at")),
            (String) r.get("subject_type"), (String) r.get("subject_id"), (String) r.get("last_decision_id"), count == null ? 1 : count.intValue());
    }

    private static Instant ts(Object o) { return o == null ? null : ((Timestamp) o).toInstant(); }
}
