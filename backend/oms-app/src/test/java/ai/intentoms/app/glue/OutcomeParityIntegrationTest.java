package ai.intentoms.app.glue;

import ai.intentoms.IntentOmsApplication;
import ai.intentoms.learning.fairness.FairnessReportService;
import ai.intentoms.learning.model.Episode;
import ai.intentoms.learning.model.EpisodeStatus;
import ai.intentoms.learning.model.FactKey;
import ai.intentoms.learning.model.LearnedFact;
import ai.intentoms.learning.model.Segment;
import ai.intentoms.learning.model.SubjectType;
import ai.intentoms.learning.model.TypedValue;
import ai.intentoms.learning.store.EpisodeRepository;
import ai.intentoms.learning.store.LearnedFactRepository;
import ai.intentoms.learning.store.SegmentRepository;
import ai.intentoms.platform.ids.Ids;
import ai.intentoms.testing.PostgresTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** GV-07: parity covers outcomes too: a segment whose orders arrive on time far less often than the rest is flagged. */
@SpringBootTest(classes = IntentOmsApplication.class, properties = {"oms.demo.seed=false"})
@ActiveProfiles("test")
class OutcomeParityIntegrationTest extends PostgresTestBase {
    @Autowired FairnessReportService fairness;
    @Autowired LearnedFactRepository facts;
    @Autowired SegmentRepository segments;
    @Autowired EpisodeRepository episodes;

    @Test
    void aSegmentWithWorseDeliveryOutcomesIsFlagged() {
        String decision = "parity_" + Ids.shortId().toLowerCase();
        String good = Ids.prefixed("seg"), poor = Ids.prefixed("seg");
        segments.insert(new Segment(good, 1, "City customers", "test", Map.of(), 12, "system:test", Instant.now()));
        segments.insert(new Segment(poor, 1, "Rural customers", "test", Map.of(), 12, "system:test", Instant.now()));
        for (int i = 0; i < 12; i++) {
            customerWithOrder("pc-" + decision + "-" + i, good, decision, 1.0);
            customerWithOrder("pr-" + decision + "-" + i, poor, decision, i < 3 ? 1.0 : 0.0);
        }

        FairnessReportService.ParityReport r = fairness.report(decision, 90);

        FairnessReportService.GroupParity rural = r.groups().stream().filter(g -> g.group().equals(poor)).findFirst().orElseThrow();
        assertThat(rural.episodes()).isEqualTo(12);
        assertThat(rural.onTimeRate()).isEqualTo(0.25);
        assertThat(rural.meanReward()).isNotNull();
        assertThat(r.flags()).anyMatch(f -> f.startsWith("Rural customers: on-time rate"));
    }

    private void customerWithOrder(String customer, String segment, String decision, double onTime) {
        facts.insert(new LearnedFact(Ids.prefixed("fact"), SubjectType.CUSTOMER, customer, FactKey.SEGMENT_MEMBERSHIP, new TypedValue.TextValue(segment), 0.9, 10,
            "ci_segment_discovery", 1, Instant.now(), null, Instant.now()));
        String orderId = "ord_" + Ids.shortId();
        jdbc.sql("""
            insert into orders(id, tenant_id, channel, channel_order_id, order_type, customer_ref, customer, state_model_id, state_model_version, status, placed_at, search_text)
            values (:id, :t, 'web', :ch, 'standard_ship', :c, cast('{}' as jsonb), 'order', 1, 'delivered', now(), :id)
            """).param("id", orderId).param("t", TENANT).param("ch", "W-" + orderId).param("c", customer).update();
        String epi = Ids.prefixed("epi");
        episodes.upsertDecisionPoint(Episode.open(epi, "dr-" + epi, decision, orderId, null, null, 1, "order", orderId, Instant.now()));
        episodes.updateStatusAndReward(epi, EpisodeStatus.PROVISIONAL, onTime, Map.of("otd", onTime, "fill", 1.0), null);
    }
}
