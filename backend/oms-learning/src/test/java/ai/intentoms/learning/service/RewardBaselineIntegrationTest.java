package ai.intentoms.learning.service;

import ai.intentoms.learning.model.Episode;
import ai.intentoms.learning.model.EpisodeStatus;
import ai.intentoms.learning.store.EpisodeRepository;
import ai.intentoms.platform.config.TenantConfigStore;
import ai.intentoms.platform.ids.Ids;
import ai.intentoms.platform.tenant.TenantContext;
import ai.intentoms.testing.PostgresTestBase;
import ai.intentoms.testing.TestApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** §7: penalty terms are normalised against the tenant's own recent baseline (95th percentile of each raw term). */
@SpringBootTest(classes = TestApplication.class)
@ActiveProfiles("test")
class RewardBaselineIntegrationTest extends PostgresTestBase {
    @Autowired RewardBaselineService baseline;
    @Autowired EpisodeRepository episodes;
    @Autowired TenantConfigStore config;

    @Test
    void scalesComeFromTheTenantsOwnEpisodes() {
        String tenant = "baseline-" + Ids.shortId();
        jdbc.sql("insert into tenant(tenant_id, name) values (:t, :t)").param("t", tenant).update();
        TenantContext.runAs(tenant, () -> {
            for (int i = 1; i <= 40; i++) {
                String id = Ids.prefixed("epi");
                episodes.upsertDecisionPoint(Episode.open(id, "dr-" + id, "sourcing_node_score", "ord-" + i, null, null, 1, "order", "ord-" + i, Instant.now()));
                episodes.updateStatusAndReward(id, EpisodeStatus.PROVISIONAL, 1.0, Map.of("cost", i / 200.0, "split", 0.0, "fill", 1.0), null);
            }
            Map<String, Object> stored = baseline.recompute();
            assertThat(stored).containsKeys("scales", "samples", "computedAt");
            Map<String, Double> scales = baseline.current();
            assertThat(scales.get("cost")).isCloseTo(0.19025, within(0.0005)); // percentile_cont(0.95) of 0.005..0.200
            assertThat(scales).doesNotContainKey("split");                   // no nonzero samples
            assertThat(config.getMap(RewardBaselineService.KEY)).containsKey("scales");
        });
    }
}
