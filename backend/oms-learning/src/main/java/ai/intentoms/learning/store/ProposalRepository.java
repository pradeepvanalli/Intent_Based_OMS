package ai.intentoms.learning.store;

import ai.intentoms.learning.model.ArtifactRef;
import ai.intentoms.learning.model.PolicyChange;
import ai.intentoms.learning.model.PolicyChangeset;
import ai.intentoms.learning.model.Proposal;
import ai.intentoms.learning.model.ProposalLifecycle;
import ai.intentoms.learning.model.ProposalStatus;
import ai.intentoms.learning.model.Review;
import ai.intentoms.platform.error.NotFoundException;
import ai.intentoms.platform.error.OmsException;
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
import java.util.Set;

/** Persistence for {@code proposal} — the author LLM's or a learner's draft changeset (§6.2, §9.1). */
@Repository
public class ProposalRepository {
    private final JdbcClient jdbc;
    public ProposalRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    private ai.intentoms.learning.event.LearningEvents events;

    /** GV-04: every inserted proposal is recorded as {@code proposal.drafted}. Setter-injected so plain construction
     * (tests) still works; without it no event is written. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setEvents(ai.intentoms.learning.event.LearningEvents events) { this.events = events; }

    private ai.intentoms.platform.config.TenantConfigStore config;

    /** Reads {@code learning.rollback_cooldown_days}; without it the default of 30 days applies. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setConfig(ai.intentoms.platform.config.TenantConfigStore config) { this.config = config; }

    public static final String COOLDOWN_KEY = "learning.rollback_cooldown_days";
    static final int DEFAULT_COOLDOWN_DAYS = 30;
    /** Report sections other steps add (shadow and canary verdicts, a promotion override); re-evaluation keeps them. */
    static final List<String> KEPT_SECTIONS = List.of("shadow", "canary", "override");

    private static final String COLS = "proposal_id, target_ref, base_version, changeset::text as changeset, hypothesis_id, tier, status, " +
        "critic_review::text as critic_review, evaluation_report::text as evaluation_report, created_by, created_at, updated_at, evaluated_at, " +
        "approved_by, approved_at, promoted_at, rejected_reason, rejected_at, rolled_back_reason, rolled_back_at, motivation";

    /**
     * Stores a new proposal. Defect 26 / §5: the same change on the same target that was rolled back within the
     * cooldown ({@code learning.rollback_cooldown_days}, default 30) is refused, since the rollback is evidence against
     * it; a learner that drafts it again gets a 409 it logs and moves on from.
     */
    /** "live use of sourcing.reject_penalty", "the sla_breach_risk decision", "the sourcing.weights setting". */
    static String plainTarget(ArtifactRef target) {
        String name = target.name() == null ? "" : target.name();
        return switch (target.type()) {
            case "parameter" -> name.startsWith("live:") ? "live use of " + name.substring("live:".length()) : "the learned value " + name;
            case "decision" -> "the " + name + " decision";
            case "config" -> "the " + name + " setting";
            default -> name;
        };
    }

