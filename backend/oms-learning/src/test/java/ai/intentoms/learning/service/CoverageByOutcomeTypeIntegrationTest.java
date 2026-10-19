package ai.intentoms.learning.service;

import ai.intentoms.learning.governance.LearnerGovernance;
import ai.intentoms.learning.learner.LaneDeliveryLearner;
import ai.intentoms.learning.model.Episode;
import ai.intentoms.learning.store.EpisodeRepository;
import ai.intentoms.platform.config.TenantConfigStore;
import ai.intentoms.platform.ids.Ids;
import ai.intentoms.platform.tenant.TenantContext;
import ai.intentoms.testing.PostgresTestBase;
import ai.intentoms.testing.TestApplication;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * OC-07 per outcome type: carrier webhooks stop, contacts keep arriving, so every episode still has "an outcome", yet the
 * delivery outcomes the sourcing learners read are gone. Coverage per type catches it and pauses those learners.
 */
@SpringBootTest(classes = TestApplication.class)
@ActiveProfiles("test")
class CoverageByOutcomeTypeIntegrationTest extends PostgresTestBase {
    private static final String DECISION = "sourcing_node_score";
    @Autowired CoverageMonitor monitor;
    @Autowired EpisodeRepository episodes;
    @Autowired LearnerGovernance governance;
    @Autowired TenantConfigStore config;

    String tenant;

    @BeforeEach
    void ownTenant() {
        tenant = "oc7_" + Ids.shortId();
        jdbc.sql("insert into tenant(tenant_id, name) values (:t, :t) on conflict do nothing").param("t", tenant).update();
        TenantContext.set(tenant);
        governance.setTenantKillSwitch(false, "user:tester");
        governance.setEnabled(LaneDeliveryLearner.KEY, true, "user:tester");
        governance.resume(LaneDeliveryLearner.KEY, "user:tester");
    }

    private void episode(Instant at, String... outcomeTypes) {
        String id = Ids.prefixed("epi");
        String order = "ord_" + Ids.shortId();
        episodes.upsertDecisionPoint(Episode.open(id, Ids.prefixed("dr"), DECISION, order, null, null, 1, "order", order, at));
        jdbc.sql("update episode set created_at = :at where tenant_id = :t and episode_id = :e").param("at", Timestamp.from(at)).param("t", tenant).param("e", id).update();
        for (String type : outcomeTypes) {
            jdbc.sql("insert into outcome(outcome_id, tenant_id, episode_id, type, payload, observed_at) values (:o, :t, :e, :type, '{}'::jsonb, :at)")
                .param("o", Ids.prefixed("out")).param("t", tenant).param("e", id).param("type", type).param("at", Timestamp.from(at.plusSeconds(3600))).update();
        }
    }

    @Test
    void losingOneOutcomeFeedPausesTheLearnersEvenWhileOtherOutcomesKeepArriving() {
        Instant before = Instant.now().minus(Duration.ofDays(20));
        Instant recent = Instant.now().minus(Duration.ofDays(6));
        for (int i = 0; i < 30; i++) episode(before, "FulfillmentOutcome", "ContactOutcome");
        for (int i = 0; i < 30; i++) episode(recent, "ContactOutcome"); // delivery webhooks stopped

        monitor.sweepOnce();

        assertThat(monitor.latest()).containsEntry(DECISION, 1.0);           // every episode still has some outcome
        assertThat(monitor.latestByType()).containsEntry(DECISION + "|FulfillmentOutcome", 0.0).containsEntry(DECISION + "|ContactOutcome", 1.0);
        assertThat(governance.gate(LaneDeliveryLearner.KEY).mayRun()).isFalse();
        assertThat(governance.gate(LaneDeliveryLearner.KEY).reason()).contains("outcome coverage for " + DECISION + " (FulfillmentOutcome) is 0%");

        // The feed comes back: coverage recovers and the monitor resumes what it paused.
        for (int i = 0; i < 30; i++) episode(recent.plusSeconds(60), "FulfillmentOutcome", "ContactOutcome");
        config.set("learning.coverage.expected_outcomes." + DECISION, List.of("ContactOutcome"));
        try {
            monitor.sweepOnce();
        } finally {
            config.delete("learning.coverage.expected_outcomes." + DECISION);
        }
        assertThat(monitor.latestByType()).containsEntry(DECISION + "|ContactOutcome", 1.0);
        assertThat(governance.gate(LaneDeliveryLearner.KEY).mayRun()).isTrue();
    }
}
