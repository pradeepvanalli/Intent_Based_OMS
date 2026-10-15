package ai.intentoms.orchestration.autonomy;

import ai.intentoms.orchestration.OrchestrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Guardrail evaluation (Agentic Control Tower §3/§4): cap enforcement, allow-list, kill switch, confidence override, the hard "refund/cancel never autonomous" rule. */
class GuardrailServiceTest extends OrchestrationTestBase {
    @Autowired GuardrailService guardrails;
    @Autowired AutonomousActionRepository actionRepo;

    @Test
    void blocksWhenAutonomyIsNotEnabled() {
        config.set(AutonomyConfigKeys.ENABLED, false);
        GuardrailDecision d = guardrails.evaluate(AutonomousActionType.RE_SOURCE, 0.95, BigDecimal.TEN);
        assertThat(d.allowed()).isFalse();
        assertThat(d.reason()).contains("not enabled");
    }

    @Test
    void allowsOnceEnabledWithDefaultAllowList() {
        config.set(AutonomyConfigKeys.ENABLED, true);
        GuardrailDecision d = guardrails.evaluate(AutonomousActionType.RE_SOURCE, 0.95, BigDecimal.TEN);
        assertThat(d.allowed()).isTrue();
    }

    @Test
    void killSwitchBlocksEvenWhenEnabled() {
        config.set(AutonomyConfigKeys.ENABLED, true);
        config.set(AutonomyConfigKeys.KILL_SWITCH, true);
        GuardrailDecision d = guardrails.evaluate(AutonomousActionType.RE_SOURCE, 0.95, BigDecimal.TEN);
        assertThat(d.allowed()).isFalse();
        assertThat(d.reason()).contains("kill switch");
    }

    @Test
    void allowListBlocksAnActionTypeNotOnTheList() {
        config.set(AutonomyConfigKeys.ENABLED, true);
        config.set(AutonomyConfigKeys.ALLOWED_ACTIONS, List.of("re_promise", "notify_customer"));
        assertThat(guardrails.evaluate(AutonomousActionType.RE_SOURCE, 0.95, BigDecimal.TEN).allowed()).isFalse();
        assertThat(guardrails.evaluate(AutonomousActionType.RE_PROMISE, 0.95, BigDecimal.TEN).allowed()).isTrue();
    }

    @Test
    void refundAndCancelAreNeverAutonomousEvenIfConfigured() {
        config.set(AutonomyConfigKeys.ENABLED, true);
        // A tenant that mistakenly lists refund/cancel in the allow-list still never gets autonomous execution.
        config.set(AutonomyConfigKeys.ALLOWED_ACTIONS, List.of("refund", "cancel", "re_source"));
        assertThat(guardrails.evaluate(AutonomousActionType.REFUND, 0.99, BigDecimal.ONE).allowed()).isFalse();
        assertThat(guardrails.evaluate(AutonomousActionType.CANCEL, 0.99, BigDecimal.ONE).allowed()).isFalse();
        assertThat(guardrails.load().allowedActions()).doesNotContain("refund", "cancel");
        assertThat(guardrails.evaluate(AutonomousActionType.RE_SOURCE, 0.99, BigDecimal.ONE).allowed()).isTrue();
    }

    @Test
    void minConfidenceOverrideRaisesTheBarButNeverLowersIt() {
        config.set(AutonomyConfigKeys.ENABLED, true);
        config.set(AutonomyConfigKeys.MIN_CONFIDENCE_OVERRIDE, 0.97);
        assertThat(guardrails.evaluate(AutonomousActionType.RE_SOURCE, 0.95, BigDecimal.TEN).allowed()).isFalse();
        assertThat(guardrails.evaluate(AutonomousActionType.RE_SOURCE, 0.98, BigDecimal.TEN).allowed()).isTrue();
    }

    @Test
    void dollarImpactCapBlocksAboveTheConfiguredCeiling() {
        config.set(AutonomyConfigKeys.ENABLED, true);
        config.set(AutonomyConfigKeys.MAX_USD_IMPACT_PER_ACTION, new BigDecimal("50.00"));
        assertThat(guardrails.evaluate(AutonomousActionType.RE_SOURCE, 0.95, new BigDecimal("49.99")).allowed()).isTrue();
        GuardrailDecision blocked = guardrails.evaluate(AutonomousActionType.RE_SOURCE, 0.95, new BigDecimal("50.01"));
        assertThat(blocked.allowed()).isFalse();
        assertThat(blocked.reason()).contains("exceeds the per-action cap");
    }

    @Test
    void dailyCapBlocksOnceReached() {
        config.set(AutonomyConfigKeys.ENABLED, true);
        config.set(AutonomyConfigKeys.MAX_ACTIONS_PER_DAY, 1);
        actionRepo.insert(new AutonomousAction(ai.intentoms.platform.ids.Ids.prefixed("aut"), tenant, "ord_1", "no_progress", null,
            "inventory_reallocation_needed", 0.95, "re_source", java.util.Map.of(), AutonomousAction.OUTCOME_EXECUTED, true, "done",
            java.util.Map.of(), time.now(), null, null, null));
        GuardrailDecision blocked = guardrails.evaluate(AutonomousActionType.RE_SOURCE, 0.95, BigDecimal.TEN);
        assertThat(blocked.allowed()).isFalse();
        assertThat(blocked.reason()).contains("daily autonomous action cap reached (1/1)");
    }
}
