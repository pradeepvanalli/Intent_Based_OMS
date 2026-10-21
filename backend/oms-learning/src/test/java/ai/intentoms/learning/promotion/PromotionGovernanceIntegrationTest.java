package ai.intentoms.learning.promotion;

import ai.intentoms.learning.governance.LearnerGovernance;
import ai.intentoms.learning.model.ArtifactRef;
import ai.intentoms.learning.model.Direction;
import ai.intentoms.learning.model.Hypothesis;
import ai.intentoms.learning.model.PolicyChange;
import ai.intentoms.learning.model.PolicyChangeset;
import ai.intentoms.learning.model.Proposal;
import ai.intentoms.learning.model.ProposalStatus;
import ai.intentoms.learning.store.HypothesisRepository;
import ai.intentoms.learning.support.DecisionFabricTestBase;
import ai.intentoms.platform.config.TenantConfigStore;
import ai.intentoms.platform.error.ConflictException;
import ai.intentoms.platform.error.OmsException;
import ai.intentoms.platform.error.ValidationException;
import ai.intentoms.platform.ids.Ids;
import ai.intentoms.platform.ledger.Actor;
import ai.intentoms.platform.ledger.EventStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/** Defects 2, 3, 4, 6, 26 and GV-02 from the requirements audit, through the real services and database. */
class PromotionGovernanceIntegrationTest extends DecisionFabricTestBase {
    @Autowired PromotionService promotion;
    @Autowired LearnerGovernance governance;
    @Autowired TenantConfigStore config;
    @Autowired HypothesisRepository hypotheses;
    @Autowired EventStore events;

    private static final Actor HUMAN = Actor.parse("user:approver");
    private static final String REASON = "test: a person accepts the weak evidence on purpose";

    private Proposal approvedThreshold(String name, double from, double to) {
        return proposal("system:band_threshold", new ArtifactRef("decision", name), liveVersion(name), PolicyChangeset.RiskTier.T1, ProposalStatus.APPROVED,
            new PolicyChange.ThresholdChange(name, "act", from, to));
    }

    // ---------------------------------------------------------------- defect 2: lifecycle

