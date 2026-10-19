package ai.intentoms.learning.experiment;

import ai.intentoms.decision.model.DecisionResult;
import ai.intentoms.learning.model.ArtifactRef;
import ai.intentoms.learning.model.Experiment;
import ai.intentoms.learning.model.ExperimentMode;
import ai.intentoms.learning.model.ExperimentStatus;
import ai.intentoms.learning.model.PolicyChange;
import ai.intentoms.learning.model.PolicyChangeset;
import ai.intentoms.learning.model.Proposal;
import ai.intentoms.learning.model.ProposalStatus;
import ai.intentoms.learning.store.ExperimentRepository;
import ai.intentoms.learning.support.DecisionFabricTestBase;
import ai.intentoms.platform.config.TenantConfigStore;
import ai.intentoms.platform.error.ConflictException;
import ai.intentoms.platform.error.ValidationException;
import ai.intentoms.platform.ids.Ids;
import ai.intentoms.platform.ledger.Actor;
import ai.intentoms.platform.security.Principal;
import ai.intentoms.platform.security.PrincipalAuthentication;
import ai.intentoms.platform.tenant.TenantContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.context.SecurityContextHolder;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * D-5 / EX-03 / EX-02: the tenant's floors on a canary's minimum duration and a shadow's sample size, which whoever
 * starts the experiment cannot go below; and the sequential test (mSPRT) deciding on realistic, noisy reward streams
 * through the real stop-rule path: live decisions routed by the experiment router, episodes with rewards built from the
 * §7 terms, and the scheduled experiment sweep. Each test runs in a tenant of its own.
 */
class CanaryFloorsAndSequentialStopIntegrationTest extends DecisionFabricTestBase {
    @Autowired ExperimentManager experiments;
    @Autowired ExperimentRepository experimentRows;
    @Autowired ExperimentTrafficRouter router;
    @Autowired TenantConfigStore config;

    private static final String TESTER = "user:tester";

    private void ownTenant(String prefix) {
        String tenant = prefix + "_" + Ids.shortId().toLowerCase();
        jdbc.sql("insert into tenant(tenant_id, name) values (:t, :t) on conflict do nothing").param("t", tenant).update();
        TenantContext.set(tenant);
        SecurityContextHolder.getContext().setAuthentication(new PrincipalAuthentication(
            new Principal(Principal.Type.USER, "tester", tenant, java.util.Set.of("admin"), java.util.Set.of("*"), 3)));
    }

    private Proposal thresholdProposal(String name, double to) {
        return proposal("system:band_threshold", new ArtifactRef("decision", name), liveVersion(name), PolicyChangeset.RiskTier.T1, ProposalStatus.EVALUATED,
            new PolicyChange.ThresholdChange(name, "act", 0.8, to));
    }

    private void backdate(Experiment e, Duration by) {
        jdbc.sql("update experiment set start_at = :s where experiment_id = :id")
            .param("s", Timestamp.from(e.startAt().minus(by))).param("id", e.experimentId()).update();
    }

    // ------------------------------------------------------------------ floors

