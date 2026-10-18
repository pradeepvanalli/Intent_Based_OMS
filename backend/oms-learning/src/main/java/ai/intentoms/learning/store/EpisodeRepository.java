package ai.intentoms.learning.store;

import ai.intentoms.learning.model.Episode;
import ai.intentoms.learning.model.EpisodeStatus;
import ai.intentoms.platform.error.NotFoundException;
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

/** Persistence for {@code episode} (OC-02). One row per decision point; the assembler inserts once, then updates
 * status/reward as outcomes land and mature. */
@Repository
public class EpisodeRepository {
    private final JdbcClient jdbc;
    public EpisodeRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    private static final String COLS = "episode_id, decision_id, decision_name, order_id, line_id, fulfillment_group_id, status, reward, " +
        "reward_components::text as reward_components, policy_version, created_at, matured_at";

    @Transactional
    public Episode insert(Episode e) {
        jdbc.sql("""
            insert into episode(episode_id, tenant_id, decision_id, decision_name, order_id, line_id, fulfillment_group_id, status, reward, reward_components, policy_version, created_at, matured_at)
            values (:id, :t, :did, :dname, :order, :line, :fg, :status, :reward, :comp, :pv, :created, :matured)
            on conflict (tenant_id, decision_id) do nothing
            """)
            .param("id", e.episodeId()).param("t", TenantContext.require()).param("did", e.decisionId()).param("dname", e.decisionName())
            .param("order", e.orderId()).param("line", e.lineId()).param("fg", e.fulfillmentGroupId()).param("status", e.status().name())
            .param("reward", e.reward()).param("comp", Jsonb.of(e.rewardComponents() == null ? Map.of() : e.rewardComponents())).param("pv", e.policyVersion())
            .param("created", Timestamp.from(e.createdAt())).param("matured", e.maturedAt() == null ? null : Timestamp.from(e.maturedAt()))
            .update();
        return e;
    }

    public Optional<Episode> find(String episodeId) {
        String t = TenantContext.require();
        return jdbc.sql("select " + COLS + " from episode where tenant_id = :t and episode_id = :id").param("t", t).param("id", episodeId).query().listOfRows().stream().findFirst().map(EpisodeRepository::hydrate);
    }

    public Optional<Episode> findByDecisionId(String decisionId) {
        String t = TenantContext.require();
        return jdbc.sql("select " + COLS + " from episode where tenant_id = :t and decision_id = :did").param("t", t).param("did", decisionId).query().listOfRows().stream().findFirst().map(EpisodeRepository::hydrate);
    }

    public List<Episode> openOrProvisional(int limit) {
        String t = TenantContext.require();
        return jdbc.sql("select " + COLS + " from episode where tenant_id = :t and status in ('OPEN','PROVISIONAL') order by created_at limit :lim")
            .param("t", t).param("lim", limit).query().listOfRows().stream().map(EpisodeRepository::hydrate).toList();
    }

    public List<Episode> forOrder(String orderId) {
        String t = TenantContext.require();
        return jdbc.sql("select " + COLS + " from episode where tenant_id = :t and order_id = :o order by created_at").param("t", t).param("o", orderId)
            .query().listOfRows().stream().map(EpisodeRepository::hydrate).toList();
    }

    public List<Episode> forDecisionSince(String decisionName, Instant since, int limit) {
        String t = TenantContext.require();
        return jdbc.sql("select " + COLS + " from episode where tenant_id = :t and decision_name = :d and created_at >= :since order by created_at desc limit :lim")
            .param("t", t).param("d", decisionName).param("since", Timestamp.from(since)).param("lim", limit).query().listOfRows().stream().map(EpisodeRepository::hydrate).toList();
    }

    /** OC-07 coverage: episodes vs. episodes that ever received at least one outcome, in a window. */
    public long countSince(String decisionName, Instant since) {
        String t = TenantContext.require();
        return jdbc.sql("select count(*) from episode where tenant_id = :t and decision_name = :d and created_at >= :since")
            .param("t", t).param("d", decisionName).param("since", Timestamp.from(since)).query(Long.class).single();
    }

    @Transactional
    public void updateStatusAndReward(String episodeId, EpisodeStatus status, Double reward, Map<String, Double> components, Instant maturedAt) {
        String t = TenantContext.require();
        int n = jdbc.sql("update episode set status = :s, reward = :r, reward_components = :c, matured_at = :m where tenant_id = :t and episode_id = :id")
            .param("s", status.name()).param("r", reward).param("c", Jsonb.of(components == null ? Map.of() : components))
            .param("m", maturedAt == null ? null : Timestamp.from(maturedAt)).param("t", t).param("id", episodeId).update();
        if (n == 0) throw new NotFoundException("Episode " + episodeId + " not found");
    }

    private static final com.fasterxml.jackson.core.type.TypeReference<Map<String, Double>> REWARD_MAP = new com.fasterxml.jackson.core.type.TypeReference<>() {};

    private static Episode hydrate(Map<String, Object> r) {
        String comp = (String) r.get("reward_components");
        return new Episode((String) r.get("episode_id"), (String) r.get("decision_id"), (String) r.get("decision_name"), (String) r.get("order_id"), (String) r.get("line_id"),
            (String) r.get("fulfillment_group_id"), EpisodeStatus.valueOf((String) r.get("status")), (Double) r.get("reward"),
            comp == null || comp.isBlank() ? Map.of() : Json.read(comp, REWARD_MAP), (Integer) r.get("policy_version"), ts(r.get("created_at")), ts(r.get("matured_at")));
    }

    private static Instant ts(Object o) { return o == null ? null : ((Timestamp) o).toInstant(); }
}
