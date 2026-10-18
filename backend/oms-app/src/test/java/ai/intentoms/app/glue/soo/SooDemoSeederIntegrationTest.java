package ai.intentoms.app.glue.soo;

import ai.intentoms.IntentOmsApplication;
import ai.intentoms.learning.experiment.ExperimentManager;
import ai.intentoms.learning.governance.LearnerGovernance;
import ai.intentoms.learning.governance.LiveParameterRepository;
import ai.intentoms.learning.model.AutonomyLevel;
import ai.intentoms.learning.model.LearnerState;
import ai.intentoms.learning.model.ProposalStatus;
import ai.intentoms.learning.store.EpisodeRepository;
import ai.intentoms.learning.store.ObjectiveProfileRepository;
import ai.intentoms.learning.store.ProposalRepository;
import ai.intentoms.platform.tenant.TenantContext;
import ai.intentoms.testing.PostgresTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The self-optimizing reference data on the demo tenant: seeded once at startup after the demo seed, populated through
 * the real services, and left alone by a second run. Shares the full-application context with IntentOmsApplicationTest.
 */
@SpringBootTest(classes = IntentOmsApplication.class, properties = {"oms.demo.seed=true"})
@ActiveProfiles("test")
class SooDemoSeederIntegrationTest extends PostgresTestBase {
    private static final String DEMO = "demo";

    @Autowired SooDemoSeeder seeder;
    @Autowired ProposalRepository proposals;
    @Autowired EpisodeRepository episodes;
    @Autowired LearnerGovernance governance;
    @Autowired LiveParameterRepository liveParameters;
    @Autowired ExperimentManager experiments;
    @Autowired ObjectiveProfileRepository objectives;

    @Test
    void demoTenantIsSeededOnceThroughTheRealServices() {
        TenantContext.runAs(DEMO, () -> {
            Map<String, Object> marker = seeder.marker().orElseThrow();
            assertThat(marker).containsEntry("complete", true).containsEntry("version", SooDemoSeeder.VERSION);

            // Every proposal status, including the deterministic ones the UI and Playwright assert on.
            Map<ProposalStatus, String> expected = Map.of(ProposalStatus.PROMOTED, SooDemoSeeder.PROP_SOURCING_THRESHOLD,
                ProposalStatus.ROLLED_BACK, SooDemoSeeder.PROP_WEIGHTS, ProposalStatus.APPROVED, SooDemoSeeder.PROP_RETURN_THRESHOLD,
                ProposalStatus.EVALUATED, SooDemoSeeder.PROP_LIVE_LANE_CALIBRATION, ProposalStatus.REJECTED, SooDemoSeeder.PROP_APPEASEMENT,
                ProposalStatus.CANARY, SooDemoSeeder.PROP_SLA_CANARY);
            expected.forEach((st, id) -> assertThat(proposals.get(id).status()).as(id).isEqualTo(st));
            for (ProposalStatus st : ProposalStatus.values()) assertThat(proposals.byStatus(st.name(), 50)).as(st.name()).isNotEmpty();
            assertThat(proposals.get(SooDemoSeeder.PROP_SOURCING_THRESHOLD).approvedBy()).isEqualTo("user:strategy");
            assertThat(proposals.get(SooDemoSeeder.PROP_LIVE_NODE_REJECT).createdBy()).isEqualTo("user:strategy");
            assertThat(proposals.get(SooDemoSeeder.PROP_LIVE_NODE_REJECT).approvedBy()).isEqualTo("user:admin");
            assertThat(proposals.rollbackSnapshot(SooDemoSeeder.PROP_LIVE_NODE_REJECT)).as("promoted through PromotionService, so rollback is available").isPresent();
            assertThat(proposals.get(SooDemoSeeder.PROP_SOURCING_THRESHOLD).evaluationReport()).containsKey("liftEstimate").containsKey("confidenceInterval");

            // OC-07: outcomes arrived for at least 80% of mature-enough episodes, except the deliberate address-check gap.
            Instant since = Instant.now().minus(Duration.ofDays(14)), observed = Instant.now().minus(Duration.ofDays(3));
            for (String d : List.of("sourcing_node_score", "sla_breach_risk", "payment_risk", "sourcing_policy_bandit")) {
                long[] c = episodes.coverage(d, since, observed);
                assertThat(c[0]).as(d + " eligible episodes").isGreaterThanOrEqualTo(20);
                assertThat((double) c[1] / c[0]).as(d + " coverage").isGreaterThanOrEqualTo(0.8);
            }
            long[] address = episodes.coverage("address_risk", since, observed);
            assertThat((double) address[1] / address[0]).as("address_risk coverage (silent data loss on purpose)").isLessThan(0.8);

            // Overrides stay rare on the seeded history (GuardrailBreachMonitor must never act on it).
            Map<String, Object> overrides = jdbc.sql("""
                select count(*) filter (where overridden_at is not null)::float / greatest(count(*), 1) as rate from decision_record
                where tenant_id = :t and actor = 'system:demo-seed'""").param("t", DEMO).query().singleRow();
            assertThat(((Number) overrides.get("rate")).doubleValue()).isLessThan(0.05);

            // Learners: two act with approval, the one reading address_risk was paused by the coverage monitor, the rest recommend.
            Map<String, LearnerState> learners = governance.states().stream().collect(Collectors.toMap(LearnerState::learnerKey, s -> s));
            assertThat(learners.get("node_reliability").autonomyLevel()).isEqualTo(AutonomyLevel.L2_ACT_WITH_APPROVAL);
            assertThat(learners.get("lane_transit").autonomyLevel()).isEqualTo(AutonomyLevel.L2_ACT_WITH_APPROVAL);
            assertThat(learners.get("fraud_threshold_tuner").pausedReason()).startsWith("outcome coverage for address_risk");
            assertThat(learners.values().stream().filter(LearnerState::paused).map(LearnerState::learnerKey)).containsExactly("fraud_threshold_tuner");

            assertThat(liveParameters.all().stream().filter(LiveParameterRepository.LiveParameter::live).map(LiveParameterRepository.LiveParameter::parameter))
                .contains("sourcing.node_reject_rate", "sourcing.node_on_time_rate", "promise.lane_transit_days");
            assertThat(experiments.running()).extracting(e -> e.mode().name()).contains("CANARY", "HOLDOUT");
            assertThat(experiments.running()).allSatisfy(e -> assertThat(e.createdBy()).isEqualTo(SooDemoSeeder.SEED));
            var objective = objectives.live().orElseThrow();
            assertThat(objective.createdBy()).isEqualTo("user:strategy");
            assertThat(objective.approvedBy()).isEqualTo("user:admin");
            assertThat(objective.constraints()).containsKey("freezeWindows");

            // Idempotent: a second run changes nothing.
            long records = jdbc.sql("select count(*) from decision_record where tenant_id = :t and actor = 'system:demo-seed'").param("t", DEMO).query(Long.class).single();
            long proposalCount = jdbc.sql("select count(*) from proposal where tenant_id = :t").param("t", DEMO).query(Long.class).single();
            assertThat(seeder.seed(DEMO)).isEqualTo(marker);
            assertThat(jdbc.sql("select count(*) from decision_record where tenant_id = :t and actor = 'system:demo-seed'").param("t", DEMO).query(Long.class).single()).isEqualTo(records);
            assertThat(jdbc.sql("select count(*) from proposal where tenant_id = :t").param("t", DEMO).query(Long.class).single()).isEqualTo(proposalCount);
        });
    }
}
