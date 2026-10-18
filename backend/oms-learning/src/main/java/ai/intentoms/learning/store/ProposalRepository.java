package ai.intentoms.learning.store;

import ai.intentoms.learning.model.ArtifactRef;
import ai.intentoms.learning.model.PolicyChange;
import ai.intentoms.learning.model.PolicyChangeset;
import ai.intentoms.learning.model.Proposal;
import ai.intentoms.learning.model.ProposalStatus;
import ai.intentoms.learning.model.Review;
import ai.intentoms.platform.error.NotFoundException;
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

/** Persistence for {@code proposal} — the author LLM's or a learner's draft changeset (§6.2, §9.1). */
@Repository
public class ProposalRepository {
    private final JdbcClient jdbc;
    public ProposalRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    private static final String COLS = "proposal_id, target_ref, base_version, changeset::text as changeset, hypothesis_id, tier, status, " +
        "critic_review::text as critic_review, evaluation_report::text as evaluation_report, created_by, created_at, updated_at, evaluated_at, " +
        "approved_by, approved_at, promoted_at, rejected_reason, rejected_at, rolled_back_reason, rolled_back_at, motivation";

    public void insert(Proposal p) {
        jdbc.sql("""
            insert into proposal(proposal_id, tenant_id, target_ref, base_version, changeset, hypothesis_id, tier, status, created_by, created_at, updated_at, motivation)
            values (:id, :t, :target, :base, :changes, :hyp, :tier, :status, :by, :created, :updated, :motivation)
            """)
            .param("id", p.proposalId()).param("t", TenantContext.require()).param("target", p.changeset().target().toString()).param("base", p.changeset().baseVersion())
            .param("changes", Jsonb.of(Json.write(p.changeset().changes(), CHANGES))).param("hyp", p.hypothesisId()).param("tier", p.tier().name()).param("status", p.status().name())
            .param("by", p.createdBy()).param("created", Timestamp.from(p.createdAt())).param("updated", Timestamp.from(p.updatedAt()))
            .param("motivation", p.changeset().motivatedBy()).update();
    }

    public Proposal get(String id) { return find(id).orElseThrow(() -> new NotFoundException("Proposal " + id + " not found")); }

    public Optional<Proposal> find(String id) {
        String t = TenantContext.require();
        return jdbc.sql("select " + COLS + " from proposal where tenant_id = :t and proposal_id = :id").param("t", t).param("id", id)
            .query().listOfRows().stream().findFirst().map(ProposalRepository::hydrate);
    }

    public List<Proposal> byStatus(String status, int limit) {
        String t = TenantContext.require();
        return jdbc.sql("select " + COLS + " from proposal where tenant_id = :t and status = :s order by created_at desc limit :lim")
            .param("t", t).param("s", status).param("lim", limit).query().listOfRows().stream().map(ProposalRepository::hydrate).toList();
    }

    /** Statuses in which a proposal is still in flight; a learner must not stack a second proposal on the same target. */
    public static final List<String> OPEN_STATUSES = List.of("DRAFT", "EVALUATED", "APPROVED", "CANARY");

    public boolean existsOpenFor(String targetRef) {
        return jdbc.sql("select count(*) from proposal where tenant_id = :t and target_ref = :target and status in (:open)")
            .param("t", TenantContext.require()).param("target", targetRef).param("open", OPEN_STATUSES).query(Long.class).single() > 0;
    }

    public long countByStatus(String status) {
        return jdbc.sql("select count(*) from proposal where tenant_id = :t and status = :s")
            .param("t", TenantContext.require()).param("s", status).query(Long.class).single();
    }

    public void markEvaluated(String id, Map<String, Object> report, Instant at) {
        jdbc.sql("update proposal set status = 'EVALUATED', evaluation_report = :r, evaluated_at = :at, updated_at = :at where tenant_id = :t and proposal_id = :id")
            .param("r", Jsonb.of(report)).param("at", Timestamp.from(at)).param("t", TenantContext.require()).param("id", id).update();
    }

    public void setCriticReview(String id, Review review) {
        jdbc.sql("update proposal set critic_review = :r where tenant_id = :t and proposal_id = :id")
            .param("r", Jsonb.of(review)).param("t", TenantContext.require()).param("id", id).update();
    }

    public void approve(String id, String by, Instant at) {
        jdbc.sql("update proposal set status = 'APPROVED', approved_by = :by, approved_at = :at, updated_at = :at where tenant_id = :t and proposal_id = :id")
            .param("by", by).param("at", Timestamp.from(at)).param("t", TenantContext.require()).param("id", id).update();
    }

    public void reject(String id, String reason, Instant at) {
        jdbc.sql("update proposal set status = 'REJECTED', rejected_reason = :r, rejected_at = :at, updated_at = :at where tenant_id = :t and proposal_id = :id")
            .param("r", reason).param("at", Timestamp.from(at)).param("t", TenantContext.require()).param("id", id).update();
    }