    @Test
    void aCanaryStartedBelowTheTenantMinimumRunsAtLeastThatLongAndOnlyARollbackEndsItSooner() {
        ownTenant("d5c");
        String name = installProbe("d5_floor");
        Proposal p = thresholdProposal(name, 0.75);

        // The audit's request: minDurationDays 0. It starts, with the tenant's 7 days and a message saying so.
        Experiment e = experiments.start(p.proposalId(), ExperimentMode.CANARY, 0.1, Map.of("minDurationDays", 0), TESTER);
        assertThat(e.stopRule()).containsEntry("minDurationDays", 7);
        assertThat(e.stopRule().get("floorsApplied").toString()).contains("raised from 0 to 7 days").contains(ExperimentManager.CANARY_MIN_DAYS_KEY);
        assertThat(Duration.between(e.startAt(), e.endAt())).isGreaterThanOrEqualTo(Duration.ofDays(7));

        // Completing it now is refused; stop cannot be used to complete it with a result of the caller's choosing.
        assertThatThrownBy(() -> experiments.complete(e.experimentId(), Actor.parse(TESTER)))
            .isInstanceOf(ConflictException.class).hasMessageContaining("minimum 7 days");
        assertThatThrownBy(() -> experiments.stop(e.experimentId(), ExperimentStatus.COMPLETED, Map.of("msprt", Map.of("verdict", "TREATMENT_BETTER")), Actor.parse(TESTER)))
            .isInstanceOf(ValidationException.class).hasMessageContaining("completed only through complete");
        assertThat(experiments.get(e.experimentId()).status()).isEqualTo(ExperimentStatus.RUNNING);
        assertThat(proposals.get(p.proposalId()).evaluationReport()).doesNotContainKey("canary");

        // A canary stored with minDurationDays 0 before the floor existed is held to the floor as well.
        Experiment legacy = new Experiment(Ids.prefixed("exp"), p.proposalId(), ExperimentMode.CANARY, 0.1, ExperimentStatus.RUNNING, Instant.now(), Instant.now().plus(Duration.ofDays(1)),
            Map.of("minDurationDays", 0), null, Instant.now(), "decision:" + name + "_legacy", name, e.candidateVersion(), e.baselineVersion(), TESTER);
        assertThat(experimentRows.insert(legacy)).isTrue();
        assertThatThrownBy(() -> experiments.complete(legacy.experimentId(), Actor.parse(TESTER))).isInstanceOf(ConflictException.class).hasMessageContaining("minimum 7 days");
        experiments.stop(legacy.experimentId(), ExperimentStatus.STOPPED, Map.of(), Actor.parse(TESTER));

        // Past its minimum it completes with its measured result.
        backdate(e, Duration.ofDays(7).plusMinutes(1));
        assertThat(experiments.complete(e.experimentId(), Actor.parse(TESTER)).status()).isEqualTo(ExperimentStatus.COMPLETED);
    }

    @Test
    void theTenantSetsItsOwnMinimumButNeverBelowOneDayAndALongerRequestIsKept() {
        ownTenant("d5t");
        config.set(ExperimentManager.CANARY_MIN_DAYS_KEY, 3);
        String a = installProbe("d5_t3");
        Experiment three = experiments.start(thresholdProposal(a, 0.75).proposalId(), ExperimentMode.CANARY, 0.1, Map.of("minDurationDays", 1), TESTER);
        assertThat(three.stopRule()).containsEntry("minDurationDays", 3);
        String b = installProbe("d5_t10");
        Experiment ten = experiments.start(thresholdProposal(b, 0.75).proposalId(), ExperimentMode.CANARY, 0.1, Map.of("minDurationDays", 10), TESTER);
        assertThat(ten.stopRule()).containsEntry("minDurationDays", 10).doesNotContainKey("floorsApplied");

        config.set(ExperimentManager.CANARY_MIN_DAYS_KEY, 0);
        String c = installProbe("d5_t0");
        Experiment one = experiments.start(thresholdProposal(c, 0.75).proposalId(), ExperimentMode.CANARY, 0.1, Map.of(), TESTER);
        assertThat(one.stopRule()).containsEntry("minDurationDays", 1);
        assertThatThrownBy(() -> experiments.complete(one.experimentId(), Actor.parse(TESTER))).isInstanceOf(ConflictException.class).hasMessageContaining("minimum 1 days");
    }

    @Test
    void aShadowCannotPassOnFewerDecisionsThanTheTenantMinimum() {
        ownTenant("d5s");
        String name = installProbe("d5_shadow");
        Proposal p = thresholdProposal(name, 0.75);
        Experiment e = experiments.start(p.proposalId(), ExperimentMode.SHADOW, 1.0, Map.of("minRecords", 0), TESTER);
        assertThat(e.stopRule()).containsEntry("minRecords", 30);
        assertThat(e.stopRule().get("floorsApplied").toString()).contains("raised from 0 to 30 decisions");

        Experiment done = experiments.complete(e.experimentId(), Actor.parse(TESTER));
        assertThat(done.result()).containsEntry("passed", false);
        assertThat(String.valueOf(done.result().get("reason"))).contains("need 30");
        @SuppressWarnings("unchecked") Map<String, Object> shadow = (Map<String, Object>) proposals.get(p.proposalId()).evaluationReport().get("shadow");
        assertThat(shadow).containsEntry("passed", false);
        assertThatThrownBy(() -> experiments.start(p.proposalId(), ExperimentMode.SHADOW, 1.0, Map.of("minRecords", -5), TESTER)).isInstanceOf(ValidationException.class);
    }

