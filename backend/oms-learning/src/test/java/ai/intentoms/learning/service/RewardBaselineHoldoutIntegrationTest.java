package ai.intentoms.learning.service;

import ai.intentoms.decision.model.DecisionRecord;
import ai.intentoms.learning.support.DecisionFabricTestBase;
import ai.intentoms.platform.ids.Ids;
import ai.intentoms.platform.tenant.TenantContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * §7: the scales come from the persistent holdout's episodes once it has enough, else from the tenant's own logged
 * episodes, else from documented defaults; every term always has an explicit scale with its source (audit
 * OBJ-normalisation).
 */
class RewardBaselineHoldoutIntegrationTest extends DecisionFabricTestBase {
    @Autowired RewardBaselineService baseline;

    private void ownTenant() {
        String tenant = "rbh_" + Ids.shortId().toLowerCase();
        jdbc.sql("insert into tenant(tenant_id, name) values (:t, :t) on conflict do nothing").param("t", tenant).update();
        TenantContext.set(tenant);
    }

    private void episodes(String arm, int n, double base, double step) {
        Instant at = Instant.now().minusSeconds(600);
        for (int i = 1; i <= n; i++) {
            DecisionRecord r = insertRecord("fulfilment_intent", 1, at.plusSeconds(i), "ship_complete", 0.9, 40, Map.of("experiment", Map.of("arm", arm)), null, "system:test");
            rewardEpisode(r, 0.5, Map.of("cost", base + i * step, "fill", 1.0));
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void theHoldoutSetsTheScaleOnceItHasThirtyEpisodes() {
        ownTenant();
        episodes("treatment", 40, 0.0, 0.005);  // learning has pushed cost down: 0.005..0.200
        episodes("holdout", 35, 0.2, 0.01);     // the baseline policy: 0.21..0.55
        Map<String, Object> stored = baseline.recompute();
        assertThat(stored).as("cost from the holdout, the rest identity or default").containsEntry("source", "mixed").containsEntry("holdoutEpisodes", 35L);
        assertThat((Map<String, String>) stored.get("sources")).containsEntry("cost", "holdout");
        assertThat((Map<String, Long>) stored.get("samples")).containsEntry("cost", 35L);
        assertThat(baseline.current().get("cost")).isCloseTo(0.533, within(0.001)); // percentile_cont(0.95) of 0.21..0.55
    }

    @Test
    @SuppressWarnings("unchecked")
    void withTooFewHoldoutEpisodesRecentEpisodesSetTheScale() {
        ownTenant();
        episodes("treatment", 40, 0.0, 0.005);
        episodes("holdout", 10, 0.2, 0.01);
        Map<String, Object> stored = baseline.recompute();
        assertThat(stored).containsEntry("holdoutEpisodes", 10L).containsEntry("loggedEpisodes", 50L);
        assertThat((Map<String, String>) stored.get("sources")).as("the replay baseline of the current policy").containsEntry("cost", "logged");
        assertThat((Map<String, Long>) stored.get("samples")).containsEntry("cost", 50L);
    }

    @Test
    @SuppressWarnings("unchecked")
    void everyTermHasAnExplicitScaleAndSourceEvenBeforeAnyHistory() {
        ownTenant();
        Map<String, Object> fresh = baseline.effective();
        assertThat(fresh).containsEntry("source", "default");
        assertThat((Map<String, Object>) fresh.get("terms")).containsOnlyKeys("fill", "otd", "cost", "split", "fraud", "cx", "inv");
        assertThat(baseline.current()).containsOnlyKeys("fill", "otd", "cost", "split", "fraud", "cx", "inv").containsEntry("inv", 30.0);

        episodes("treatment", 40, 0.0, 0.005);
        Map<String, Object> stored = baseline.recompute();
        Map<String, Map<String, Object>> terms = (Map<String, Map<String, Object>>) stored.get("terms");
        assertThat(terms).containsOnlyKeys("fill", "otd", "cost", "split", "fraud", "cx", "inv");
        assertThat(terms.get("fill")).containsEntry("scale", 1.0).containsEntry("method", "identity").containsEntry("source", "identity");
        assertThat((String) terms.get("fill").get("reason")).contains("already a share in [0,1]");
        assertThat(terms.get("otd")).containsEntry("scale", 1.0).containsEntry("method", "identity");
        assertThat(terms.get("cost")).containsEntry("method", "p95").containsEntry("source", "logged").containsEntry("samples", 40L);
        assertThat(terms.get("inv")).as("aged-stock relief has a scale too").containsEntry("scale", 30.0).containsEntry("source", "default");
        assertThat((String) terms.get("inv").get("reason")).contains("documented default").contains("30 days");
        assertThat(terms.get("fraud")).containsEntry("scale", 1.0).containsEntry("source", "default");
        assertThat(baseline.effective()).as("what was stored is what is read back").containsEntry("scales", stored.get("scales")).containsEntry("sources", stored.get("sources"));
    }
}
