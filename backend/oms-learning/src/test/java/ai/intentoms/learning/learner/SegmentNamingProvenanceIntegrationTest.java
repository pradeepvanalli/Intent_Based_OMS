package ai.intentoms.learning.learner;

import ai.intentoms.learning.model.FactKey;
import ai.intentoms.learning.model.LearnedFact;
import ai.intentoms.learning.model.Segment;
import ai.intentoms.learning.model.SubjectType;
import ai.intentoms.learning.model.TypedValue;
import ai.intentoms.learning.store.LearnedFactRepository;
import ai.intentoms.platform.ids.Ids;
import ai.intentoms.testing.PostgresTestBase;
import ai.intentoms.testing.TestApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/** CI-02: segment provenance names who actually named each segment: the analyst, or the template and why. */
@SpringBootTest(classes = {TestApplication.class, SegmentNamingProvenanceIntegrationTest.AnalystConfig.class})
@ActiveProfiles("test")
class SegmentNamingProvenanceIntegrationTest extends PostgresTestBase {
    static final AtomicBoolean ANSWER = new AtomicBoolean(true);

    /** Registered only by this class (no stereotype annotation, so component scans elsewhere never pick it up). */
    static class AnalystConfig {
        @Bean SegmentNamer analystNamer() {
            return (profiles, overall) -> {
                if (!ANSWER.get()) return Optional.empty();
                List<SegmentNamer.Name> out = new ArrayList<>();
                for (SegmentNamer.Profile p : profiles) out.add(new SegmentNamer.Name("Analyst segment " + p.index(), "Named by the analyst"));
                return Optional.of(out);
            };
        }
    }

    @Autowired CiSegmentDiscovery discovery;
    @Autowired LearnedFactRepository facts;

    private void population() {
        String run = Ids.shortId();
        Instant now = Instant.now();
        for (int i = 0; i < 80; i++) {
            String c = "segn-" + run + "-" + i;
            boolean fast = i < 40;
            put(c, FactKey.SPLIT_TOLERANCE, new TypedValue.NumberValue(fast ? 0.9 : 0.1), now);
            put(c, FactKey.SPEED_VS_COST, new TypedValue.NumberValue(fast ? 0.8 : -0.8), now);
        }
    }

    @Test
    void analystNamesAreLabelledAsTheAnalystsAndAFallbackSaysWhy() {
        population();
        ANSWER.set(true);
        List<Segment> named = discovery.discoverForTenant();
        assertThat(named).isNotEmpty().allSatisfy(s -> {
            assertThat(s.label()).startsWith("Analyst segment");
            assertThat(s.definition()).containsEntry("namedBy", "analyst").doesNotContainKey("templateReason");
            assertThat((List<?>) s.definition().get("traits")).isNotEmpty();
        });

        ANSWER.set(false);
        List<Segment> fallback = discovery.discoverForTenant();
        assertThat(fallback).isNotEmpty().allSatisfy(s -> {
            assertThat(s.label()).endsWith("customers");
            assertThat(s.definition()).containsEntry("namedBy", "template");
            assertThat(String.valueOf(s.definition().get("templateReason"))).startsWith("analyst unavailable");
        });
        ANSWER.set(true);
    }

    private void put(String customer, FactKey key, TypedValue v, Instant at) {
        facts.supersede(SubjectType.CUSTOMER, customer, key, at);
        facts.insert(new LearnedFact(Ids.prefixed("fact"), SubjectType.CUSTOMER, customer, key, v, 0.8, 5, CiPreferenceLearner.KEY, 1, at, null, at));
    }
}