    // ------------------------------------------------------------------ the sequential test on realistic streams

    /**
     * One episode's reward under the demo objective's weights (fill 1.0, on time 1.3, cost 1.0, split 0.8, contacts
     * 1.0, aged stock 0.6), from per-term outcomes drawn the way orders actually vary: most fill completely, on-time
     * delivery is a coin with the arm's own probability, cost is 5-25% of the order value, some orders split, a few
     * customers ask where their order is or complain.
     */
    private static double reward(Random rnd, double onTime, double wismo) {
        double fill = rnd.nextDouble() < 0.95 ? 1.0 : 0.5;
        double otd = rnd.nextDouble() < onTime ? 1.0 : 0.0;
        double cost = 0.05 + rnd.nextDouble() * 0.20;
        double split = rnd.nextDouble() < 0.15 ? 0.5 : 0.0;
        double u = rnd.nextDouble();
        double cx = u < 0.03 ? 1.0 : u < 0.03 + wismo ? 0.5 : 0.0;
        double inv = rnd.nextDouble() < 0.10 ? 1.0 : 0.0;
        return 1.0 * fill + 1.3 * otd - 1.0 * cost - 0.8 * split - 1.0 * cx + 0.6 * inv;
    }

    /** Customers the router puts in the canary slice and customers it leaves on the live version, sticky per customer. */
    private List<List<String>> customers(String name, int baseline, int perArm) {
        List<String> canary = new ArrayList<>(), control = new ArrayList<>();
        for (int i = 0; canary.size() < perArm || control.size() < perArm; i++) {
            String key = "cust_" + i;
            var route = router.route(name, "customer:" + key, baseline);
            if (route.isPresent() && "canary".equals(route.get().arm())) { if (canary.size() < perArm) canary.add(key); }
            else if (route.isEmpty() && control.size() < perArm) control.add(key);
        }
        return List.of(canary, control);
    }

    private record Stream(int batches, Experiment experiment) {}

