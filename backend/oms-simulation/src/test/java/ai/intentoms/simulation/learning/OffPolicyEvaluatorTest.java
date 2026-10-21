package ai.intentoms.simulation.learning;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/** Pure IPS/doubly-robust estimator math (EX-01). */
class OffPolicyEvaluatorTest {

    @Test
    void emptyLogYieldsZeroedResult() {
        OffPolicyEvaluator.Result r = OffPolicyEvaluator.evaluate(List.of(), rec -> rec.chosenArm());
        assertThat(r.sampleSize()).isZero();
        assertThat(r.liftEstimate()).isZero();
    }

    @Test
    void policyThatAlwaysPicksTheBetterArmShowsPositiveLiftWithCiNotCrossingZero() {
        // Two arms logged roughly 50/50 at propensity 0.5 each; "B" earns a consistently higher reward than "A".
        // A proposed policy that always picks "B" should show clear positive lift over the mixed logged policy.
        Random rnd = new Random(42);
        List<LoggedRecord> records = new ArrayList<>();
        for (int i = 0; i < 2000; i++) {
            boolean pickedB = rnd.nextBoolean();
            String arm = pickedB ? "B" : "A";
            double reward = pickedB ? 1.0 + rnd.nextGaussian() * 0.1 : 0.2 + rnd.nextGaussian() * 0.1;
            records.add(new LoggedRecord(arm, 0.5, 0.9, "ACT", reward));
        }

        OffPolicyEvaluator.Result r = OffPolicyEvaluator.evaluate(records, rec -> "B");

        assertThat(r.sampleSize()).isEqualTo(2000);
        assertThat(r.liftEstimate()).isPositive();
        assertThat(r.ciLower()).isPositive(); // CI doesn't cross zero: the lift is a real, detectable effect
        assertThat(r.clippedRecords()).isZero(); // propensity 0.5 is well above the 0.05 floor
    }

    @Test
    void identicalProposedAndLoggedPolicyShowsNoLift() {
        List<LoggedRecord> records = List.of(
            new LoggedRecord("A", 0.8, 0.9, "ACT", 1.0),
            new LoggedRecord("A", 0.8, 0.9, "ACT", 1.0),
            new LoggedRecord("A", 0.8, 0.9, "ACT", 1.0));

        OffPolicyEvaluator.Result r = OffPolicyEvaluator.evaluate(records, rec -> rec.chosenArm());

        assertThat(r.liftEstimate()).isCloseTo(0.0, org.assertj.core.data.Offset.offset(0.01));
    }

    @Test
    void propensityBelowFloorIsClippedNotAllowedToBlowUpTheEstimate() {
        List<LoggedRecord> records = List.of(
            new LoggedRecord("A", 0.001, 0.95, "ACT", 1.0), // would otherwise dominate the estimate (1 / 0.001 = 1000x)
            new LoggedRecord("A", 0.5, 0.9, "ACT", 1.0),
            new LoggedRecord("A", 0.5, 0.9, "ACT", 1.0));

        OffPolicyEvaluator.Result r = OffPolicyEvaluator.evaluate(records, rec -> rec.chosenArm());

        assertThat(r.clippedRecords()).isEqualTo(1);
        assertThat(r.proposedRewardEstimate()).isLessThan(10.0); // sane, not blown up by the near-zero propensity
    }

    /**
     * D4-1/EX-01: a decision's own governed exploration floor can legitimately log a true propensity below the
     * default 0.05 (an 8-arm decision exploring at a 10% share logs 0.1/7 ≈ 0.0143 for its explored arm). The default
     * floor would clip that record away, truncating the floor's own propensities and biasing the correction; the
     * explicit-floor overload lets a caller pass that decision's own (lower) floor instead.
     */
    @Test
    void anExplicitLowerFloorIsNotClippedWhileTheDefaultWould() {
        double explored = 0.1 / 7; // ExplorationFloor's share / (arms - 1) for an 8-arm T0 decision at a 10% share
        List<LoggedRecord> records = List.of(
            new LoggedRecord("system", explored, 0.9, "ACT", 1.0),
            new LoggedRecord("carrier_delay", 0.9, 0.9, "ACT", 1.0),
            new LoggedRecord("carrier_delay", 0.9, 0.9, "ACT", 1.0));

        OffPolicyEvaluator.Result defaultFloor = OffPolicyEvaluator.evaluate(records, rec -> rec.chosenArm());
        assertThat(defaultFloor.clippedRecords()).as("the default 0.05 floor clips the explored record").isEqualTo(1);

        OffPolicyEvaluator.Result lowerFloor = OffPolicyEvaluator.evaluate(records, rec -> rec.chosenArm(), explored);
        assertThat(lowerFloor.clippedRecords()).as("a floor at the explored propensity itself clips nothing").isZero();
    }

    @Test
    void thresholdChangeSimulatorMovesARecordOutOfTheActBandWhenConfidenceFallsShortOfTheNewThreshold() {
        ai.intentoms.learning.model.PolicyChangeset cs = new ai.intentoms.learning.model.PolicyChangeset(
            ai.intentoms.learning.model.ArtifactRef.parse("decision:fulfilment_intent"), 3,
            List.of(new ai.intentoms.learning.model.PolicyChange.ThresholdChange("fulfilment_intent", "ACT", 0.80, 0.92)),
            null, ai.intentoms.learning.model.PolicyChangeset.RiskTier.T1);

        LoggedRecord stillActs = new LoggedRecord("ship_complete", 1.0, 0.95, "ACT", 1.0);
        LoggedRecord noLongerActs = new LoggedRecord("ship_complete", 1.0, 0.85, "ACT", 1.0);

        assertThat(ProposedArmSimulator.proposedArm(stillActs, cs)).isEqualTo("ship_complete");
        assertThat(ProposedArmSimulator.proposedArm(noLongerActs, cs)).isNull();
    }
}
