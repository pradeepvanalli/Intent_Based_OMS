package ai.intentoms.learning.api;

import ai.intentoms.learning.governance.ParameterResolver;
import ai.intentoms.learning.hooks.NodeReliabilityFeatures;
import ai.intentoms.learning.model.ProposalStatus;
import ai.intentoms.learning.promotion.PromotionService;
import ai.intentoms.learning.store.ProposalRepository;
import ai.intentoms.platform.error.ConflictException;
import ai.intentoms.platform.error.OmsException;
import ai.intentoms.platform.error.ValidationException;
import ai.intentoms.platform.ledger.Actor;
import ai.intentoms.platform.security.Principal;
import ai.intentoms.platform.security.PrincipalAuthentication;
import ai.intentoms.testing.PostgresTestBase;
import ai.intentoms.testing.TestApplication;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** EX-07 for live use: propose → approve by someone else → promote makes a learned family live; revoke turns it off at once. */
@SpringBootTest(classes = TestApplication.class)
@ActiveProfiles("test")
class LiveUseFlowIntegrationTest extends PostgresTestBase {
    @Autowired LiveUseController liveUse;
    @Autowired ProposalRepository proposals;
    @Autowired PromotionService promotion;
    @Autowired ParameterResolver resolver;
    @Autowired LearningController learning;
    @Autowired ai.intentoms.learning.governance.LearnerRegistry registry;

    private void as(String user) {
        SecurityContextHolder.getContext().setAuthentication(new PrincipalAuthentication(new Principal(Principal.Type.USER, user, TENANT, Set.of("strategy_lead"), Set.of("*"), 3)));
    }

    @AfterEach void clear() { SecurityContextHolder.clearContext(); }

    @Test
    void liveUseIsAProposalThroughTheGateAndRevokeIsImmediate() {
        String family = NodeReliabilityFeatures.REJECT_RATE;
        resolver.revoke(family, "test-setup"); // the shared test tenant may carry a promotion from another test
        assertThat(resolver.isLive(family)).isFalse();
        assertThat(resolver.explain(family, 0.10, 0.30).value()).isEqualTo(0.10);

        as("alice");
        var p = liveUse.propose(new LiveUseController.LiveUseRequest(family, "node_reliability", 0.0, 0.5, 0.1, true, null));
        assertThat(p.status()).isEqualTo(ProposalStatus.DRAFT);
        assertThat(p.changeset().target().toString()).isEqualTo("parameter:live:" + family);

        as("bob");
        // Defect 2: a draft is tested before it is approved.
        assertThatThrownBy(() -> learning.approve(p.proposalId(), null)).isInstanceOf(ConflictException.class);
        proposals.markEvaluated(p.proposalId(), java.util.Map.of("method", "no_counterfactual", "sampleSize", 0), java.time.Instant.now());
        proposals.approve(p.proposalId(), "user:bob", java.time.Instant.now());
        // EX-07: with no shadow run and no comparison a person must say why they promote anyway.
        assertThatThrownBy(() -> promotion.promote(p.proposalId(), Actor.parse("user:bob")))
            .isInstanceOf(OmsException.class).satisfies(e -> assertThat(((OmsException) e).code()).isEqualTo("promotion_needs_override"));
        promotion.promote(p.proposalId(), Actor.parse("user:bob"), "pilot on one region agreed with operations");
        assertThat(proposals.get(p.proposalId()).status()).isEqualTo(ProposalStatus.PROMOTED);
        assertThat(proposals.promotionNote(p.proposalId()).orElseThrow()).containsEntry("reason", "pilot on one region agreed with operations");
        assertThat(resolver.isLive(family)).isTrue();
        // learned 0.30 is served, limited to max step 0.1 from the static 0.10
        assertThat(resolver.explain(family, 0.10, 0.30).value()).isEqualTo(0.2, org.assertj.core.data.Offset.offset(1e-9));

        liveUse.revoke(family);
        assertThat(resolver.isLive(family)).isFalse();
        assertThat(resolver.explain(family, 0.10, 0.30).value()).isEqualTo(0.10);
        // Defect 27: switching live use off closes the proposal that switched it on.
        var closed = proposals.get(p.proposalId());
        assertThat(closed.status()).isEqualTo(ProposalStatus.ROLLED_BACK);
        assertThat(closed.rolledBackReason()).contains("switched off by user:bob");
        assertThatThrownBy(() -> promotion.rollback(p.proposalId(), Actor.parse("user:bob"), null)).isInstanceOf(ConflictException.class);
        // N4: a person's safety switch-off is no evidence against the change. The learner that would draft the same change
        // again still waits out the cooldown (defect 26), but a person may propose it again at once.
        var learnerRedraft = new ai.intentoms.learning.model.Proposal(ai.intentoms.platform.ids.Ids.prefixed("prop"), p.changeset(), null, p.tier(), ProposalStatus.DRAFT,
            null, null, "system:node_reliability", java.time.Instant.now(), java.time.Instant.now(), null, null, null, null, null, null, null, null);
        assertThatThrownBy(() -> proposals.insert(learnerRedraft))
            .isInstanceOf(OmsException.class).satisfies(e -> assertThat(((OmsException) e).code()).isEqualTo("proposal_cooling_down"));
        as("alice");
        var again = liveUse.propose(new LiveUseController.LiveUseRequest(family, "node_reliability", 0.0, 0.5, 0.1, true, null));
        assertThat(again.status()).isEqualTo(ProposalStatus.DRAFT);

        // A rollback for cause (here a person rolling the promoted change back) still holds everyone off for the cooldown.
        proposals.markEvaluated(again.proposalId(), java.util.Map.of("method", "no_counterfactual", "sampleSize", 0), java.time.Instant.now());
        proposals.approve(again.proposalId(), "user:bob", java.time.Instant.now());
        promotion.promote(again.proposalId(), Actor.parse("user:bob"), "pilot on one region agreed with operations");
        promotion.rollback(again.proposalId(), Actor.parse("user:bob"), "rejected orders went up in the pilot region");
        assertThatThrownBy(() -> liveUse.propose(new LiveUseController.LiveUseRequest(family, "node_reliability", 0.0, 0.5, 0.1, true, null)))
            .isInstanceOf(OmsException.class).satisfies(e -> assertThat(((OmsException) e).code()).isEqualTo("proposal_cooling_down"));
        // A different range is a different change.
        assertThat(liveUse.propose(new LiveUseController.LiveUseRequest(family, "node_reliability", 0.0, 0.4, 0.1, true, null)).status()).isEqualTo(ProposalStatus.DRAFT);
    }

