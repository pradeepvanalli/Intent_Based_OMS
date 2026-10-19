package ai.intentoms.learning.api;

import ai.intentoms.learning.model.FactKey;
import ai.intentoms.learning.model.LearnedFact;
import ai.intentoms.learning.model.SubjectType;
import ai.intentoms.learning.model.TypedValue;
import ai.intentoms.learning.store.LearnedFactRepository;
import ai.intentoms.platform.error.ForbiddenException;
import ai.intentoms.platform.error.ValidationException;
import ai.intentoms.platform.ids.Ids;
import ai.intentoms.platform.security.Principal;
import ai.intentoms.platform.security.PrincipalAuthentication;
import ai.intentoms.testing.PostgresTestBase;
import ai.intentoms.testing.TestApplication;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** GV-02 budget endpoint, GV-03 global kill switch (platform administrators only) and §9.3 {@code GET /learning/facts?subject=}. */
@SpringBootTest(classes = TestApplication.class)
@ActiveProfiles("test")
class LearningGovernanceApiIntegrationTest extends PostgresTestBase {
    @Autowired LearningController learning;
    @Autowired LearnedFactRepository facts;

    private void as(String user, Set<String> permissions) {
        SecurityContextHolder.getContext().setAuthentication(new PrincipalAuthentication(new Principal(Principal.Type.USER, user, TENANT, Set.of("admin"), permissions, 3)));
    }

    private void asPlatformAdmin() { as("root", Set.of("*")); }

    private void asTenantAdmin() { as("tenant-admin", Set.of("learning.read", "learning.admin", "config.admin")); }

    @AfterEach void clear() { SecurityContextHolder.clearContext(); }

    @Test
    void onlyAPlatformAdministratorSwitchesLearningOffForEveryTenant() {
        asTenantAdmin();
        assertThat(learning.killSwitchState().canSetGlobal()).isFalse();
        assertThatThrownBy(() -> learning.killSwitch(new LearningController.KillSwitch(null, false, "global"))).isInstanceOf(ForbiddenException.class);
        assertThat(learning.killSwitchState().globalKillSwitch()).isFalse();

        asPlatformAdmin();
        try {
            var on = learning.killSwitch(new LearningController.KillSwitch(null, false, "global"));
            assertThat(on.globalKillSwitch()).isTrue();
            assertThat(on.globalSource()).isEqualTo("runtime");
            assertThat(on.globalSetBy()).isEqualTo("user:root");
            assertThat(on.canSetGlobal()).isTrue();
            asTenantAdmin();
            assertThat(learning.killSwitchState().globalKillSwitch()).isTrue(); // every tenant sees it
        } finally {
            asPlatformAdmin();
            assertThat(learning.killSwitch(new LearningController.KillSwitch(null, true, "global")).globalKillSwitch()).isFalse();
        }
    }

    @Test
    void theTenantKillSwitchIsSeparateFromTheGlobalOne() {
        asTenantAdmin();
        try {
            var s = learning.killSwitch(new LearningController.KillSwitch("*", false));
            assertThat(s.tenantPaused()).isTrue();
            assertThat(s.globalKillSwitch()).isFalse();
        } finally {
            assertThat(learning.killSwitch(new LearningController.KillSwitch("*", true)).tenantPaused()).isFalse();
        }
    }

    @Test
    void aLearnersBudgetIsSetThroughTheApiAndNeedsBothAdminPermissions() {
        asTenantAdmin();
        try {
            var v = learning.setBudget("band_threshold", new LearningController.BudgetUpdate(0.02, 3, null));
            assertThat(v.maxStep()).isEqualTo(0.02);
            assertThat(v.maxPromotionsPerWeek()).isEqualTo(3);
            // Omitted fields keep their value; clearMaxStep removes the learner's own limit.
            assertThat(learning.setBudget("band_threshold", new LearningController.BudgetUpdate(null, 2, null)).maxStep()).isEqualTo(0.02);
            var cleared = learning.setBudget("band_threshold", new LearningController.BudgetUpdate(null, null, true));
            assertThat(cleared.maxStep()).isNull();
            assertThat(cleared.maxPromotionsPerWeek()).isEqualTo(2);
            assertThatThrownBy(() -> learning.setBudget("band_threshold", new LearningController.BudgetUpdate(-0.1, null, null))).isInstanceOf(ValidationException.class);
            assertThatThrownBy(() -> learning.setBudget("band_threshold", new LearningController.BudgetUpdate(null, -1, null))).isInstanceOf(ValidationException.class);

            as("learning-only", Set.of("learning.read", "learning.admin"));
            assertThatThrownBy(() -> learning.setBudget("band_threshold", new LearningController.BudgetUpdate(0.5, 9, null))).isInstanceOf(ForbiddenException.class);
        } finally {
            asTenantAdmin();
            learning.setBudget("band_threshold", new LearningController.BudgetUpdate(null, 1, true));
        }
    }

    @Test
    void theAuthorIsRefusedFirstThenTheLifecycle() {
        var cs = new ai.intentoms.learning.model.PolicyChangeset(new ai.intentoms.learning.model.ArtifactRef("decision", "return_risk"), 1,
            java.util.List.of(new ai.intentoms.learning.model.PolicyChange.ThresholdChange("return_risk", "act", 0.9, 0.92)), "test", ai.intentoms.learning.model.PolicyChangeset.RiskTier.T1);
        var p = new ai.intentoms.learning.model.Proposal(Ids.prefixed("prop"), cs, null, ai.intentoms.learning.model.PolicyChangeset.RiskTier.T1,
            ai.intentoms.learning.model.ProposalStatus.DRAFT, null, null, "user:tenant-admin", Instant.now(), Instant.now(), null, null, null, null, null, null, null, null);
        proposals.insert(p);
        asTenantAdmin();
        assertThatThrownBy(() -> learning.approve(p.proposalId(), null)).isInstanceOf(ValidationException.class).hasMessageContaining("cannot approve it themselves");
        asPlatformAdmin();
        assertThatThrownBy(() -> learning.approve(p.proposalId(), null)).isInstanceOf(ai.intentoms.platform.error.ConflictException.class).hasMessageContaining("not been tested");
    }

    @Autowired ai.intentoms.learning.store.ProposalRepository proposals;

    @Test
    void factsCanBeReadBySubjectAsDocumented() {
        String node = "DC-E2-" + Ids.shortId();
        facts.insert(new LearnedFact(Ids.prefixed("fact"), SubjectType.NODE, node, FactKey.NODE_ON_TIME_RATE, new TypedValue.NumberValue(0.91), 0.8, 40,
            "node_reliability", 1, Instant.now(), null, Instant.now()));
        asTenantAdmin();
        assertThat(learning.facts(null, null, "node:" + node)).extracting(LearnedFact::subjectId).containsExactly(node);
        assertThat(learning.facts("NODE", node, null)).hasSize(1);
        assertThatThrownBy(() -> learning.facts(null, null, "node")).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> learning.facts(null, null, "planet:mars")).isInstanceOf(ValidationException.class).hasMessageContaining("Unknown subject type");
    }
}
