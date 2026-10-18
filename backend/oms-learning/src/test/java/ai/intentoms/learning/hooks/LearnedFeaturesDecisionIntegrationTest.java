package ai.intentoms.learning.hooks;

import ai.intentoms.decision.definition.DecisionDefinitionLoader;
import ai.intentoms.decision.definition.DecisionDefinitionRepository;
import ai.intentoms.decision.model.DecisionDefinition;
import ai.intentoms.decision.model.DecisionOptions;
import ai.intentoms.decision.model.DecisionRecord;
import ai.intentoms.decision.model.DecisionResult;
import ai.intentoms.decision.runtime.DecisionRuntime;
import ai.intentoms.decision.runtime.DecisionService;
import ai.intentoms.learning.governance.LearnerGovernance;
import ai.intentoms.learning.governance.ParameterResolver;
import ai.intentoms.learning.model.AutonomyLevel;
import ai.intentoms.learning.model.FactKey;
import ai.intentoms.learning.model.LearnedFact;
import ai.intentoms.learning.model.SubjectType;
import ai.intentoms.learning.model.TypedValue;
import ai.intentoms.learning.store.LearnedFactCache;
import ai.intentoms.learning.store.LearnedFactRepository;
import ai.intentoms.platform.ids.Ids;
import ai.intentoms.platform.ledger.Actor;
import ai.intentoms.testing.PostgresTestBase;
import ai.intentoms.testing.TestApplication;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * NF-01: decide p95 stays within 300 ms with learned features, and the feature lookup itself adds at most 10 ms p95
 * from the cache. NF-04: a decision is replayable from its record plus the fact and policy versions it read: the
 * recorded input already carries the learned values (with versions in details), so a replay reproduces the answer even
 * after the facts have moved on.
 */
@SpringBootTest(classes = TestApplication.class)
@ActiveProfiles("test")
class LearnedFeaturesDecisionIntegrationTest extends PostgresTestBase {
    private static final Path DEMO_DECISIONS = Path.of("..", "..", "config", "tenants", "demo", "decisions").toAbsolutePath().normalize();
    private static final String DECISION = NodeReliabilityFeatures.DECISION;
    private static final int NODES = 40;

    @Autowired DecisionDefinitionLoader loader;
    @Autowired DecisionDefinitionRepository definitions;
    @Autowired DecisionService decisions;
    @Autowired DecisionRuntime runtime;
    @Autowired ParameterResolver parameters;
    @Autowired LearnerGovernance governance;
    @Autowired LearnedFactRepository facts;
    @Autowired LearnedFactCache cache;
    @Autowired LearningDecisionHooks hooks;

    private String prefix;

    @BeforeEach
    void seed() {
        loader.loadDirectory(DEMO_DECISIONS);
        governance.setTenantKillSwitch(false, "user:tester");
        governance.setEnabled("node_reliability", true, "user:tester");
        governance.resume("node_reliability", "user:tester");
        governance.setAutonomy("node_reliability", AutonomyLevel.L1_RECOMMEND, "user:tester");
        parameters.promote(NodeReliabilityFeatures.REJECT_RATE, "node_reliability", 0.0, 1.0, null, null, "user:tester");
        parameters.promote(NodeReliabilityFeatures.ON_TIME_RATE, "node_reliability", 0.0, 1.0, null, null, "user:tester");
        prefix = "PERF" + Ids.shortId() + "-";
        for (int i = 0; i < NODES; i++) {
            fact(prefix + i, FactKey.NODE_REJECT_RATE, 0.01, 1);
            fact(prefix + i, FactKey.NODE_ON_TIME_RATE, 0.95, 1);
        }
        cache.invalidateAll();
    }

    private void fact(String node, FactKey key, double v, int version) {
        Instant now = Instant.now();
        facts.supersede(SubjectType.NODE, node, key, now);
        facts.insert(new LearnedFact(Ids.prefixed("fact"), SubjectType.NODE, node, key, new TypedValue.NumberValue(v), 0.9, 50, "node_reliability", version, now, null, now));
    }

    private Map<String, Object> input() {
        List<Map<String, Object>> cands = new ArrayList<>();
        for (int i = 0; i < NODES; i++) {
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("id", prefix + i);
            c.put("type", i % 3 == 0 ? "store" : "dc");
            c.put("backlog_units", 10 * i);
            c.put("reject_rate", 0.05);
            c.put("staffing", 1.0);
            c.put("minutes_to_cutoff", 30 + 5 * i);
            c.put("distance_km", 20.0 + 7 * i);
            c.put("cost_usd", 6.5 + 0.3 * i);
            cands.add(c);
        }
        return Map.of("order", Map.of("destination_state", "NJ", "ship_method", "ground", "line_count", 2), "candidates", cands);
    }

