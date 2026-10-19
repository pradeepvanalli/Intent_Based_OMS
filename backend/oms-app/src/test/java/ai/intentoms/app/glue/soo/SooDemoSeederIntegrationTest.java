package ai.intentoms.app.glue.soo;

import ai.intentoms.IntentOmsApplication;
import ai.intentoms.learning.experiment.ExperimentManager;
import ai.intentoms.learning.governance.LearnerGovernance;
import ai.intentoms.learning.governance.LearnerRegistry;
import ai.intentoms.learning.governance.LiveFamilies;
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
    @Autowired LearnerRegistry registry;

    @Test
    void demoTenantIsSeededOnceThroughTheRealServices() {
        TenantContext.runAs(DEMO, () -> {
            Map<String, Object> marker = seeder.marker().orElseThrow();
            assertThat(marker).containsEntry("complete", true).containsEntry("version", SooDemoSeeder.VERSION);

            // Every proposal status, including the deterministic ones the UI and Playwright assert on.
            Map<ProposalStatus, String> expected = Map.of(ProposalStatus.PROMOTED, SooDemoSeeder.PROP_SOURCING_THRESHOLD,
                ProposalStatus.ROLLED_BACK, SooDemoSeeder.PROP_WEIGHTS, ProposalStatus.APPROVED, SooDemoSeeder.PROP_RETURN_THRESHOLD,
                ProposalStatus.EVALUATED, SooDemoSeeder.PROP_LIVE_RESERVATION_TTL, ProposalStatus.REJECTED, SooDemoSeeder.PROP_APPEASEMENT,
                ProposalStatus.CANARY, SooDemoSeeder.PROP_SLA_CANARY);
            expected.forEach((st, id) -> assertThat(proposals.get(id).status()).as(id).isEqualTo(st));
            for (ProposalStatus st : ProposalStatus.values()) assertThat(proposals.byStatus(st.name(), 50)).as(st.name()).isNotEmpty();
            assertThat(proposals.get(SooDemoSeeder.PROP_LIVE_LANE_CALIBRATION).status()).isEqualTo(ProposalStatus.PROMOTED);

            // Defect 19: every governance step ran as a labelled demo actor, never a real user.
            assertThat(proposals.get(SooDemoSeeder.PROP_SOURCING_THRESHOLD).createdBy()).isEqualTo("system:demo-seed.band_threshold");
            assertThat(proposals.get(SooDemoSeeder.PROP_SOURCING_THRESHOLD).approvedBy()).isEqualTo(SooDemoSeeder.STRATEGY);
            assertThat(proposals.get(SooDemoSeeder.PROP_LIVE_NODE_REJECT).createdBy()).isEqualTo(SooDemoSeeder.STRATEGY);
            assertThat(proposals.get(SooDemoSeeder.PROP_LIVE_NODE_REJECT).approvedBy()).isEqualTo(SooDemoSeeder.ADMIN);
            long realUserEvents = jdbc.sql("""
                select count(*) from event where tenant_id = :t and aggregate_type = 'learning' and actor in ('user:admin', 'user:strategy')""")
                .param("t", DEMO).query(Long.class).single();
            assertThat(realUserEvents).as("learning ledger events attributed to real users by the seeder").isZero();
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
            assertThat(learners.values().stream().filter(LearnerState::paused).map(LearnerState::learnerKey))
                .as("paused: %s", learners.values().stream().filter(LearnerState::paused).map(l -> l.learnerKey() + "=" + l.pausedReason()).toList()).containsExactly("fraud_threshold_tuner");

            assertThat(liveParameters.all().stream().filter(LiveParameterRepository.LiveParameter::live).map(LiveParameterRepository.LiveParameter::parameter))
                .contains("sourcing.node_reject_rate", "sourcing.node_on_time_rate", "promise.lane_transit_days", "promise.lane_calibration", "sourcing.policy_bandit",
                    "sourcing.reject_penalty", "customer.preferences", "sourcing.node_backlog_breach", "monitoring.warn_early_threshold", "promise.buffer_days",
                    "fraud.signal_risk")
                .doesNotContain("inventory.reservation_ttl", "decision.calibration", "fraud.agent_reputation", "fraud.agent_dispute_rate");

            // B-1: every seeded live range sits inside its family's domain, and a learner feeding the family has learned something.
            for (LiveParameterRepository.LiveParameter p : liveParameters.all().stream().filter(LiveParameterRepository.LiveParameter::live).toList()) {
                assertThat(LiveFamilies.validate(p.parameter(), p.min(), p.max(), p.maxStep())).as("range of %s", p.parameter()).isEmpty();
                List<String> feeders = registry.descriptors().stream().filter(d -> d.liveParameters().contains(p.parameter())).map(d -> d.key()).toList();
                long facts = jdbc.sql("select count(*) from learned_fact where tenant_id = :t and learner in (:k) and valid_to is null")
                    .param("t", DEMO).param("k", feeders).query(Long.class).single();
                assertThat(facts).as("learned values behind live %s (learners %s)", p.parameter(), feeders).isPositive();
            }
            // Warn-early is a 0-4 breach-risk score: the seeded range must not warn on the default score of 1 (audit B-1 flood).
            var warn = liveParameters.find("monitoring.warn_early_threshold").orElseThrow();
            assertThat(warn.min()).isGreaterThan(1.0);
            assertThat(warn.max()).isLessThanOrEqualTo(4.0);
            assertThat(marker).containsKey("skipped");

            // Reference data the learners need: matured fraud labels (FD-01, FD-03), labelled resolutions (DF-02), lane
            // samples with a moderate stated-vs-actual gap (CX-01), storefront impressions (CX-02).
            assertThat(factCount("FRAUD_SIGNAL_RATE")).as("fraud signal rates from the matured cohort").isGreaterThanOrEqualTo(5);
            assertThat(jdbc.sql("select count(*) from proposal where tenant_id = :t and created_by = 'system:fraud_threshold_tuner'").param("t", DEMO).query(Long.class).single())
                .as("the fraud threshold tuner proposed from the matured cohort").isPositive();
            assertThat(jdbc.sql("select count(*) from learned_fact where tenant_id = :t and fact_key = 'CALIBRATION_CURVE' and subject_id = 'sla_breach_risk' and valid_to is null")
                .param("t", DEMO).query(Long.class).single()).as("calibration curve from labelled breach-risk resolutions").isEqualTo(1);
            Map<String, Object> label = jdbc.sql("select labels->'human_resolution' as l from decision_record where tenant_id = :t and id = 'dr-demo-sla_breach_risk-demo-esc-001'")
                .param("t", DEMO).query((rs, i) -> ai.intentoms.platform.json.Json.readMap(rs.getString("l"))).single();
            assertThat(label).containsKeys("value", "outcome", "reasonCode", "recommendedValue", "resolvedBy");
            List<Double> offsets = jdbc.sql("select (value->>'value')::float from learned_fact where tenant_id = :t and fact_key = 'LANE_CALIBRATION_OFFSET' and valid_to is null")
                .param("t", DEMO).query(Double.class).list();
            assertThat(offsets).as("lane corrections").hasSizeGreaterThanOrEqualTo(5);
            // The seeded range is -0.3..0.3: most lanes' corrections fall inside it, and most are a real correction (5 points or more).
            assertThat(offsets.stream().filter(o -> Math.abs(o) <= 0.3).count()).as("corrections inside the seeded range: %s", offsets)
                .isGreaterThanOrEqualTo(Math.round(offsets.size() * 0.8));
            assertThat(offsets.stream().filter(o -> Math.abs(o) >= 0.05).count()).as("lanes with a correction of 5 points or more: %s", offsets)
                .isGreaterThanOrEqualTo(offsets.size() / 2);
            assertThat(factCount("PROMISE_BUFFER_DAYS")).as("promise buffers from storefront impressions").isPositive();

            // §12 north star: whole-order episodes carry the perfect component, a mix of 1 and 0, holdout included.
            Map<String, Object> perfect = jdbc.sql("""
                select count(*) filter (where (e.reward_components->>'perfect')::float = 1) as ones, count(*) filter (where (e.reward_components->>'perfect')::float = 0) as zeros,
                       count(distinct e.order_id) filter (where d.details->'experiment'->>'arm' = 'holdout') as holdout_orders
                from episode e join decision_record d on d.tenant_id = e.tenant_id and d.id = e.decision_id
                where e.tenant_id = :t and e.reward_components->>'perfect' is not null""").param("t", DEMO).query().singleRow();
            assertThat(((Number) perfect.get("ones")).longValue()).isPositive();
            assertThat(((Number) perfect.get("zeros")).longValue()).isPositive();
            assertThat(((Number) perfect.get("holdout_orders")).longValue()).as("holdout orders with a perfect-order outcome").isGreaterThanOrEqualTo(30);

            // FS-04: the seeded weight change was drafted by the (template) Author from the weight learner's hypothesis.
            assertThat(proposals.get(SooDemoSeeder.PROP_WEIGHTS).createdBy()).isEqualTo(SooDemoSeeder.TEMPLATE_AUTHOR);

            // The T0 path: replay above zero and a passed shadow from a real shadow experiment.
            var t0 = proposals.get(SooDemoSeeder.PROP_T0_EXCEPTION);
            assertThat(t0.status()).isEqualTo(ProposalStatus.EVALUATED);
            assertThat(t0.tier().name()).isEqualTo("T0");
            @SuppressWarnings("unchecked") Map<String, Object> ci = (Map<String, Object>) t0.evaluationReport().get("confidenceInterval");
            assertThat(((Number) ci.get("lower")).doubleValue()).as("T0 replay lower bound in %s", t0.evaluationReport()).isGreaterThan(0.0);
            @SuppressWarnings("unchecked") Map<String, Object> shadow = (Map<String, Object>) t0.evaluationReport().get("shadow");
            assertThat(shadow).as("shadow verdict").containsEntry("passed", true);

            // The holdout is tagged by the live router's own assignment and big enough for the lift report.
            String holdoutId = experiments.running().stream().filter(e -> e.mode().name().equals("HOLDOUT")).findFirst().orElseThrow().experimentId();
            long holdoutEpisodes = jdbc.sql("""
                select count(*) from episode e join decision_record d on d.tenant_id = e.tenant_id and d.id = e.decision_id
                where e.tenant_id = :t and e.reward is not null and d.details->'experiment'->>'arm' = 'holdout' and d.details->'experiment'->>'experimentId' = :h""")
                .param("t", DEMO).param("h", holdoutId).query(Long.class).single();
            assertThat(holdoutEpisodes).as("rewarded holdout episodes for the lift report").isGreaterThanOrEqualTo(30);

            // FD-05 for real: the agent-reputation learner scored the agents from seeded mandates; the seeder wrote no facts in its name.
            assertThat(jdbc.sql("select count(*) from mandate where tenant_id = :t and mandate_id like 'mdt-demo-%'").param("t", DEMO).query(Long.class).single()).isGreaterThanOrEqualTo(29);
            Map<String, Object> rep = jdbc.sql("""
                select learner, sample_size, version from learned_fact where tenant_id = :t and subject_type = 'AGENT' and subject_id = 'shopbot-ucp'
                  and fact_key = 'AGENT_REPUTATION' and valid_to is null""").param("t", DEMO).query().singleRow();
            assertThat(rep).containsEntry("learner", "agent_reputation").containsEntry("sample_size", 14);
            assertThat(experiments.running()).extracting(e -> e.mode().name()).contains("CANARY", "HOLDOUT");
            assertThat(experiments.running()).allSatisfy(e -> assertThat(e.createdBy()).isEqualTo(SooDemoSeeder.SEED));
            var objective = objectives.live().orElseThrow();
            assertThat(objective.createdBy()).isEqualTo(SooDemoSeeder.STRATEGY);
            assertThat(objective.approvedBy()).isEqualTo(SooDemoSeeder.ADMIN);
            assertThat(objective.constraints()).containsKey("freezeWindows");

            // Idempotent: a second run changes nothing.
            long records = jdbc.sql("select count(*) from decision_record where tenant_id = :t and actor = 'system:demo-seed'").param("t", DEMO).query(Long.class).single();
            long proposalCount = jdbc.sql("select count(*) from proposal where tenant_id = :t").param("t", DEMO).query(Long.class).single();
            assertThat(seeder.seed(DEMO)).isEqualTo(marker);
            assertThat(jdbc.sql("select count(*) from decision_record where tenant_id = :t and actor = 'system:demo-seed'").param("t", DEMO).query(Long.class).single()).isEqualTo(records);
            assertThat(jdbc.sql("select count(*) from proposal where tenant_id = :t").param("t", DEMO).query(Long.class).single()).isEqualTo(proposalCount);
        });
    }

    private long factCount(String key) {
        return jdbc.sql("select count(*) from learned_fact where tenant_id = :t and fact_key = :k and valid_to is null").param("t", DEMO).param("k", key).query(Long.class).single();
    }
}
