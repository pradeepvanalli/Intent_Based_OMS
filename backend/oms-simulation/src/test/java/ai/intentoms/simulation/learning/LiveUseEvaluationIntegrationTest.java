package ai.intentoms.simulation.learning;

import ai.intentoms.learning.learner.PromiseBufferLearner;
import ai.intentoms.learning.model.ArtifactRef;
import ai.intentoms.learning.model.FactKey;
import ai.intentoms.learning.model.LearnedFact;
import ai.intentoms.learning.model.PolicyChange;
import ai.intentoms.learning.model.PolicyChangeset;
import ai.intentoms.learning.model.Proposal;
import ai.intentoms.learning.model.ProposalStatus;
import ai.intentoms.learning.model.SubjectType;
import ai.intentoms.learning.model.TypedValue;
import ai.intentoms.learning.store.LearnedFactRepository;
import ai.intentoms.learning.store.ProposalRepository;
import ai.intentoms.platform.ids.Ids;
import ai.intentoms.testing.PostgresTestBase;
import ai.intentoms.testing.TestApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Switching on live use of a family no learner has learned anything for is weak evidence, and the evaluation says so in
 * plain words, so a person promoting it must give a reason. Once the learner has a value the reason moves on to the
 * comparison of served and static values.
 */
@SpringBootTest(classes = TestApplication.class, properties = "oms.workflow.enabled=false")
@ActiveProfiles("test")
class LiveUseEvaluationIntegrationTest extends PostgresTestBase {
    @Autowired ProposalEvaluationService evaluations;
    @Autowired ProposalRepository proposals;
    @Autowired LearnedFactRepository facts;

    private Proposal liveUse(double max) {
        String family = PromiseBufferLearner.PARAMETER;
        PolicyChangeset cs = new PolicyChangeset(new ArtifactRef("parameter", "live:" + family), 0,
            List.of(new PolicyChange.ParameterChange("live:" + family, Map.of("enabled", false), Map.of("enabled", true, "learner", PromiseBufferLearner.KEY, "min", 0.0, "max", max))),
            "test", PolicyChangeset.RiskTier.T1);
        Proposal p = new Proposal(Ids.prefixed("prop"), cs, null, PolicyChangeset.RiskTier.T1, ProposalStatus.DRAFT, null, null, "user:alice", Instant.now(), Instant.now(),
            null, null, null, null, null, null, null, null);
        proposals.insert(p);
        return p;
    }

    @Test
    void switchingOnAFamilyWithNothingLearnedSaysSo() {
        jdbc.sql("delete from learned_fact where tenant_id = :t and learner = :l").param("t", TENANT).param("l", PromiseBufferLearner.KEY).update();

        Map<String, Object> report = evaluations.evaluate(liveUse(3.0).proposalId(), "user:alice").evaluationReport();
        assertThat(report).containsEntry("method", "no_counterfactual");
        assertThat(String.valueOf(report.get("reason"))).contains("has not learned any value for " + PromiseBufferLearner.PARAMETER);

        facts.insert(new LearnedFact(Ids.prefixed("fact"), SubjectType.SEGMENT, PromiseBufferLearner.segment("web"), FactKey.PROMISE_BUFFER_DAYS,
            new TypedValue.NumberValue(1.0), 0.6, 40, PromiseBufferLearner.KEY, 1, Instant.now(), null, Instant.now()));
        Map<String, Object> after = evaluations.evaluate(liveUse(2.0).proposalId(), "user:alice").evaluationReport();
        assertThat(String.valueOf(after.get("reason"))).doesNotContain("has not learned any value");
    }
}