    private static long p95(long[] nanos) {
        long[] s = nanos.clone();
        Arrays.sort(s);
        return s[(int) Math.ceil(0.95 * s.length) - 1];
    }

    @Test
    @SuppressWarnings("unchecked")
    void decideWithLearnedFeaturesStaysWithinTheLatencyBudgetAndRecordsWhatItUsed() {
        Map<String, Object> input = input();
        for (int i = 0; i < 10; i++) decisions.decide(DECISION, input, DecisionOptions.live("order", "ord_perf_warm_" + i, Actor.system("nf01")));

        int runs = 100;
        long[] decide = new long[runs];
        DecisionResult last = null;
        for (int i = 0; i < runs; i++) {
            long t = System.nanoTime();
            last = decisions.decide(DECISION, input, DecisionOptions.live("order", "ord_perf_" + i, Actor.system("nf01")));
            decide[i] = System.nanoTime() - t;
        }
        long[] lookup = new long[300];
        for (int i = 0; i < lookup.length; i++) {
            long t = System.nanoTime();
            hooks.contribute(DECISION, input, DecisionOptions.live("order", "ord_lookup", Actor.system("nf01")));
            lookup[i] = System.nanoTime() - t;
        }
        long decideP95Ms = p95(decide) / 1_000_000, lookupP95Ms = p95(lookup) / 1_000_000;
        System.out.printf("NF-01: decide p95 %d ms over %d runs with %d candidates (%d learned facts each); feature lookup p95 %.2f ms; fact cache hit rate %.3f%n",
            decideP95Ms, runs, NODES, NODES * 2, p95(lookup) / 1e6, cache.hitRate());
        assertThat(decideP95Ms).isLessThanOrEqualTo(300);
        assertThat(lookupP95Ms).isLessThanOrEqualTo(10);

        DecisionRecord rec = decisions.record(last.recordId());
        List<Map<String, Object>> used = (List<Map<String, Object>>) rec.details().get("learnedFacts");
        assertThat(used).hasSize(NODES * 2).allSatisfy(u -> assertThat(u.get("source")).isEqualTo("LEARNED"));
        Map<String, Object> firstCandidate = ((List<Map<String, Object>>) rec.input().get("candidates")).get(0);
        assertThat(firstCandidate).containsEntry("reject_rate", 0.01).containsEntry("on_time_rate", 0.95);
    }

    @Test
    @SuppressWarnings("unchecked")
    void aRecordedDecisionReplaysIdenticallyAfterTheFactsMoveOn() {
        DecisionResult original = decisions.decide(DECISION, input(), DecisionOptions.live("order", "ord_replay_1", Actor.system("nf04")));
        DecisionRecord rec = decisions.record(original.recordId());
        Map<String, Object> usedFirst = ((List<Map<String, Object>>) rec.details().get("learnedFacts")).get(0);
        assertThat(usedFirst).containsEntry("version", 1).containsKey("factKey");

        fact(prefix + "0", FactKey.NODE_REJECT_RATE, 0.30, 2);
        cache.invalidateAll();
        DecisionResult later = decisions.decide(DECISION, input(), DecisionOptions.live("order", "ord_replay_2", Actor.system("nf04")));
        DecisionRecord laterRec = decisions.record(later.recordId());
        assertThat(((List<Map<String, Object>>) laterRec.details().get("learnedFacts")))
            .anySatisfy(u -> { assertThat(u.get("subjectId")).isEqualTo(prefix + "0"); assertThat(u.get("factKey")).isEqualTo("NODE_REJECT_RATE"); assertThat(u.get("version")).isEqualTo(2); });

        DecisionDefinition def = definitions.find(DECISION, rec.version()).map(DecisionDefinitionRepository.Row::definition).orElseThrow();
        DecisionResult replay = runtime.evaluate(def, rec.input(), DecisionOptions.simulation("order", "ord_replay_1", rec.version()));
        assertThat(replay.value()).isEqualTo(rec.value());
        assertThat(replay.band()).isEqualTo(rec.band());
        assertThat(replay.confidence()).isEqualTo(rec.confidence());
        assertThat(replay.answers().keySet()).isEqualTo(rec.answers().keySet());
        rec.answers().forEach((k, a) -> assertThat(replay.answers().get(k).value()).as(k).isEqualTo(a.value()));
        assertThat(((List<Map<String, Object>>) rec.input().get("candidates")).get(0)).containsEntry("reject_rate", 0.01);   // the record still holds what it read
    }
}
