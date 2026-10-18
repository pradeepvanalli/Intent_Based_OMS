package ai.intentoms.learning.api;

import ai.intentoms.learning.governance.ParameterResolver;
import ai.intentoms.learning.hooks.NodeReliabilityFeatures;
import ai.intentoms.learning.model.ProposalStatus;
import ai.intentoms.learning.promotion.PromotionService;
import ai.intentoms.learning.store.ProposalRepository;
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
        proposals.approve(p.proposalId(), "user:bob", java.time.Instant.now());
        promotion.promote(p.proposalId(), Actor.parse("user:bob"));
        assertThat(proposals.get(p.proposalId()).status()).isEqualTo(ProposalStatus.PROMOTED);
        assertThat(resolver.isLive(family)).isTrue();
        // learned 0.30 is served, limited to max step 0.1 from the static 0.10
        assertThat(resolver.explain(family, 0.10, 0.30).value()).isEqualTo(0.2, org.assertj.core.data.Offset.offset(1e-9));

        liveUse.revoke(family);
        assertThat(resolver.isLive(family)).isFalse();
        assertThat(resolver.explain(family, 0.10, 0.30).value()).isEqualTo(0.10);
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
        as("bob");
        assertThatThrownBy(() -> learning.approve(p.proposalId(), null)).isInstanceOf(ValidationException.class).hasMessageContaining("reason code");
        assertThatThrownBy(() -> learning.approve(p.proposalId(), java.util.Map.of("reasonCode", "because"))).isInstanceOf(ValidationException.class);
        assertThat(learning.approve(p.proposalId(), java.util.Map.of("reasonCode", "carrier_service_gap")).status()).isEqualTo(ProposalStatus.APPROVED);
    }
}