    public void insert(Proposal p) {
        String target = p.changeset().target().toString();
        int days = config == null ? DEFAULT_COOLDOWN_DAYS : Math.max(0, config.getInt(COOLDOWN_KEY, DEFAULT_COOLDOWN_DAYS));
        if (days > 0) {
            String fp = fingerprint(target, p.changeset().changes());
            // N4: a precautionary switch-off of live use is no evidence against the change, so it only holds learners back.
            boolean byLearner = p.createdBy() != null && p.createdBy().startsWith("system:");
            Set<String> forCause = rolledBackForCauseSince(target, Instant.now().minus(java.time.Duration.ofDays(days)));
            for (Proposal rb : rolledBackSince(target, Instant.now().minus(java.time.Duration.ofDays(days)))) {
                if (!byLearner && !forCause.contains(rb.proposalId())) continue;
                if (fp.equals(fingerprint(target, rb.changeset().changes()))) {
                    String when = rb.rolledBackAt() == null ? "recently" : "on " + rb.rolledBackAt().toString().substring(0, 10);
                    String why = rb.rolledBackReason() == null ? "no reason given" : rb.rolledBackReason();
                    throw new OmsException(org.springframework.http.HttpStatus.CONFLICT, "proposal_cooling_down",
                        "The same change to " + plainTarget(p.changeset().target()) + " was rolled back " + when + " (" + why + "); it cannot be proposed again for " + days
                            + " days without different evidence", Map.of("rolledBackProposalId", rb.proposalId(), "cooldownDays", days, "target", target), null);
                }
            }
        }
        jdbc.sql("""
            insert into proposal(proposal_id, tenant_id, target_ref, base_version, changeset, hypothesis_id, tier, status, created_by, created_at, updated_at, motivation)
            values (:id, :t, :target, :base, :changes, :hyp, :tier, :status, :by, :created, :updated, :motivation)
            """)
            .param("id", p.proposalId()).param("t", TenantContext.require()).param("target", p.changeset().target().toString()).param("base", p.changeset().baseVersion())
            .param("changes", Jsonb.of(Json.write(p.changeset().changes(), CHANGES))).param("hyp", p.hypothesisId()).param("tier", p.tier().name()).param("status", p.status().name())
            .param("by", p.createdBy()).param("created", Timestamp.from(p.createdAt())).param("updated", Timestamp.from(p.updatedAt()))
            .param("motivation", p.changeset().motivatedBy()).update();
        if (events != null) {
            Map<String, Object> payload = new java.util.LinkedHashMap<>();
            payload.put("target", p.changeset().target().toString());
            payload.put("tier", p.tier().name());
            payload.put("hypothesisId", p.hypothesisId());
            payload.put("changeTypes", p.changeset().changes().stream().map(c -> c.getClass().getSimpleName()).toList());
            payload.put("createdBy", p.createdBy());
            events.proposalDrafted(p.proposalId(), ai.intentoms.platform.ledger.Actor.parse(p.createdBy()), payload);
        }
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

    /**
     * Writes a replay report and moves the proposal to EVALUATED (allowed only from DRAFT or EVALUATED). The new replay
     * replaces the old one, but the shadow and canary verdicts and any promotion override already on the report are
     * kept: re-testing on past decisions must never erase a shadow run the promotion gate depends on.
     */
    public void markEvaluated(String id, Map<String, Object> report, Instant at) {
        int n = jdbc.sql("""
            update proposal set status = 'EVALUATED', evaluated_at = :at, updated_at = :at,
              evaluation_report = cast(:r as jsonb) || coalesce((select jsonb_object_agg(e.key, e.value) from jsonb_each(coalesce(evaluation_report, '{}'::jsonb)) e
                                                                 where e.key in (:kept)), '{}'::jsonb)
            where tenant_id = :t and proposal_id = :id and status in (:from)
            """)
            .param("r", Json.write(report)).param("at", Timestamp.from(at)).param("kept", KEPT_SECTIONS).param("t", TenantContext.require()).param("id", id)
            .param("from", ProposalLifecycle.sourcesOf(ProposalStatus.EVALUATED)).update();
        if (n == 0) throw refused(id, "re-test");
    }

    public void setCriticReview(String id, Review review) {
        jdbc.sql("update proposal set critic_review = :r where tenant_id = :t and proposal_id = :id")
            .param("r", Jsonb.of(review)).param("t", TenantContext.require()).param("id", id).update();
    }

    public void approve(String id, String by, Instant at) {
        int n = jdbc.sql("update proposal set status = 'APPROVED', approved_by = :by, approved_at = :at, updated_at = :at where tenant_id = :t and proposal_id = :id and status in (:from)")
            .param("by", by).param("at", Timestamp.from(at)).param("t", TenantContext.require()).param("id", id)
            .param("from", ProposalLifecycle.sourcesOf(ProposalStatus.APPROVED)).update();
        if (n == 0) throw refused(id, "approve");
    }

    public void reject(String id, String reason, Instant at) {
        int n = jdbc.sql("update proposal set status = 'REJECTED', rejected_reason = :r, rejected_at = :at, updated_at = :at where tenant_id = :t and proposal_id = :id and status in (:from)")
            .param("r", reason).param("at", Timestamp.from(at)).param("t", TenantContext.require()).param("id", id)
            .param("from", ProposalLifecycle.sourcesOf(ProposalStatus.REJECTED)).update();
        if (n == 0) throw refused(id, "reject");
    }

    public void setStatus(String id, ProposalStatus status, Instant at) {
        int n = jdbc.sql("update proposal set status = :s, updated_at = :at where tenant_id = :t and proposal_id = :id and status in (:from)")
            .param("s", status.name()).param("at", Timestamp.from(at)).param("t", TenantContext.require()).param("id", id)
            .param("from", ProposalLifecycle.sourcesOf(status)).update();
        if (n == 0) throw refused(id, "move it to " + status.name().toLowerCase(java.util.Locale.ROOT));
    }

    public void promote(String id, Instant at) {
        int n = jdbc.sql("update proposal set status = 'PROMOTED', promoted_at = :at, updated_at = :at where tenant_id = :t and proposal_id = :id and status in (:from)")
            .param("at", Timestamp.from(at)).param("t", TenantContext.require()).param("id", id)
            .param("from", ProposalLifecycle.sourcesOf(ProposalStatus.PROMOTED)).update();
        if (n == 0) throw refused(id, "promote");
    }

    /** A rollback for cause: a guardrail breach, a failed trial, or a person rolling the change back. */
    public void rollback(String id, String reason, Instant at) { rollback(id, reason, at, true); }

    /**
     * N4: {@code forCause} false for a precautionary switch-off (a person revoking live use at once): people may propose
     * the same change again straight away, while learners still wait out the cooldown.
     */
    public void rollback(String id, String reason, Instant at, boolean forCause) {
        int n = jdbc.sql("update proposal set status = 'ROLLED_BACK', rolled_back_reason = :r, rolled_back_at = :at, rolled_back_for_cause = :cause, updated_at = :at " +
                "where tenant_id = :t and proposal_id = :id and status in (:from)")
            .param("r", reason).param("at", Timestamp.from(at)).param("cause", forCause).param("t", TenantContext.require()).param("id", id)
            .param("from", ProposalLifecycle.sourcesOf(ProposalStatus.ROLLED_BACK)).update();
        if (n == 0) throw refused(id, "roll back");
    }

    /** Ids of proposals on this target rolled back for cause since an instant (N4). */
    public Set<String> rolledBackForCauseSince(String targetRef, Instant since) {
        return new java.util.HashSet<>(jdbc.sql("select proposal_id from proposal where tenant_id = :t and target_ref = :target and status = 'ROLLED_BACK' and rolled_back_for_cause and rolled_back_at >= :since")
            .param("t", TenantContext.require()).param("target", targetRef).param("since", Timestamp.from(since)).query(String.class).list());
    }

    /** 404 when the proposal does not exist, else the lifecycle's 409 for its current status. */
    private RuntimeException refused(String id, String action) {
        Proposal p = get(id);
        return ProposalLifecycle.refused(id, p.status(), action);
    }

    /** The human override recorded when a person promoted a change the gate would have held back (EX-07, GV-02). */
    public void setPromotionNote(String id, Map<String, Object> note) {
        jdbc.sql("update proposal set promotion_note = :n where tenant_id = :t and proposal_id = :id")
            .param("n", Jsonb.of(note)).param("t", TenantContext.require()).param("id", id).update();
    }

    public Optional<Map<String, Object>> promotionNote(String id) {
        return jdbc.sql("select promotion_note::text from proposal where tenant_id = :t and proposal_id = :id and promotion_note is not null")
            .param("t", TenantContext.require()).param("id", id).query(String.class).optional().map(Json::readMap);
    }

    /** GV-02: how many times this target was promoted since an instant, whatever happened to those changes since. */
    public int promotionsOfTargetSince(String targetRef, Instant since) {
        return jdbc.sql("select count(*) from proposal where tenant_id = :t and target_ref = :target and promoted_at >= :since")
            .param("t", TenantContext.require()).param("target", targetRef).param("since", Timestamp.from(since)).query(Long.class).single().intValue();
    }

    /** Proposals on this target rolled back since an instant (defect 26 cooldown). */
    public List<Proposal> rolledBackSince(String targetRef, Instant since) {
        return jdbc.sql("select " + COLS + " from proposal where tenant_id = :t and target_ref = :target and status = 'ROLLED_BACK' and rolled_back_at >= :since order by rolled_back_at desc")
            .param("t", TenantContext.require()).param("target", targetRef).param("since", Timestamp.from(since)).query().listOfRows().stream().map(ProposalRepository::hydrate).toList();
    }

    private static final com.fasterxml.jackson.core.type.TypeReference<PolicyChange> ONE_CHANGE = new com.fasterxml.jackson.core.type.TypeReference<>() {};

    /**
     * Same target and same changes, whatever order they were listed in: SHA-256 over a canonical form of each change.
     * A change's {@code from} is left out: what was rolled back is where the change took the target, and the same
     * destination drafted from a slightly different starting point (live use switched off since, say) is the same change.
     */
    public static String fingerprint(String targetRef, List<PolicyChange> changes) {
        List<String> parts = new java.util.ArrayList<>();
        for (PolicyChange c : changes) {
            Map<String, Object> m = new java.util.LinkedHashMap<>(Json.readMap(Json.write(c, ONE_CHANGE)));
            m.remove("from");
            parts.add(canonical(m));
        }
        java.util.Collections.sort(parts);
        try {
            byte[] d = java.security.MessageDigest.getInstance("SHA-256").digest((targetRef + "|" + String.join("|", parts)).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(d);
        } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    private static String canonical(Object o) {
        if (o instanceof Map<?, ?> m) {
            java.util.TreeMap<String, Object> sorted = new java.util.TreeMap<>();
            m.forEach((k, v) -> sorted.put(String.valueOf(k), v));
            StringBuilder b = new StringBuilder("{");
            sorted.forEach((k, v) -> b.append(k).append('=').append(canonical(v)).append(','));
            return b.append('}').toString();
        }
        if (o instanceof List<?> l) {
            StringBuilder b = new StringBuilder("[");
            for (Object x : l) b.append(canonical(x)).append(',');
            return b.append(']').toString();
        }
        if (o instanceof Number n) return String.valueOf(n.doubleValue());
        return String.valueOf(o);
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
