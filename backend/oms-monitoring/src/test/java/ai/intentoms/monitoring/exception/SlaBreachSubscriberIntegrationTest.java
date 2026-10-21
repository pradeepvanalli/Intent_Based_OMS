package ai.intentoms.monitoring.exception;

import ai.intentoms.monitoring.MonitoringTestBase;
import ai.intentoms.monitoring.playbook.PlaybookStep;
import ai.intentoms.order.model.Order;
import ai.intentoms.platform.ledger.Actor;
import ai.intentoms.platform.ledger.Event;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** FR-SM-08 / FR-MON-02: sla.breached ledger events become sla_breach exceptions whose onBreach playbook or decision runs. */
class SlaBreachSubscriberIntegrationTest extends MonitoringTestBase {
    @Autowired MonitoringEventSubscriber subscriber;
    @Autowired ExceptionService exceptions;

    private static Event breach(String orderId, String state, String onBreach, long ageSeconds) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("entityType", "order_line");
        p.put("entityId", orderId + ":L1");
        p.put("state", state);
        p.put("onBreach", onBreach);
        p.put("enteredAt", Instant.now().minusSeconds(ageSeconds).toString());
        p.put("dueAt", Instant.now().toString());
        p.put("ageSeconds", ageSeconds);
        p.put("timerId", "slt_1");
        return new Event("evt_" + orderId + "_" + state, TENANT, "order", orderId, 7, "sla.breached", p, Actor.system("sla-sweeper"), null, null,
            Map.of("entityType", "order_line", "entityId", orderId + ":L1"), Instant.now());
    }

    @Test
    void playbookOnBreachRunsStoreBacklogAndIsIdempotent() {
        Order o = newOrder("web", "released", true);
        Event e = breach(o.id(), "released", "playbook.store_backlog", 15_000);
        subscriber.on(e);
        subscriber.on(e);   // outbox re-delivery

        List<ExceptionRecord> list = exceptions.list(ExceptionQuery.forOrder(o.id())).items();
        assertThat(list).hasSize(1);
        ExceptionRecord exc = list.get(0);
        assertThat(exc.type()).isEqualTo("sla_breach");
        assertThat(exc.code()).isEqualTo("sla_breached_released");
        assertThat(exc.lineId()).isEqualTo("L1");
        assertThat(exc.nodeId()).isEqualTo("DC-EAST");
        assertThat(exc.details()).containsEntry("state", "released").containsEntry("onBreach", "playbook.store_backlog").containsEntry("ageSeconds", 15_000);
        assertThat(exc.sourceEventId()).isEqualTo(e.id());
        assertThat(exc.playbook()).isEqualTo("store_backlog");
        assertThat(exc.playbookSteps()).extracting(PlaybookStep::action).containsExactly("notify", "resource");
        assertThat(exc.playbookSteps().get(0).status()).isEqualTo(PlaybookStep.DONE);
        assertThat(exc.playbookSteps().get(1).status()).isEqualTo(PlaybookStep.SKIPPED);
        assertThat(exc.playbookSteps().get(1).detail()).isEqualTo("no handler");

        var alerts = jdbc.sql("select key, severity, count, channels_sent::text as ch from alert where tenant_id = :t and key = :k").param("t", TENANT)
            .param("k", "exception:sla_breach:" + o.id() + ":store_backlog").query().listOfRows();
        assertThat(alerts).hasSize(1);
        assertThat(alerts.get(0)).containsEntry("severity", "medium").containsEntry("count", 1);
        assertThat(events("order", o.id(), "exception.raised")).hasSize(1);
    }

    @Test
    void decisionOnBreachRunsTheDecisionAndStoresItsRecord() {
        Order o = newOrder("marketplace", "sourcing", false);
        subscriber.on(breach(o.id(), "backordered", "decision.backorder_next_action", 3 * 86_400));
        ExceptionRecord exc = exceptions.list(ExceptionQuery.forOrder(o.id())).items().get(0);
        assertThat(exc.type()).isEqualTo("sla_breach");
        assertThat(exc.decisionRecordId()).isNotNull();
        assertThat(exc.details()).containsEntry("decision", "backorder_next_action").containsKey("decisionValue").containsEntry("onBreachDecision", "backorder_next_action");
        assertThat(exc.playbook()).as("taxonomy playbook still runs after the decision").isEqualTo("store_backlog");
        assertThat(exc.playbookRunAt()).isNotNull();
        assertThat(events("order", o.id(), "exception.decision_attached")).hasSize(1);
    }

    /**
     * B4-1: a {@code store_rejected} / {@code short_pick} line transition is handled end to end by the owning
     * workflow that fires it (reservation release, re-source, node penalty, its own exception with the playbook
     * suppressed) before this generic transition event is ever delivered. The subscriber must not independently
     * reclassify it and raise a second exception, which used to run the taxonomy's default playbook against whatever
     * node the order sits on by then (the replacement, not the one that rejected).
     */
    @Test
    void storeRejectTransitionAloneRaisesNoExceptionTheOwningWorkflowAlreadyHandledIt() {
        Order o = newOrder("web", "released", true);
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("entityType", "order_line");
        p.put("entityId", o.id() + ":L1");
        p.put("from", "released");
        p.put("to", "awaiting_sourcing");
        p.put("qty", 2);
        p.put("trigger", "event.store_rejected");
        subscriber.on(new Event("evt_tr_" + o.id(), TENANT, "order", o.id(), 9, "order.transitioned", p, Actor.system("wms"), null, null, Map.of(), Instant.now()));
        assertThat(exceptions.list(ExceptionQuery.forOrder(o.id())).items()).isEmpty();
    }

    @Test
    void workflowFailureRaisesASystemException() {
        Order o = newOrder("web", "released", true);
        Map<String, Object> p = Map.of("type", "order.standard_ship", "businessKey", o.id(), "reason", "WMS unreachable", "step", "release");
        subscriber.on(new Event("evt_wf_" + o.id(), TENANT, "workflow", "wf_1", 3, "workflow.failed", p, Actor.system("workflow"), null, null, Map.of(), Instant.now()));
        ExceptionRecord exc = exceptions.list(ExceptionQuery.forOrder(o.id())).items().get(0);
        assertThat(exc.type()).isEqualTo("system");
        assertThat(exc.severity()).isEqualTo("critical");
        assertThat(exc.queue()).isEqualTo("platform_oncall");
        assertThat(exc.details()).containsEntry("workflowId", "wf_1").containsEntry("step", "release");
    }
}