    /**
     * Streams orders in batches of {@code batch} per arm, rewarding each decision's episode, and runs the experiment
     * sweep after every batch, the way the scheduled job sees traffic arrive. Stops when the sweep ends the canary.
     */
    private Stream stream(String name, Experiment e, Random rnd, int batch, int maxPerArm, double[] canaryArm, double[] controlArm) {
        List<List<String>> keys = customers(name, e.baselineVersion(), maxPerArm);
        int sent = 0, batches = 0;
        while (sent < maxPerArm) {
            for (int i = sent; i < sent + batch; i++) {
                DecisionResult t = decideLive(name, "ord_t" + i + Ids.shortId(), 100, keys.get(0).get(i));
                assertThat(t.version()).isEqualTo(e.candidateVersion());
                rewardEpisode(records.find(t.recordId()).orElseThrow(), reward(rnd, canaryArm[0], canaryArm[1]), Map.of());
                DecisionResult c = decideLive(name, "ord_c" + i + Ids.shortId(), 100, keys.get(1).get(i));
                assertThat(c.version()).isEqualTo(e.baselineVersion());
                rewardEpisode(records.find(c.recordId()).orElseThrow(), reward(rnd, controlArm[0], controlArm[1]), Map.of());
            }
            sent += batch;
            batches++;
            experiments.sweepOnce();
            if (experiments.get(e.experimentId()).status() != ExperimentStatus.RUNNING) break;
        }
        return new Stream(batches, experiments.get(e.experimentId()));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> msprt(Experiment e) { return (Map<String, Object>) e.result().get("msprt"); }

    private static int n(Map<String, Object> msprt, String arm) { return ((Number) ((Map<String, Object>) msprt.get(arm)).get("n")).intValue(); }

    @Test
    void theSequentialTestStopsACanaryOnceEnoughTrafficShowsABetterCandidate() {
        ownTenant("d5m");
        String name = installProbe("d5_better");
        Proposal p = thresholdProposal(name, 0.75);
        Experiment e = experiments.start(p.proposalId(), ExperimentMode.CANARY, 0.5, Map.of("minPerArm", 30, "alpha", 0.05), TESTER);
        backdate(e, Duration.ofDays(8)); // past its 7-day minimum: only the sequential test decides when it ends

        // The candidate keeps 92% of promises instead of 78% and halves where-is-my-order contacts: about +0.2 reward.
        Stream s = stream(name, e, new Random(20261019L), 20, 400, new double[]{0.92, 0.05}, new double[]{0.78, 0.10});

        assertThat(s.experiment().status()).isEqualTo(ExperimentStatus.COMPLETED);
        Map<String, Object> m = msprt(s.experiment());
        assertThat(m.get("verdict")).isEqualTo("TREATMENT_BETTER");
        assertThat(s.batches()).as("it needs more than the first batch (minimum 30 per arm) and stops well before 400").isBetween(2, 19);
        assertThat(n(m, "treatment")).isGreaterThanOrEqualTo(30);
        assertThat(((Number) m.get("difference")).doubleValue()).isBetween(0.05, 0.40);
        Proposal after = proposals.get(p.proposalId());
        assertThat(after.status()).isEqualTo(ProposalStatus.EVALUATED);
        assertThat(after.evaluationReport()).containsKey("canary");
    }

    @Test
    void aCanaryWithNoRealDifferenceKeepsRunning() {
        ownTenant("d5n");
        String name = installProbe("d5_null");
        Experiment e = experiments.start(thresholdProposal(name, 0.75).proposalId(), ExperimentMode.CANARY, 0.5, Map.of("minPerArm", 30, "alpha", 0.05), TESTER);
        backdate(e, Duration.ofDays(8));

        Stream s = stream(name, e, new Random(7L), 25, 250, new double[]{0.82, 0.08}, new double[]{0.82, 0.08});

        assertThat(s.experiment().status()).isEqualTo(ExperimentStatus.RUNNING);
        assertThat(msprt(s.experiment()).get("verdict")).isEqualTo("CONTINUE");
        assertThat(n(msprt(s.experiment()), "treatment")).isEqualTo(250);
    }

    @Test
    void aWorseCandidateIsStoppedAndItsChangeRejected() {
        ownTenant("d5w");
        String name = installProbe("d5_worse");
        Proposal p = thresholdProposal(name, 0.75);
        Experiment e = experiments.start(p.proposalId(), ExperimentMode.CANARY, 0.5, Map.of("minPerArm", 30, "alpha", 0.05), TESTER);
        backdate(e, Duration.ofDays(8));

        Stream s = stream(name, e, new Random(42L), 20, 400, new double[]{0.70, 0.12}, new double[]{0.86, 0.06});

        assertThat(s.experiment().status()).isEqualTo(ExperimentStatus.COMPLETED);
        assertThat(msprt(s.experiment()).get("verdict")).isEqualTo("TREATMENT_WORSE");
        assertThat(proposals.get(p.proposalId()).status()).isEqualTo(ProposalStatus.REJECTED);
    }

    @Test
    void aDecidedCanaryStillRunsItsMinimumDurationBeforeTheSweepEndsIt() {
        ownTenant("d5d");
        String name = installProbe("d5_min");
        Proposal p = thresholdProposal(name, 0.75);
        Experiment e = experiments.start(p.proposalId(), ExperimentMode.CANARY, 0.5, Map.of("minPerArm", 30, "alpha", 0.05, "minDurationDays", 0), TESTER);

        Stream s = stream(name, e, new Random(11L), 50, 250, new double[]{0.95, 0.04}, new double[]{0.75, 0.10});

        // The test has decided, but the canary keeps running for the tenant's minimum, and nobody can complete it early.
        assertThat(s.experiment().status()).isEqualTo(ExperimentStatus.RUNNING);
        assertThat(msprt(s.experiment()).get("verdict")).isEqualTo("TREATMENT_BETTER");
        assertThatThrownBy(() -> experiments.complete(e.experimentId(), Actor.parse(TESTER))).isInstanceOf(ConflictException.class);

        backdate(e, Duration.ofDays(7).plusMinutes(1));
        experiments.sweepOnce();
        assertThat(experiments.get(e.experimentId()).status()).isEqualTo(ExperimentStatus.COMPLETED);
        assertThat(proposals.get(p.proposalId()).evaluationReport()).containsKey("canary");
    }
}
