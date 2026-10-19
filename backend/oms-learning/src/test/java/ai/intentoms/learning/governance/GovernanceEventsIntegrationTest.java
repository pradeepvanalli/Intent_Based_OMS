package ai.intentoms.learning.governance;

import ai.intentoms.learning.LearningFixtures;
import ai.intentoms.learning.api.LearningController;
import ai.intentoms.learning.event.LearningEvents;
import ai.intentoms.learning.model.AutonomyLevel;
import ai.intentoms.learning.model.Direction;
import ai.intentoms.learning.model.FactKey;
import ai.intentoms.learning.model.Hypothesis;
import ai.intentoms.learning.model.LearnedFact;
import ai.intentoms.learning.model.ObjectiveProfile;
import ai.intentoms.learning.model.ObjectiveWeights;
import ai.intentoms.learning.model.Proposal;
import ai.intentoms.learning.model.SubjectType;
import ai.intentoms.learning.model.TypedValue;
import ai.intentoms.learning.service.ObjectiveService;
import ai.intentoms.learning.store.HypothesisRepository;
import ai.intentoms.learning.store.LearnedFactRepository;
import ai.intentoms.learning.store.ObjectiveProfileRepository;
import ai.intentoms.learning.store.ProposalRepository;
import ai.intentoms.platform.error.ValidationException;
import ai.intentoms.platform.ids.Ids;
import ai.intentoms.platform.json.Json;
import ai.intentoms.platform.ledger.Actor;
import ai.intentoms.testing.PostgresTestBase;
import ai.intentoms.testing.TestApplication;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** GV-04: every proposal, evaluation, approval, promotion, rollback and governance change is a digested ledger event. */
@SpringBootTest(classes = TestApplication.class)
@ActiveProfiles("test")
class GovernanceEventsIntegrationTest extends PostgresTestBase {
    @Autowired ProposalRepository proposals;
    @Autowired HypothesisRepository hypotheses;
    @Autowired LearnedFactRepository facts;
    @Autowired LearnerGovernance governance;
    @Autowired ParameterResolver parameters;
    @Autowired ObjectiveService objectives;
    @Autowired ObjectiveProfileRepository profiles;
    @Autowired LearningEvents events;
    @Autowired LearningController controller;

    @AfterEach
    void reset() {
        TenantContextAware.run(() -> {
            governance.setTenantKillSwitch(false, "user:tester");
            governance.setEnabled("node_reliability", true, "user:tester");
            governance.resume("node_reliability", "user:tester");
            governance.setAutonomy("node_reliability", AutonomyLevel.L1_RECOMMEND, "user:tester");
        });
    }

    private List<Map<String, Object>> events(String aggregateId) {
        return jdbc.sql("select type, actor, payload::text as payload from event where tenant_id = :t and aggregate_type = 'learning' and aggregate_id = :a order by sequence")
            .param("t", TENANT).param("a", aggregateId).query().listOfRows();
    }

    private List<String> types(String aggregateId) { return events(aggregateId).stream().map(e -> (String) e.get("type")).toList(); }

    @Test
    void proposalLifecycleIsInTheLedgerWithAPayloadDigest() {
        Hypothesis h = new Hypothesis(Ids.prefixed("hyp"), Map.of("decision", "return_risk"), "override_rate", Direction.DECREASE, Map.of("action", "move_threshold"),
            0.6, List.of("ep_1"), "humans keep overriding", Hypothesis.Status.OPEN, Instant.now());
        hypotheses.insert(h);
        events.hypothesisRaised(h.hypothesisId(), Actor.system("analyst"), Map.of("decision", "return_risk"));   // analyst writes it too: still once
        assertThat(types(h.hypothesisId())).containsExactly("hypothesis.raised");

        Proposal p = LearningFixtures.threshold("return_risk", 0.80, 0.82, "system:band_threshold", h.hypothesisId());
        proposals.insert(p);
        events.proposalDrafted(p.proposalId(), Actor.system("author"), Map.of("hypothesisId", h.hypothesisId()));
        // Defect 2: a draft must be tested before it can be approved.
        assertThatThrownBy(() -> controller.approve(p.proposalId(), null)).isInstanceOf(ai.intentoms.platform.error.ConflictException.class);
        proposals.markEvaluated(p.proposalId(), Map.of("liftEstimate", 0.02), Instant.now());
        controller.approve(p.proposalId(), null);
        assertThat(types(p.proposalId())).containsExactly("proposal.drafted", "proposal.approved");
        Map<String, Object> approved = events(p.proposalId()).get(1);
        assertThat(approved.get("actor")).isEqualTo("user:tester");
        Map<String, Object> payload = Json.readMap((String) approved.get("payload"));
        assertThat(payload).containsKey("payloadDigest").containsEntry("target", "decision:return_risk");

        Proposal q = LearningFixtures.threshold("return_risk", 0.80, 0.70, "system:band_threshold", null);
        proposals.insert(q);
        controller.reject(q.proposalId(), Map.of("reason", "too aggressive"));
        assertThat(types(q.proposalId())).containsExactly("proposal.drafted", "proposal.rejected");
    }