    @Test
    void aProposalOnlyMovesAlongItsLifecycle() {
        String name = installProbe("e2_life");
        Proposal p = approvedThreshold(name, 0.8, 0.78);
        promotion.promote(p.proposalId(), HUMAN);
        assertThat(proposals.get(p.proposalId()).status()).isEqualTo(ProposalStatus.PROMOTED);

        // Approving, re-testing or rejecting a live change is refused; it can only be rolled back.
        assertThatThrownBy(() -> proposals.approve(p.proposalId(), "user:other", Instant.now())).isInstanceOf(ConflictException.class).hasMessageContaining("it is live");
        assertThatThrownBy(() -> proposals.markEvaluated(p.proposalId(), Map.of("method", "x"), Instant.now())).isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> proposals.reject(p.proposalId(), "no", Instant.now())).isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> promotion.promote(p.proposalId(), HUMAN, REASON)).isInstanceOf(ConflictException.class);

        promotion.rollback(p.proposalId(), HUMAN, "test");
        // A rolled-back change is closed: no approve, no promote, no reject.
        assertThatThrownBy(() -> proposals.approve(p.proposalId(), "user:other", Instant.now())).isInstanceOf(ConflictException.class).hasMessageContaining("rolled back");
        assertThatThrownBy(() -> proposals.reject(p.proposalId(), "no", Instant.now())).isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> promotion.rollback(p.proposalId(), HUMAN, "again")).isInstanceOf(ConflictException.class);
        assertThat(proposals.get(p.proposalId()).status()).isEqualTo(ProposalStatus.ROLLED_BACK);
    }

    @Test
    void aDraftMustBeTestedBeforeItIsApproved() {
        String name = installProbe("e2_draft");
        Proposal p = proposal("system:band_threshold", new ArtifactRef("decision", name), liveVersion(name), PolicyChangeset.RiskTier.T1, ProposalStatus.DRAFT,
            new PolicyChange.ThresholdChange(name, "act", 0.8, 0.78));
        assertThatThrownBy(() -> proposals.approve(p.proposalId(), "user:other", Instant.now())).isInstanceOf(ConflictException.class).hasMessageContaining("Test it");
    }

    // ---------------------------------------------------------------- defect 3: re-test keeps the shadow verdict

    @Test
    void reTestingKeepsTheShadowVerdict() {
        String name = installProbe("e2_shadow");
        Proposal p = proposal("system:band_threshold", new ArtifactRef("decision", name), liveVersion(name), PolicyChangeset.RiskTier.T0, ProposalStatus.EVALUATED,
            new PolicyChange.ThresholdChange(name, "act", 0.8, 0.78));
        proposals.mergeReport(p.proposalId(), "shadow", Map.of("passed", true, "paired", 40));
        proposals.markEvaluated(p.proposalId(), Map.of("method", "ips_doubly_robust", "liftEstimate", 0.05, "confidenceInterval", Map.of("lower", 0.01, "upper", 0.09)), Instant.now());
        Map<String, Object> report = proposals.get(p.proposalId()).evaluationReport();
        assertThat(report).containsKey("shadow").containsEntry("liftEstimate", 0.05);
        assertThat(((Map<?, ?>) report.get("shadow")).get("passed")).isEqualTo(true);
    }

    // ---------------------------------------------------------------- defect 4: kill switches stop promotion

    @Test
    void theTenantKillSwitchBlocksManualAndAutomaticPromotion() {
        String name = installProbe("e2_kill");
        Proposal p = approvedThreshold(name, 0.8, 0.78);
        governance.setTenantKillSwitch(true, "user:admin");
        try {
            assertThatThrownBy(() -> promotion.promote(p.proposalId(), HUMAN, REASON)).isInstanceOf(ConflictException.class).hasMessageContaining("switched off");
            assertThat(promotion.sweepOnce()).isZero();
        } finally {
            governance.setTenantKillSwitch(false, "user:admin");
        }
        promotion.promote(p.proposalId(), HUMAN);
        assertThat(proposals.get(p.proposalId()).status()).isEqualTo(ProposalStatus.PROMOTED);
    }

    @Test
    void theGlobalKillSwitchBlocksEveryTenant() {
        String name = installProbe("e2_global");
        Proposal p = approvedThreshold(name, 0.8, 0.78);
        governance.setGlobalKillSwitch(true, "user:platform");
        try {
            assertThat(governance.learningOn()).isFalse();
            assertThat(governance.offReason()).isEqualTo("global_kill_switch");
            assertThatThrownBy(() -> promotion.promote(p.proposalId(), HUMAN, REASON)).isInstanceOf(ConflictException.class).hasMessageContaining("global kill switch");
        } finally {
            governance.setGlobalKillSwitch(false, "user:platform");
        }
        assertThat(governance.learningOn()).isTrue();
    }

    // ---------------------------------------------------------------- GV-02 budget per target, override recorded

    @Test
    void aSecondPromotionOfTheSameTargetInAWeekNeedsAWrittenReason() {
        String name = installProbe("e2_budget");
        promotion.promote(approvedThreshold(name, 0.8, 0.78).proposalId(), HUMAN);
        Proposal second = approvedThreshold(name, 0.78, 0.76);

        assertThatThrownBy(() -> promotion.promote(second.proposalId(), HUMAN))
            .isInstanceOf(OmsException.class).satisfies(e -> {
                assertThat(((OmsException) e).code()).isEqualTo("promotion_needs_override");
                assertThat((List<?>) ((OmsException) e).details().get("waivable")).anyMatch(w -> String.valueOf(w).contains("GV-02"));
            });

        promotion.promote(second.proposalId(), HUMAN, REASON);
        assertThat(proposals.get(second.proposalId()).status()).isEqualTo(ProposalStatus.PROMOTED);
        Map<String, Object> note = proposals.promotionNote(second.proposalId()).orElseThrow();
        assertThat(note).containsEntry("reason", REASON).containsEntry("by", "user:approver");
        var promoted = events.load("learning", second.proposalId()).stream().filter(e -> e.type().equals("proposal.promoted")).findFirst().orElseThrow();
        assertThat(promoted.payload()).containsKey("override");
    }

    @Test
    void aLearnersMaxStepIsSetThroughGovernanceAndHonoured() {
        String name = installProbe("e2_step");
        governance.setBudget("band_threshold", 0.01, 5, "user:admin");
        try {
            Proposal p = approvedThreshold(name, 0.8, 0.78);
            assertThatThrownBy(() -> promotion.promote(p.proposalId(), HUMAN)).isInstanceOf(OmsException.class).hasMessageContaining("max step of 0.0100");
        } finally {
            governance.setBudget("band_threshold", null, 1, "user:admin");
        }
    }

    // ---------------------------------------------------------------- defect 6: weights applied as one vector

    @Test
    @SuppressWarnings("unchecked")
    void aMultiWeightChangeIsAppliedAtomically() {
        String key = "sourcing.weights_e2_" + Ids.shortId();
        config.set(key, Map.of("cost", 0.4, "time", 0.25, "soft", 0.25, "split", 0.1));
        // What the weight learner drafts: three weights moved, renormalised, split left alone.
        Proposal p = proposal("system:sourcing_weights", new ArtifactRef("config", key), 0, PolicyChangeset.RiskTier.T1, ProposalStatus.APPROVED,
            new PolicyChange.WeightChange(key, "cost", 0.4, 0.43), new PolicyChange.WeightChange(key, "time", 0.25, 0.22), new PolicyChange.WeightChange(key, "soft", 0.25, 0.25));
        promotion.promote(p.proposalId(), HUMAN, REASON);
        Map<String, Object> w = config.getMap(key);
        assertThat(((Number) w.get("cost")).doubleValue()).isCloseTo(0.43, within(1e-6));
        assertThat(((Number) w.get("time")).doubleValue()).isCloseTo(0.22, within(1e-6));
        assertThat(((Number) w.get("soft")).doubleValue()).isCloseTo(0.25, within(1e-6));
        assertThat(((Number) w.get("split")).doubleValue()).isCloseTo(0.10, within(1e-6));
        assertThat(w.values().stream().mapToDouble(v -> ((Number) v).doubleValue()).sum()).isCloseTo(1.0, within(1e-6));

        promotion.rollback(p.proposalId(), HUMAN, null);
        assertThat(((Number) config.getMap(key).get("cost")).doubleValue()).isEqualTo(0.4);
    }

    @Test
    void anUnsetWeightMapNeedsEveryWeightWhenNoDefaultIsKnown() {
        String key = "sourcing.weights_e2_unset_" + Ids.shortId();
        Proposal partial = proposal("system:sourcing_weights", new ArtifactRef("config", key), 0, PolicyChangeset.RiskTier.T1, ProposalStatus.APPROVED,
            new PolicyChange.WeightChange(key, "cost", 0.4, 0.45), new PolicyChange.WeightChange(key, "time", 0.25, 0.2));
        assertThatThrownBy(() -> promotion.promote(partial.proposalId(), HUMAN, REASON)).hasMessageContaining("must list every weight");
        assertThat(config.get(key)).isEmpty();

        Proposal full = proposal("system:sourcing_weights", new ArtifactRef("config", key), 0, PolicyChangeset.RiskTier.T1, ProposalStatus.APPROVED,
            new PolicyChange.WeightChange(key, "cost", 0.4, 0.5), new PolicyChange.WeightChange(key, "time", 0.6, 0.5));
        promotion.promote(full.proposalId(), HUMAN, REASON);
        assertThat(config.getMap(key)).containsEntry("cost", 0.5).containsEntry("time", 0.5);
    }

    // ---------------------------------------------------------------- defect 26 / §5: rollback is evidence

    @Test
    void aRolledBackChangeCannotBeProposedAgainDuringTheCooldownAndCountsAgainstItsHypothesis() {
        String name = installProbe("e2_cool");
        Hypothesis h = new Hypothesis(Ids.prefixed("hyp"), Map.of("decision", name), "override_rate", Direction.DECREASE, Map.of(), 0.8,
            List.of("initial evidence"), "act sooner", Hypothesis.Status.DRAFTED, Instant.now());
        hypotheses.insert(h);
        PolicyChangeset cs = new PolicyChangeset(new ArtifactRef("decision", name), liveVersion(name), List.of(new PolicyChange.ThresholdChange(name, "act", 0.8, 0.78)),
            "act sooner", PolicyChangeset.RiskTier.T1);
        Proposal p = new Proposal(Ids.prefixed("prop"), cs, h.hypothesisId(), PolicyChangeset.RiskTier.T1, ProposalStatus.DRAFT, null, null, "system:band_threshold",
            Instant.now(), Instant.now(), null, null, null, null, null, null, null, null);
        proposals.insert(p);
        proposals.markEvaluated(p.proposalId(), Map.of("liftEstimate", 0.1, "confidenceInterval", Map.of("lower", 0.01, "upper", 0.2)), Instant.now());
        proposals.approve(p.proposalId(), "user:reviewer", Instant.now());
        promotion.promote(p.proposalId(), HUMAN);
        promotion.rollback(p.proposalId(), Actor.system("guardrail-breach-monitor"), "guardrail breach: override rate 13%");

        Proposal again = new Proposal(Ids.prefixed("prop"), cs, h.hypothesisId(), PolicyChangeset.RiskTier.T1, ProposalStatus.DRAFT, null, null, "system:band_threshold",
            Instant.now(), Instant.now(), null, null, null, null, null, null, null, null);
        assertThatThrownBy(() -> proposals.insert(again)).isInstanceOf(OmsException.class)
            .satisfies(e -> assertThat(((OmsException) e).code()).isEqualTo("proposal_cooling_down"));

        Hypothesis after = hypotheses.find(h.hypothesisId()).orElseThrow();
        assertThat(after.priorConfidence()).isCloseTo(0.4, within(1e-9));
        assertThat(after.evidence()).anyMatch(e -> e.contains("Rolled back") && e.contains("override rate"));

        // A different change on the same target is still welcome.
        PolicyChangeset other = new PolicyChangeset(cs.target(), cs.baseVersion(), List.of(new PolicyChange.ThresholdChange(name, "act", 0.8, 0.79)), "smaller step",
            PolicyChangeset.RiskTier.T1);
        proposals.insert(new Proposal(Ids.prefixed("prop"), other, null, PolicyChangeset.RiskTier.T1, ProposalStatus.DRAFT, null, null, "system:band_threshold",
            Instant.now(), Instant.now(), null, null, null, null, null, null, null, null));
    }

    // ---------------------------------------------------------------- D5-8: separation of duties on promote

    /** D5-8: the author of a proposal must not also be the one who promotes it, mirroring the existing author-can't-
     * approve-it-themselves rule. A different person approving in between doesn't change that — only a third person
     * (or the original approver) may promote. */
    @Test
    void theAuthorOfAProposalCannotPromoteItThemselvesEvenAfterSomeoneElseApproved() {
        String name = installProbe("e_sod");
        // proposal(...) with ProposalStatus.APPROVED both evaluates (positive, no-shadow evidence) and approves as "user:reviewer" —
        // a person different from the author, exactly like the audit's repro ("author drafts, a different person approves").
        Proposal p = proposal("user:alice", new ArtifactRef("decision", name), liveVersion(name), PolicyChangeset.RiskTier.T1, ProposalStatus.APPROVED,
            new PolicyChange.ThresholdChange(name, "act", 0.8, 0.78));
        assertThat(p.createdBy()).isEqualTo("user:alice");
        assertThat(p.approvedBy()).isEqualTo("user:reviewer");

        // The original author tries to promote their own (now approved-by-someone-else) proposal: refused, 422-mapped.
        assertThatThrownBy(() -> promotion.promote(p.proposalId(), Actor.parse("user:alice")))
            .isInstanceOf(ValidationException.class)
            .hasMessageContaining("author").hasMessageContaining("cannot promote");
        assertThat(proposals.get(p.proposalId()).status()).as("refused before promotion, still APPROVED").isEqualTo(ProposalStatus.APPROVED);

        // A third person — neither the author nor the approver — promotes it successfully.
        promotion.promote(p.proposalId(), Actor.parse("user:carol"));
        assertThat(proposals.get(p.proposalId()).status()).isEqualTo(ProposalStatus.PROMOTED);
    }

    /** D5-8: the guard is for a *person* promoting only — the automatic sweep (which never routes through
     * {@link PromotionService#promote(String, Actor, String)}, applying the change directly as {@code Actor.system(...)})
     * must keep auto-promoting a system-authored T0 change with good evidence, exactly as before this fix. */
    @Test
    void theAutomaticSweepStillAutoPromotesASystemAuthoredProposalUnaffectedByTheSeparationOfDutiesGuard() {
        String name = installProbe("e_sod_auto");
        Proposal p = proposal("system:band_threshold", new ArtifactRef("decision", name), liveVersion(name), PolicyChangeset.RiskTier.T0, ProposalStatus.EVALUATED,
            new PolicyChange.ThresholdChange(name, "act", 0.8, 0.78));
        proposals.markEvaluated(p.proposalId(), Map.of("liftEstimate", 0.1, "confidenceInterval", Map.of("lower", 0.01, "upper", 0.2),
            "shadow", Map.of("passed", true)), Instant.now());

        // >= 1, not ==1: the shared "test" tenant may carry other EVALUATED/auto-promotable proposals left behind by
        // other tests in this class; what matters here is that *this* proposal (system-authored, no person involved)
        // still auto-promotes, unaffected by the new separation-of-duties guard on the person-driven promote() path.
        assertThat(promotion.sweepOnce()).isGreaterThanOrEqualTo(1);
        assertThat(proposals.get(p.proposalId()).status()).isEqualTo(ProposalStatus.PROMOTED);
    }
}