    /** B-1: a live-use proposal stored without the endpoint's check (an older seeder, a direct insert) is checked again at promotion. */
    @Test
    void promotionRechecksTheRangeAgainstTheFamilyDomain() {
        String family = NodeReliabilityFeatures.REJECT_RATE;
        resolver.revoke(family, "test-setup");
        var cs = new ai.intentoms.learning.model.PolicyChangeset(new ai.intentoms.learning.model.ArtifactRef("parameter", "live:" + family), 0,
            java.util.List.of(new ai.intentoms.learning.model.PolicyChange.ParameterChange("live:" + family, java.util.Map.of("enabled", false),
                java.util.Map.of("enabled", true, "learner", "node_reliability", "min", 0.0, "max", 5.0, "maxStep", 0.1))),
            "reject rates read as a 0-5 score", ai.intentoms.learning.model.PolicyChangeset.RiskTier.T1);
        var p = new ai.intentoms.learning.model.Proposal(ai.intentoms.platform.ids.Ids.prefixed("prop"), cs, null, cs.tier(), ProposalStatus.DRAFT,
            null, null, "user:alice", java.time.Instant.now(), java.time.Instant.now(), null, null, null, null, null, null, null, null);
        proposals.insert(p);
        proposals.markEvaluated(p.proposalId(), java.util.Map.of("method", "no_counterfactual", "sampleSize", 0), java.time.Instant.now());
        proposals.approve(p.proposalId(), "user:bob", java.time.Instant.now());

        assertThatThrownBy(() -> promotion.promote(p.proposalId(), Actor.parse("user:bob"), "pilot agreed"))
            .isInstanceOf(ConflictException.class).hasMessageContaining("cannot switch on " + family).hasMessageContaining("max 5");
        assertThat(resolver.isLive(family)).isFalse();
        assertThat(proposals.get(p.proposalId()).status()).isEqualTo(ProposalStatus.APPROVED);
    }