    @Test
    void learnedFactsAndGovernanceChangesAreEvents() {
        LearnedFact f = new LearnedFact(Ids.prefixed("fact"), SubjectType.NODE, "DC-NJ", FactKey.NODE_ON_TIME_RATE, new TypedValue.NumberValue(0.93), 0.8, 40,
            "node_reliability", 1, Instant.now(), null, Instant.now());
        facts.insert(f);
        assertThat(types(f.factId())).containsExactly("fact.learned");

        governance.setAutonomy("node_reliability", AutonomyLevel.L2_ACT_WITH_APPROVAL, "user:tester");
        governance.setEnabled("node_reliability", false, "user:tester");
        governance.setEnabled("node_reliability", true, "user:tester");
        governance.pause("node_reliability", "outcome coverage for sourcing_node_score is 40%", "system:coverage-monitor");
        governance.resume("node_reliability", "user:tester");
        assertThat(types("learner:node_reliability")).containsSubsequence("learner.autonomy_changed", "learner.switched", "learner.switched", "learner.paused", "learner.resumed");
        assertThat(events("learner:node_reliability")).filteredOn(e -> "learner.autonomy_changed".equals(e.get("type")))
            .map(e -> Json.readMap((String) e.get("payload")))
            .anySatisfy(p -> assertThat(p).containsEntry("from", "L1_RECOMMEND").containsEntry("to", "L2_ACT_WITH_APPROVAL"));

        governance.setTenantKillSwitch(true, "user:tester");
        assertThat(types("tenant-kill-switch")).contains("learning.kill_switch_changed");

        String family = "test.family_" + Ids.shortId();
        parameters.promote(family, "node_reliability", 0.5, 0.99, 0.2, null, "user:tester");
        parameters.revoke(family, "user:tester");
        assertThat(types("parameter:" + family)).containsExactly("parameter.live_changed", "parameter.live_changed");
        assertThat(events("parameter:" + family)).map(e -> Json.readMap((String) e.get("payload")).get("status")).containsExactly("LIVE", "REVOKED");
    }

    @Test
    void objectiveIsDraftedByOnePersonAndApprovedByAnother() {
        ObjectiveProfile draft = objectives.draft(new ObjectiveWeights(1, 2, 1, 1, 1, 1, 1), Map.of(), "user:tester", null, "manual", Map.of());
        assertThat(draft.status()).isEqualTo(ObjectiveProfile.Status.DRAFT);
        assertThat(objectives.drafts()).extracting(ObjectiveProfile::version).contains(draft.version());
        assertThatThrownBy(() -> objectives.approve(draft.version(), "user:tester")).isInstanceOf(ValidationException.class).hasMessageContaining("cannot approve");

        ObjectiveProfile live = objectives.approve(draft.version(), "user:approver");
        assertThat(live.status()).isEqualTo(ObjectiveProfile.Status.APPROVED);
        assertThat(live.approvedBy()).isEqualTo("user:approver");
        assertThat(profiles.live()).map(ObjectiveProfile::version).contains(draft.version());
        assertThat(types("objective:" + draft.version())).containsExactly("objective.drafted", "objective.approved");

        assertThatThrownBy(() -> objectives.draft(new ObjectiveWeights(0, 0, 0, 0, 0, 0, 0), Map.of(), "user:tester", null, "manual", Map.of()))
            .isInstanceOf(ValidationException.class);
        // The REST path drafts, it never self-approves.
        ObjectiveProfile viaApi = controller.setObjective(new LearningController.ObjectiveUpdate(ObjectiveWeights.preset("speed_first"), Map.of()));
        assertThat(viaApi.status()).isEqualTo(ObjectiveProfile.Status.DRAFT);
        assertThat(profiles.live()).map(ObjectiveProfile::version).contains(draft.version());
        actAs("someone-else", Set.of("*"));
        assertThat(controller.approveObjective(viaApi.version()).status()).isEqualTo(ObjectiveProfile.Status.APPROVED);
    }

    /** Runs cleanup with the test tenant bound even after a test switched principals. */
    static final class TenantContextAware {
        static void run(Runnable r) {
            ai.intentoms.platform.tenant.TenantContext.set(TENANT);
            r.run();
        }
    }
}
