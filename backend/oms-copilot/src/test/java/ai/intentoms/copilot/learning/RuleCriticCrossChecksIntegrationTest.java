package ai.intentoms.copilot.learning;

import ai.intentoms.copilot.NoLlmTestBase;
import ai.intentoms.learning.model.ArtifactRef;
import ai.intentoms.learning.model.PolicyChange;
import ai.intentoms.learning.model.PolicyChangeset;
import ai.intentoms.learning.model.Proposal;
import ai.intentoms.learning.model.ProposalStatus;
import ai.intentoms.learning.model.Review;
import ai.intentoms.learning.store.ProposalRepository;
import ai.intentoms.platform.ids.Ids;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * XP-02 (audit E3-N2): the automatic rule critic (it runs on every committed evaluation, no one has to ask) weighs the
 * inverse-propensity cross-check and the share of final rewards. The report below is the seeded
 * {@code prop_demo_t0_exception_triage}'s: a doubly-robust headline clearly above zero, the plain IPS interval entirely
 * below zero and no mature reward. That used to be reviewed "looks safe, no risks".
 */
class RuleCriticCrossChecksIntegrationTest extends NoLlmTestBase {
    @Autowired ProposalRepository proposals;

    private Proposal draft() {
        PolicyChangeset cs = new PolicyChangeset(new ArtifactRef("decision", "exception_classify"), 1,
            List.of(new PolicyChange.ThresholdChange("exception_classify", "act", 0.50, 0.55)), "triage more exceptions", PolicyChangeset.RiskTier.T0);
        Proposal p = new Proposal(Ids.prefixed("prop"), cs, null, PolicyChangeset.RiskTier.T0, ProposalStatus.DRAFT, null, null, "system:test", Instant.now(), Instant.now(),
            null, null, null, null, null, null, null, null);
        proposals.insert(p);
        return p;
    }

    private static Map<String, Object> report(double ipsLower, double ipsUpper, double matureShare) {
        return Map.of("method", "ips_doubly_robust", "sampleSize", 120, "liftEstimate", 0.153, "confidenceInterval", Map.of("lower", 0.074, "upper", 0.2325),
            "ips", Map.of("liftEstimate", (ipsLower + ipsUpper) / 2, "confidenceInterval", Map.of("lower", ipsLower, "upper", ipsUpper)),
            "rewards", Map.of("mature", Math.round(120 * matureShare), "provisional", 120 - Math.round(120 * matureShare), "matureShare", matureShare));
    }

    private Review evaluatedWith(Map<String, Object> report) {
        Proposal p = draft();
        proposals.markEvaluated(p.proposalId(), report, Instant.now());
        Review r = proposals.find(p.proposalId()).orElseThrow().criticReview();
        assertThat(r).as("the critic runs on the committed evaluation").isNotNull();
        assertThat(r.source()).isEqualTo(Review.RULES);
        return r;
    }

    @Test
    void aSignificantlyNegativeIpsEstimateIsARiskEvenUnderAGoodHeadline() {
        Review r = evaluatedWith(report(-0.302, -0.067, 1.0));
        assertThat(r.verdict()).isEqualTo(Review.Verdict.RISKY);
        assertThat(r.risks()).anyMatch(s -> s.contains("inverse-propensity") && s.contains("-0.302") && s.contains("-0.067"));
    }

    @Test
    void mostlyProvisionalRewardsAreARiskAndNeverLookSafe() {
        Review r = evaluatedWith(report(0.02, 0.21, 0.0));
        assertThat(r.verdict()).isEqualTo(Review.Verdict.NEEDS_CHANGES);
        assertThat(r.risks()).anyMatch(s -> s.contains("0% of the rewards") && s.contains("final"));
    }

    @Test
    void theSeededT0ShapeIsNeverLooksSafe() {
        Review r = evaluatedWith(report(-0.302, -0.067, 0.0));
        assertThat(r.verdict()).isNotEqualTo(Review.Verdict.LOOKS_SAFE).isEqualTo(Review.Verdict.RISKY);
        assertThat(r.risks()).hasSizeGreaterThanOrEqualTo(2);
    }

    @Test
    void aChangeBothEstimatorsAgreeOnWithFinalRewardsStillLooksSafe() {
        Review r = evaluatedWith(report(0.03, 0.25, 0.8));
        assertThat(r.verdict()).isEqualTo(Review.Verdict.LOOKS_SAFE);
        assertThat(r.risks()).isEmpty();
    }
}