    /**
     * B-1 as the audit reproduced it: warn-early is a 0-4 SLA breach risk score, and a range read as a probability
     * (0.3-0.9) served every "unlikely to breach" order a delay e-mail while reporting the value as plainly LEARNED.
     */
    @Test
    void liveRangesMustFitTheFamilyDomainAndClampsAreReported() {
        String family = ai.intentoms.learning.learner.WarnEarlyThresholdLearner.PARAMETER;
        as("alice");
        assertThatThrownBy(() -> liveUse.propose(new LiveUseController.LiveUseRequest(family, "warn_early", 0.0, 10.0, 1.0, true, null)))
            .isInstanceOf(ValidationException.class)
            .hasMessage("This range cannot be used for monitoring.warn_early_threshold: max 10 is outside the values monitoring.warn_early_threshold can take: "
                + "SLA breach risk score (0 none, 4 certain), from 0 to 4.");
        assertThatThrownBy(() -> liveUse.propose(new LiveUseController.LiveUseRequest(family, "warn_early", 1.0, 3.0, 3.0, true, null)))
            .isInstanceOf(ValidationException.class).hasMessageContaining("max step 3 is too large for monitoring.warn_early_threshold: one promotion may move it at most 2");
        assertThatThrownBy(() -> liveUse.propose(new LiveUseController.LiveUseRequest("sourcing.policy_bandit", "sourcing_policy_bandit", 0.0, 1.0, null, true, null)))
            .isInstanceOf(ValidationException.class).hasMessageContaining("is an on/off switch").hasMessageContaining("leave min, max and max step empty");
        assertThat(liveUse.propose(new LiveUseController.LiveUseRequest(family, "warn_early", 1.5, 3.5, 0.5, true, null)).status()).isEqualTo(ProposalStatus.DRAFT);
        assertThat(liveUse.domains()).anySatisfy(d -> assertThat(d).containsEntry("family", family).containsEntry("min", 0.0).containsEntry("max", 4.0)
            .containsEntry("maxStepLimit", 2.0).containsEntry("affectedDecisions", java.util.List.of("sla_breach_risk")));

        // A range that was promoted anyway (the seeded 0.3-0.9) still bounds the value, but the clamp is reported.
        try {
            resolver.promote(family, "warn_early", 0.3, 0.9, null, null, "test-setup");
            ParameterResolver.Resolution r = resolver.explain(family, 2.0, 2.0);
            assertThat(r.value()).isEqualTo(0.9);
            assertThat(r.clamped()).isTrue();
            assertThat(r.reason()).isEqualTo("learned 2 is above the promoted maximum; clamped to 0.9");
            assertThat(r.describe()).containsEntry("clamped", true).containsEntry("learned", 2.0);
            assertThat(resolver.explain(family, 2.0, 0.5).clamped()).isFalse();
            // A learned number outside the family's domain is never served.
            ParameterResolver.Resolution wrongUnits = resolver.explain(family, 2.0, 7.0);
            assertThat(wrongUnits.source()).isEqualTo("STATIC");
            assertThat(wrongUnits.value()).isEqualTo(2.0);
            assertThat(wrongUnits.reason()).startsWith("learned value 7 is outside the values monitoring.warn_early_threshold can take");
        } finally {
            resolver.revoke(family, "test-cleanup");
        }
    }

    /** B-1: every family a registered learner feeds declares its value domain in one place. */
    @Test
    void everyFedFamilyDeclaresItsDomain() {
        assertThat(registry.descriptors().stream().flatMap(d -> d.liveParameters().stream()).distinct().toList())
            .isNotEmpty().allSatisfy(f -> assertThat(ai.intentoms.learning.governance.LiveFamilies.of(f)).as(f).isPresent());
    }

    @Test
    void aLearnerCanOnlyFeedTheFamiliesItDeclares() {
        as("alice");
        assertThatThrownBy(() -> liveUse.propose(new LiveUseController.LiveUseRequest("promise.buffer_days", "node_reliability", null, null, null, true, null)))
            .isInstanceOf(ValidationException.class);
    }

    /** GV-07: approving a change scoped to postal codes needs a reason code from the allowed list. */
    @Test
    void postalCodeScopedChangesNeedAReasonCodeToApprove() {
        var cs = new ai.intentoms.learning.model.PolicyChangeset(new ai.intentoms.learning.model.ArtifactRef("parameter", "sourcing.blocked_zip3"), 0,
            java.util.List.of(new ai.intentoms.learning.model.PolicyChange.ParameterChange("sourcing.blocked_zip3", java.util.List.of(), java.util.List.of("941"))),
            "carrier keeps failing here", ai.intentoms.learning.model.PolicyChangeset.RiskTier.T1);
        var now = java.time.Instant.now();
        var p = new ai.intentoms.learning.model.Proposal(ai.intentoms.platform.ids.Ids.prefixed("prop"), cs, null, ai.intentoms.learning.model.PolicyChangeset.RiskTier.T1,
            ProposalStatus.DRAFT, null, null, "user:alice", now, now, null, null, null, null, null, null, null, null);
        proposals.insert(p);
        proposals.markEvaluated(p.proposalId(), java.util.Map.of("method", "no_counterfactual"), now);
        as("bob");
        assertThatThrownBy(() -> learning.approve(p.proposalId(), null)).isInstanceOf(ValidationException.class).hasMessageContaining("reason code");
        assertThatThrownBy(() -> learning.approve(p.proposalId(), java.util.Map.of("reasonCode", "because"))).isInstanceOf(ValidationException.class);
        assertThat(learning.approve(p.proposalId(), java.util.Map.of("reasonCode", "carrier_service_gap")).status()).isEqualTo(ProposalStatus.APPROVED);
    }
}