    public void setStatus(String id, ProposalStatus status, Instant at) {
        jdbc.sql("update proposal set status = :s, updated_at = :at where tenant_id = :t and proposal_id = :id")
            .param("s", status.name()).param("at", Timestamp.from(at)).param("t", TenantContext.require()).param("id", id).update();
    }

    public void promote(String id, Instant at) {
        jdbc.sql("update proposal set status = 'PROMOTED', promoted_at = :at, updated_at = :at where tenant_id = :t and proposal_id = :id")
            .param("at", Timestamp.from(at)).param("t", TenantContext.require()).param("id", id).update();
    }

    public void rollback(String id, String reason, Instant at) {
        jdbc.sql("update proposal set status = 'ROLLED_BACK', rolled_back_reason = :r, rolled_back_at = :at, updated_at = :at where tenant_id = :t and proposal_id = :id")
            .param("r", reason).param("at", Timestamp.from(at)).param("t", TenantContext.require()).param("id", id).update();
    }

    /** Adds or replaces one section of the evaluation report (e.g. {@code shadow}, {@code canary}) without touching the rest. */
    public void mergeReport(String id, String section, Map<String, Object> value) {
        jdbc.sql("update proposal set evaluation_report = coalesce(evaluation_report, '{}'::jsonb) || jsonb_build_object(:k, :v::jsonb), updated_at = now() where tenant_id = :t and proposal_id = :id")
            .param("k", section).param("v", Json.write(value)).param("t", TenantContext.require()).param("id", id).update();
    }

    /** EX-07 / AC4: what a promotion replaced, written when it is applied and read back by rollback. */
    public void setRollbackSnapshot(String id, Map<String, Object> snapshot) {
        jdbc.sql("update proposal set rollback_snapshot = :s where tenant_id = :t and proposal_id = :id")
            .param("s", Jsonb.of(snapshot)).param("t", TenantContext.require()).param("id", id).update();
    }

    public Optional<Map<String, Object>> rollbackSnapshot(String id) {
        return jdbc.sql("select rollback_snapshot::text from proposal where tenant_id = :t and proposal_id = :id and rollback_snapshot is not null")
            .param("t", TenantContext.require()).param("id", id).query(String.class).optional().map(Json::readMap);
    }

    /** Proposals promoted since an instant, newest first (guardrail watch window, change-budget counting). */
    public List<Proposal> promotedSince(Instant since, int limit) {
        String t = TenantContext.require();
        return jdbc.sql("select " + COLS + " from proposal where tenant_id = :t and status = 'PROMOTED' and promoted_at >= :since order by promoted_at desc limit :lim")
            .param("t", t).param("since", Timestamp.from(since)).param("lim", limit).query().listOfRows().stream().map(ProposalRepository::hydrate).toList();
    }

    /** Proposals in any of these statuses created by an author, newest first (author quality metric). */
    public List<Proposal> byCreator(String createdBy, int limit) {
        String t = TenantContext.require();
        return jdbc.sql("select " + COLS + " from proposal where tenant_id = :t and created_by = :by order by created_at desc limit :lim")
            .param("t", t).param("by", createdBy).param("lim", limit).query().listOfRows().stream().map(ProposalRepository::hydrate).toList();
    }

    private static final com.fasterxml.jackson.core.type.TypeReference<List<PolicyChange>> CHANGES = new com.fasterxml.jackson.core.type.TypeReference<>() {};

    private static Proposal hydrate(Map<String, Object> r) {
        PolicyChangeset changeset = new PolicyChangeset(ArtifactRef.parse((String) r.get("target_ref")), (Integer) r.get("base_version"),
            Json.read((String) r.get("changeset"), CHANGES), r.get("motivation") != null ? (String) r.get("motivation") : (String) r.get("hypothesis_id"),
            PolicyChangeset.RiskTier.valueOf((String) r.get("tier")));
        String criticJson = (String) r.get("critic_review");
        return new Proposal((String) r.get("proposal_id"), changeset, (String) r.get("hypothesis_id"), PolicyChangeset.RiskTier.valueOf((String) r.get("tier")),
            ProposalStatus.valueOf((String) r.get("status")), criticJson == null ? null : Json.read(criticJson, Review.class),
            Json.readMap((String) r.get("evaluation_report")), (String) r.get("created_by"), ts(r.get("created_at")), ts(r.get("updated_at")), ts(r.get("evaluated_at")),
            (String) r.get("approved_by"), ts(r.get("approved_at")), ts(r.get("promoted_at")), (String) r.get("rejected_reason"), ts(r.get("rejected_at")),
            (String) r.get("rolled_back_reason"), ts(r.get("rolled_back_at")));
    }

    private static Instant ts(Object o) { return o == null ? null : ((Timestamp) o).toInstant(); }
}
