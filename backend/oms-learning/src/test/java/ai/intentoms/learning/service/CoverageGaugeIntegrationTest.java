package ai.intentoms.learning.service;

import ai.intentoms.learning.model.Episode;
import ai.intentoms.learning.model.OutcomeData;
import ai.intentoms.learning.model.OutcomeRecord;
import ai.intentoms.learning.store.EpisodeRepository;
import ai.intentoms.learning.store.OutcomeRepository;
import ai.intentoms.platform.ids.Ids;
import ai.intentoms.platform.tenant.TenantContext;
import ai.intentoms.testing.PostgresTestBase;
import ai.intentoms.testing.TestApplication;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** Defect 20: outcome coverage is exported as a gauge holding the latest ratio, not a counter that adds ratios up. */
@SpringBootTest(classes = TestApplication.class)
@ActiveProfiles("test")
class CoverageGaugeIntegrationTest extends PostgresTestBase {
    @Autowired CoverageMonitor coverage;
    @Autowired EpisodeRepository episodes;
    @Autowired OutcomeRepository outcomes;
    @Autowired MeterRegistry registry;

    @Test
    void coverageIsAGaugeOfTheLatestRatio() {
        String tenant = "cov-" + Ids.shortId().toLowerCase();
        jdbc.sql("insert into tenant(tenant_id, name) values (:t, :t) on conflict do nothing").param("t", tenant).update();
        TenantContext.runAs(tenant, () -> {
            Instant at = Instant.now().minus(Duration.ofDays(5));
            for (int i = 0; i < 25; i++) {
                String order = "ord-cov-" + i;
                Episode e = Episode.open(Ids.prefixed("epi"), "dr-cov-" + i, "sourcing_node_score", order, null, null, 1, "order", order, at);
                episodes.upsertDecisionPoint(e);
                if (i < 20) outcomes.insert(OutcomeRecord.of(Ids.prefixed("out"), e.episodeId(), new OutcomeData.DeliveryOutcome(true, 0, false, false), at.plusSeconds(3600), "ev-cov-" + i, Instant.now()));
            }
            coverage.sweepOnce();
            coverage.sweepOnce();
            assertThat(coverage.latest()).containsKey("sourcing_node_score");
            double gauge = registry.get("oms.learning.decision.coverage").tags("tenant", tenant, "decision", "sourcing_node_score").gauge().value();
            assertThat(gauge).isCloseTo(0.8, within(1e-9));
        });
    }
}
