package ai.intentoms.learning.api;

import ai.intentoms.learning.governance.LearnerGovernance;
import ai.intentoms.platform.error.ValidationException;
import ai.intentoms.platform.json.Json;
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

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * E4-N1 (audit 4, API robustness / GV-03 / §9.3 R11): {@code POST /api/v1/learning/kill-switch}'s body used a
 * primitive {@code boolean enabled}, so a body that omitted it — or misspelled it, e.g. {@code "on": true} — silently
 * deserialised as {@code enabled: false} and switched a learner, or the whole tenant, off with no error. Fixed:
 * {@code enabled} is boxed and required (400/{@link ValidationException} when absent), for every scope.
 */
@SpringBootTest(classes = TestApplication.class)
@ActiveProfiles("test")
class KillSwitchBodyValidationIntegrationTest extends PostgresTestBase {
    @Autowired LearningController learning;
    @Autowired LearnerGovernance governance;

    private void asTenantAdmin() {
        SecurityContextHolder.getContext().setAuthentication(new PrincipalAuthentication(new Principal(Principal.Type.USER, "tester", TENANT, Set.of("admin"), Set.of("*"), 3)));
    }

    @AfterEach void clear() { SecurityContextHolder.clearContext(); }

    @Test
    void aBodyMissingEnabledIsRejectedAndChangesNothingForTheLearnerScope() {
        asTenantAdmin();
        boolean before = governance.states().stream().filter(s -> "rule_utilization".equals(s.learnerKey())).findFirst().map(s -> s.enabled()).orElse(true);

        // The audit's exact repro: a body shaped with "on" instead of "enabled" must not silently switch anything off.
        LearningController.KillSwitch misspelled = Json.read("{\"learnerKey\":\"rule_utilization\",\"on\":true}", LearningController.KillSwitch.class);
        assertThat(misspelled.enabled()).as("a missing field deserialises to null, not false, now that enabled is boxed").isNull();
        assertThatThrownBy(() -> learning.killSwitch(misspelled)).isInstanceOf(ValidationException.class).hasMessageContaining("enabled");

        boolean after = governance.states().stream().filter(s -> "rule_utilization".equals(s.learnerKey())).findFirst().map(s -> s.enabled()).orElse(true);
        assertThat(after).as("the rejected body changed nothing").isEqualTo(before);
    }

    @Test
    void aBodyMissingEnabledIsRejectedForTheTenantScopeToo() {
        asTenantAdmin();
        boolean pausedBefore = learning.killSwitchState().tenantPaused();

        LearningController.KillSwitch noEnabled = Json.read("{\"learnerKey\":\"*\"}", LearningController.KillSwitch.class);
        assertThat(noEnabled.enabled()).isNull();
        assertThatThrownBy(() -> learning.killSwitch(noEnabled)).isInstanceOf(ValidationException.class).hasMessageContaining("enabled");

        assertThat(learning.killSwitchState().tenantPaused()).as("the rejected body changed nothing").isEqualTo(pausedBefore);
    }

    @Test
    void aWellFormedBodyStillWorks() {
        asTenantAdmin();
        try {
            var off = learning.killSwitch(new LearningController.KillSwitch("rule_utilization", false));
            assertThat(off.pausedLearners()).contains("rule_utilization");
        } finally {
            learning.killSwitch(new LearningController.KillSwitch("rule_utilization", true));
        }
    }
}
