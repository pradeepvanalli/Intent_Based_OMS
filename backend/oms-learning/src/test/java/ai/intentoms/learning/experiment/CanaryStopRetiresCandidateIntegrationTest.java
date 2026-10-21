package ai.intentoms.learning.experiment;

import ai.intentoms.learning.model.ArtifactRef;
import ai.intentoms.learning.model.ExperimentMode;
import ai.intentoms.learning.model.ExperimentStatus;
import ai.intentoms.learning.model.PolicyChange;
import ai.intentoms.learning.model.PolicyChangeset;
import ai.intentoms.learning.model.Proposal;
import ai.intentoms.learning.model.ProposalStatus;
import ai.intentoms.learning.support.DecisionFabricTestBase;
import ai.intentoms.platform.ledger.Actor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * D4-2 (audit 4, EX-03): {@code ExperimentManager.finish} retired the candidate decision version only for SHADOW
 * experiments; a canary's own candidate ({@code buildCandidate}, lint + golden-set gate, left as {@code draft} the
 * whole time it runs) was never retired on stop, so every canary start-then-stop left a stray {@code draft} version
 * behind (observed live: {@code return_risk} v3). Fixed: a canary's candidate is retired on stop too, and the
 * experiment's result says whether it happened.
 */
class CanaryStopRetiresCandidateIntegrationTest extends DecisionFabricTestBase {
    @Autowired ExperimentManager experiments;

    private static final String TESTER = "user:tester";

    @Test
    void stoppingACanaryRetiresItsCandidateVersionInsteadOfLeavingAStrayDraft() {
        String name = installProbe("d4_2_canary");
        int v1 = liveVersion(name);
        Proposal p = proposal("system:band_threshold", new ArtifactRef("decision", name), v1, PolicyChangeset.RiskTier.T1, ProposalStatus.EVALUATED,
            new PolicyChange.ThresholdChange(name, "act", 0.8, 0.5));

        var e = experiments.start(p.proposalId(), ExperimentMode.CANARY, 0.1, Map.of(), TESTER);
        assertThat(definitions.find(name, e.candidateVersion()).orElseThrow().definition().status()).as("never published while running").isEqualTo("draft");

        experiments.stop(e.experimentId(), ExperimentStatus.STOPPED, Map.of(), Actor.parse(TESTER));

        assertThat(definitions.find(name, e.candidateVersion()).orElseThrow().definition().status())
            .as("a stopped canary's candidate must not be left behind as a stray draft (D4-2)").isEqualTo("retired");
        var stopped = experiments.get(e.experimentId());
        assertThat(stopped.status()).isEqualTo(ExperimentStatus.STOPPED);
        assertThat(stopped.result()).as("the result says the candidate was retired").containsEntry("candidateVersionRetired", true);
    }

    @Test
    void completingACanaryAlsoRetiresItsCandidateVersion() {
        String name = installProbe("d4_2_canary_complete");
        int v1 = liveVersion(name);
        Proposal p = proposal("system:band_threshold", new ArtifactRef("decision", name), v1, PolicyChangeset.RiskTier.T1, ProposalStatus.EVALUATED,
            new PolicyChange.ThresholdChange(name, "act", 0.8, 0.5));
        var e = experiments.start(p.proposalId(), ExperimentMode.CANARY, 0.5, Map.of("minPerArm", 5), TESTER);
        jdbc.sql("update experiment set start_at = :s where experiment_id = :id")
            .param("s", java.sql.Timestamp.from(e.startAt().minus(java.time.Duration.ofDays(7)).minusSeconds(60))).param("id", e.experimentId()).update();

        experiments.complete(e.experimentId(), Actor.parse(TESTER));

        assertThat(definitions.find(name, e.candidateVersion()).orElseThrow().definition().status())
            .as("a completed canary's candidate is retired too").isEqualTo("retired");
    }
}
