package ai.intentoms.orchestration.autonomy;

import ai.intentoms.finance.model.Money;
import ai.intentoms.order.capture.CaptureRequest;
import ai.intentoms.order.model.Order;
import ai.intentoms.orchestration.OrchestrationTestBase;
import ai.intentoms.platform.jdbc.Page;
import ai.intentoms.workflow.task.HumanTask;
import ai.intentoms.workflow.task.TaskQuery;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End to end: {@link AnomalyScanner#scanTenant()} finds a genuinely stuck order, diagnoses it through the normal
 * {@code DecisionService.decide()} and either executes the mapped action for real (guardrails allow) or raises a
 * human task with the diagnosis attached (guardrails block, or the decision itself needed a person). Both paths are
 * exercised against the same kind of order, only the guardrail configuration changes between runs.
 */
class AutonomyIntegrationTest extends OrchestrationTestBase {
    @Autowired AnomalyScanner scanner;
    @Autowired AnomalyRepository anomalies;
    @Autowired AutonomousActionRepository actionRepo;

    @BeforeEach
    void disableStuckThreshold() {
        // every candidate must look old regardless of real wall-clock time between capture and scan
        config.set(AutonomyConfigKeys.STUCK_AFTER_MINUTES, 0);
    }

    @Test
    void executesTheMappedActionEndToEndWhenGuardrailsAllow() {
        config.set(AutonomyConfigKeys.ENABLED, true);
        Order o = capture(canadaAddressOrder("WEB-AUT-1"));

        List<Anomaly> found = scanner.scanTenant();
        Anomaly anomaly = found.stream().filter(a -> a.orderId().equals(o.id())).findFirst().orElseThrow();
        assertThat(anomaly.diagnosisValue()).isEqualTo("address_confirmation_needed");
        assertThat(anomaly.outcome()).isEqualTo(Anomaly.OUTCOME_EXECUTED);
        assertThat(anomaly.actionId()).isNotNull();

        AutonomousAction action = actionRepo.get(anomaly.actionId());
        assertThat(action.outcome()).isEqualTo(AutonomousAction.OUTCOME_EXECUTED);
        assertThat(action.actionTaken()).isEqualTo("notify_customer");
        assertThat(action.undoable()).isFalse();
        assertThat(action.orderId()).isEqualTo(o.id());
        assertThat(action.guardrailSnapshot()).containsEntry("enabled", true);

        Page<HumanTask> tasksForOrder = tasks.list(new TaskQuery(null, null, null, "order", o.id(), null, 0, 50));
        assertThat(tasksForOrder.items()).as("a successfully executed autonomous action must not also open a human task").isEmpty();
    }

    @Test
    void raisesAQueuedHumanTaskWithTheDiagnosisWhenAGuardrailBlocks() {
        config.set(AutonomyConfigKeys.ENABLED, false); // guardrails block even though the decision would act
        Order o = capture(canadaAddressOrder("WEB-AUT-2"));

        List<Anomaly> found = scanner.scanTenant();
        Anomaly anomaly = found.stream().filter(a -> a.orderId().equals(o.id())).findFirst().orElseThrow();
        assertThat(anomaly.diagnosisValue()).isEqualTo("address_confirmation_needed");
        assertThat(anomaly.outcome()).isEqualTo(Anomaly.OUTCOME_ESCALATED);
        assertThat(anomaly.actionId()).isNull();
        assertThat(anomaly.escalationReason()).contains("guardrail blocked").contains("not enabled");
        assertThat(anomaly.escalationQueue()).isEqualTo("decision_escalations");
        assertThat(actionRepo.list(null, null, null, null, 0, 50).items()).as("nothing was actually executed").isEmpty();

        HumanTask task = tasks.get(anomaly.escalationTaskId());
        assertThat(task.queue()).isEqualTo("decision_escalations");
        assertThat(task.subjectId()).isEqualTo(o.id());
        assertThat(task.payload()).containsEntry("diagnosisValue", "address_confirmation_needed");
        assertThat(String.valueOf(task.payload().get("blockedReason"))).contains("not enabled");
    }

    @Test
    void aDeclinedPaymentWithRetriesIsDiagnosedByTheL0RuleAndHonestlyEscalatesWhenNothingCanBeRetried() {
        config.set(AutonomyConfigKeys.ENABLED, true);
        config.set(AutonomyConfigKeys.MAX_USD_IMPACT_PER_ACTION, new java.math.BigDecimal("500.00")); // this order's $220 merchandise must clear the cap so the guardrail allows the attempt
        Order o = capture(declinedPaymentOrder("WEB-AUT-3"));
        // the workflow's own payment_authorize step only runs once driven (the poller is off in tests); call the
        // real service directly so a FAILED payment row exists for the input builder and the L0 rule to read.
        payments.authorize(o.id(), Money.usd("242.00"), "tok_visa_decline", "card", Map.of());

        List<Anomaly> found = scanner.scanTenant();
        Anomaly anomaly = found.stream().filter(a -> a.orderId().equals(o.id())).findFirst().orElseThrow();
        assertThat(anomaly.diagnosisValue()).isEqualTo("payment_retry_needed");
        assertThat(anomaly.band()).isEqualTo("ACT");
        // L0 rule fired: guardrails allowed the attempt, but a genuinely declined (not merely expiring) payment has
        // nothing open to reauthorise, so the autonomous attempt honestly fails and escalates instead of pretending.
        assertThat(anomaly.outcome()).isEqualTo(Anomaly.OUTCOME_ESCALATED);
        AutonomousAction action = actionRepo.get(anomaly.actionId());
        assertThat(action.outcome()).isEqualTo(AutonomousAction.OUTCOME_FAILED);
        assertThat(action.actionTaken()).isEqualTo("retry_payment");
        HumanTask task = tasks.get(anomaly.escalationTaskId());
        assertThat(task.payload()).containsEntry("attemptedActionId", action.id());
    }

    @Test
    void lowConfidenceLandsInTheDecisionsOwnEscalationPathNotAGuardrailBlock() {
        config.set(AutonomyConfigKeys.ENABLED, true);
        // No L0 rule matches; the heuristic provider (no Jev key in tests) tops out below the T2 act threshold, so
        // DecisionService.decide() itself escalates through the normal EscalationHandler before autonomy ever runs.
        Order o = capture(webOrder("WEB-AUT-4", "tok_visa_ok"));

        List<Anomaly> found = scanner.scanTenant();
        Anomaly anomaly = found.stream().filter(a -> a.orderId().equals(o.id())).findFirst().orElseThrow();
        assertThat(anomaly.outcome()).isEqualTo(Anomaly.OUTCOME_ESCALATED);
        assertThat(anomaly.escalationReason()).contains("band");
        assertThat(anomaly.escalationTaskId()).isNotNull();
        HumanTask task = tasks.get(anomaly.escalationTaskId());
        assertThat(task.type()).isEqualTo("decision_escalation");
        assertThat(task.payload()).containsKey("recommendedValue");
    }

    private static CaptureRequest canadaAddressOrder(String channelOrderId) {
        return new CaptureRequest("web", channelOrderId, "standard_ship", "cust-aut-1", customer("cust-aut-1"),
            List.of(line("1", "HOOD-GRY-M", 1, "68.00")),
            List.of(new CaptureRequest.AddressInput("ship_to", "Avery Tremblay", "77 Bloor St W", null, "Toronto", "ON", "M5S 1M2", "CA", null, "residential")),
            List.of(), List.of(new CaptureRequest.TenderInput("card", "tok_visa_ok", Money.usd("74.80"), Map.of())), List.of(), List.of(), "ground", null, "USD", Map.of(), null);
    }

    private static CaptureRequest declinedPaymentOrder(String channelOrderId) {
        return new CaptureRequest("web", channelOrderId, "standard_ship", "cust-aut-2", customer("cust-aut-2"),
            List.of(line("1", "BOOT-LTH-9", 1, "220.00")),
            List.of(new CaptureRequest.AddressInput("ship_to", "Zoe King", "200 S Biscayne Blvd", null, "Miami", "FL", "33131", "US", null, "residential")),
            List.of(), List.of(new CaptureRequest.TenderInput("card", "tok_visa_decline", Money.usd("242.00"), Map.of())), List.of(), List.of(), "ground", null, "USD", Map.of(), null);
    }
}
