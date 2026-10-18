package ai.intentoms.learning.fairness;

import ai.intentoms.decision.model.Band;
import ai.intentoms.decision.record.DecisionRecordRepository;
import ai.intentoms.learning.LearningFixtures;
import ai.intentoms.learning.model.FactKey;
import ai.intentoms.learning.model.LearnedFact;
import ai.intentoms.learning.model.Segment;
import ai.intentoms.learning.model.SubjectType;
import ai.intentoms.learning.model.TypedValue;
import ai.intentoms.learning.store.LearnedFactRepository;
import ai.intentoms.learning.store.SegmentRepository;
import ai.intentoms.platform.ids.Ids;
import ai.intentoms.testing.PostgresTestBase;
import ai.intentoms.testing.TestApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** GV-07: parity is reported per discovered segment, divergence is flagged, and no learner declares a protected feature. */
@SpringBootTest(classes = TestApplication.class)
@ActiveProfiles("test")
class FairnessReportIntegrationTest extends PostgresTestBase {
    @Autowired FairnessReportService fairness;
    @Autowired LearnedFactRepository facts;
    @Autowired SegmentRepository segments;
    @Autowired DecisionRecordRepository records;

    @Test
    void parityBySegmentFlagsAGroupHeldBackMoreThanTheRest() {
        String decision = "fair_test_" + Ids.shortId();
        String open = Ids.prefixed("seg"), held = Ids.prefixed("seg");
        segments.insert(new Segment(open, 1, "Frequent buyers", "test", Map.of(), 12, "system:test", Instant.now()));
        segments.insert(new Segment(held, 1, "New customers", "test", Map.of(), 12, "system:test", Instant.now()));
        for (int i = 0; i < 12; i++) {
            member("cf-" + decision + "-" + i, open, decision, Band.ACT);
            member("ch-" + decision + "-" + i, held, decision, i < 2 ? Band.ACT : Band.ESCALATE);
        }

        FairnessReportService.ParityReport r = fairness.report(decision, 90);

        assertThat(r.grouping()).isEqualTo("segment");
        assertThat(r.groups()).extracting(FairnessReportService.GroupParity::label).contains("Frequent buyers", "New customers");
        FairnessReportService.GroupParity heldGroup = r.groups().stream().filter(g -> g.group().equals(held)).findFirst().orElseThrow();
        assertThat(heldGroup.customers()).isEqualTo(12);
        assertThat(heldGroup.decisions()).isEqualTo(12);
        assertThat(heldGroup.actBandRate()).isLessThan(0.2);
        assertThat(r.flags()).anyMatch(f -> f.startsWith("New customers: act-band rate"));
        assertThat(r.featureAudit()).isNotEmpty().allSatisfy(a -> assertThat(a.protectedFeatures()).isEmpty());
    }

    private void member(String customer, String segment, String decision, Band band) {
        facts.insert(new LearnedFact(Ids.prefixed("fact"), SubjectType.CUSTOMER, customer, FactKey.SEGMENT_MEMBERSHIP, new TypedValue.TextValue(segment), 0.9, 10,
            "ci_segment_discovery", 1, Instant.now(), null, Instant.now()));
        records.insert(LearningFixtures.record(decision, "customer", customer, band, Map.of(), Instant.now()));
    }
}
