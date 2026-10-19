package ai.intentoms.simulation.learning;

import ai.intentoms.decision.definition.DecisionDefinitionRepository;
import ai.intentoms.decision.model.DecisionDefinition;
import ai.intentoms.decision.studio.DecisionStudioService;
import ai.intentoms.learning.model.ArtifactRef;
import ai.intentoms.learning.model.PolicyChange;
import ai.intentoms.learning.model.PolicyChangeset;
import ai.intentoms.learning.model.Proposal;
import ai.intentoms.learning.model.ProposalStatus;
import ai.intentoms.learning.store.ProposalRepository;
import ai.intentoms.platform.error.ConflictException;
import ai.intentoms.platform.ids.Ids;
import ai.intentoms.testing.PostgresTestBase;
import ai.intentoms.testing.TestApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * D-2 (GV-02) at evaluation: a decision change is replayed only against the version that is live, so one drafted
 * against an older version, or from a threshold that is no longer the live one, is refused with "re-draft against the
 * live version" (409) instead of being evaluated on the old version's records and approved from there.
 */
@SpringBootTest(classes = TestApplication.class, properties = "oms.workflow.enabled=false")
@ActiveProfiles("test")
@TestPropertySource(properties = {"spring.flyway.ignore-migration-patterns=*:missing,*:future", "oms.learning.replay.min-cohort=3"})
class StaleProposalEvaluationIntegrationTest extends PostgresTestBase {
    @Autowired ProposalEvaluationService evaluations;
    @Autowired DecisionStudioService studio;
    @Autowired DecisionDefinitionRepository definitions;
    @Autowired ProposalRepository proposals;

    private int publish(String name, double act) {
        DecisionDefinition d = studio.createDraft(name, ProposalReplayIntegrationTest.yaml(name).replace("act: { confidence_gte: 0.5 }", "act: { confidence_gte: " + act + " }"));
        studio.publish(name, d.version(), "live", true);
        return definitions.byStatus(name, "live").get(0).definition().version();
    }

    private Proposal draft(String name, int base, double from, double to) {
        Proposal p = new Proposal(Ids.prefixed("prop"), new PolicyChangeset(new ArtifactRef("decision", name), base,
            List.<PolicyChange>of(new PolicyChange.ThresholdChange(name, "act", from, to)), "test", PolicyChangeset.RiskTier.T1), null,
            PolicyChangeset.RiskTier.T1, ProposalStatus.DRAFT, null, null, "system:band_threshold", Instant.now(), Instant.now(), null, null, null, null, null, null, null, null);
        proposals.insert(p);
        return p;
    }

    @Test
    void aThresholdChangeDraftedAgainstAnOlderVersionIsNotEvaluated() {
        String name = "d2_eval_" + Ids.shortId().toLowerCase();
        int v1 = publish(name, 0.6);
        Proposal early = draft(name, v1, 0.6, 0.55);
        int v2 = publish(name, 0.55); // the change it proposed went live by another route

        assertThatThrownBy(() -> evaluations.evaluate(early.proposalId(), "user:tester"))
            .isInstanceOf(ConflictException.class)
            .hasMessageContaining("drafted against version " + v1)
            .hasMessageContaining("version " + v2 + " is live")
            .hasMessageContaining("re-draft against the live version");
        assertThat(proposals.get(early.proposalId()).status()).isEqualTo(ProposalStatus.DRAFT);

        // The right version but a from that is not the live value is refused the same way.
        Proposal wrongFrom = draft(name, v2, 0.6, 0.5);
        assertThatThrownBy(() -> evaluations.evaluate(wrongFrom.proposalId(), "user:tester"))
            .isInstanceOf(ConflictException.class).hasMessageContaining("live value is 0.55").hasMessageContaining("re-draft against the live version");

        // Drafted against what is live, it is evaluated.
        Proposal current = draft(name, v2, 0.55, 0.5);
        assertThat(evaluations.evaluate(current.proposalId(), "user:tester").status()).isEqualTo(ProposalStatus.EVALUATED);
    }
}
