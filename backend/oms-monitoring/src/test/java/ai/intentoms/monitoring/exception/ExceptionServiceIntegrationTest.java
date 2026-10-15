package ai.intentoms.monitoring.exception;

import ai.intentoms.monitoring.MonitoringTestBase;
import ai.intentoms.monitoring.playbook.PlaybookStep;
import ai.intentoms.order.model.Order;
import ai.intentoms.statemachine.HoldService;
import ai.intentoms.workflow.task.HumanTask;
import ai.intentoms.workflow.task.HumanTaskService;
import ai.intentoms.workflow.task.TaskStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** FR-MON-03/04 end to end with the heuristic provider: classify, route, remediate, de-duplicate, resolve. */
class ExceptionServiceIntegrationTest extends MonitoringTestBase {
    @Autowired ExceptionService exceptions;
    @Autowired HumanTaskService tasks;
    @Autowired HoldService holds;

    @Test
    void raiseClassifiesRoutesAndRunsThePlaybook() {
        Order o = newOrder("web", "released", true);
        ExceptionRecord e = exceptions.raise(new ExceptionRequest("carrier", "address_invalid", "Address undeliverable: invalid zip code, unverifiable street", o.id(), "L1", null, Map.of("zip", "00000")));

        assertThat(e.id()).startsWith("exc_");
        assertThat(e.type()).isEqualTo("address");
        assertThat(e.severity()).isEqualTo("medium");
        assertThat(e.queue()).isEqualTo("cs_exceptions");
        assertThat(e.playbook()).isEqualTo("address_fix");
        assertThat(e.status()).isEqualTo(ExceptionStatus.OPEN);
        assertThat(e.classificationRecordId()).isNotNull();
        assertThat(e.classificationConfidence()).isBetween(0.5, 1.0);
        assertThat(e.slaDueAt()).isNotNull();
        assertThat(e.playbookRunAt()).isNotNull();

        // address_fix: hold (built-in, done) -> contact_customer (orchestration, skipped: no handler) -> create_task (done)
        assertThat(e.playbookSteps()).extracting(PlaybookStep::action).containsExactly("hold", "contact_customer", "create_task");
        assertThat(e.playbookSteps().get(0).status()).isEqualTo(PlaybookStep.DONE);
        assertThat(e.playbookSteps().get(1).status()).isEqualTo(PlaybookStep.SKIPPED);
        assertThat(e.playbookSteps().get(1).detail()).isEqualTo("no handler");
        assertThat(e.playbookSteps().get(2).status()).isEqualTo(PlaybookStep.DONE);
        assertThat(holds.active("order", o.id())).extracting(h -> h.type()).contains("address");

        assertThat(e.taskId()).isNotNull();
        HumanTask task = tasks.get(e.taskId());
        assertThat(task.queue()).isEqualTo("cs_exceptions");
        assertThat(task.type()).isEqualTo("exception");
        assertThat(task.subjectType()).isEqualTo("order");
        assertThat(task.subjectId()).isEqualTo(o.id());
        assertThat(task.payload()).containsEntry("exceptionId", e.id());

        assertThat(events("order", o.id(), "exception.raised")).hasSize(1);
        assertThat(events("order", o.id(), "exception.playbook_step")).hasSize(3);
    }

    @Test
    void storeRejectClassifiesToStoreRejectAndResourceIsLeftToOrchestration() {
        Order o = newOrder("web", "released", true);
        ExceptionRecord e = exceptions.raise(new ExceptionRequest("wms", "store_rejected", "Store rejected the release: node could not fulfil", o.id(), "L1", "STORE-12", Map.of()));
        assertThat(e.type()).isEqualTo("store_reject");
        assertThat(e.queue()).isEqualTo("ops_exceptions");
        assertThat(e.playbook()).isEqualTo("resource");
        assertThat(e.playbookSteps()).extracting(PlaybookStep::action, PlaybookStep::status).containsExactly(
            org.assertj.core.groups.Tuple.tuple("resource", PlaybookStep.SKIPPED), org.assertj.core.groups.Tuple.tuple("notify", PlaybookStep.DONE));
        assertThat(e.taskId()).as("resource playbook has no create_task and classification acted").isNull();
    }

    @Test
    void duplicatesInsideTheWindowAreReused() {
        Order o = newOrder("web", "released", true);
        ExceptionRequest req = new ExceptionRequest("wms", "store_rejected", "Store rejected the release: node could not fulfil", o.id(), "L1", null, Map.of());
        ExceptionRecord first = exceptions.raise(req);
        ExceptionRecord second = exceptions.raise(req);
        assertThat(second.id()).isEqualTo(first.id());
        assertThat(second.occurrences()).isEqualTo(2);
        assertThat(exceptions.list(ExceptionQuery.forOrder(o.id())).total()).isEqualTo(1);
        assertThat(events("order", o.id(), "exception.recurred")).hasSize(1);
        assertThat(events("order", o.id(), "exception.raised")).hasSize(1);
    }

    @Test
    void typedRaiseSkipsClassificationAndUnknownTypeFallsBackToOther() {
        Order o = newOrder("web", "released", true);
        ExceptionRecord sys = exceptions.raise(new ExceptionRequest("workflow", "workflow_failed", "Workflow failed at step source", o.id(), null, null, Map.of()), "system");
        assertThat(sys.type()).isEqualTo("system");
        assertThat(sys.classificationRecordId()).isNull();
        assertThat(sys.queue()).isEqualTo("platform_oncall");
        assertThat(sys.playbookSteps()).extracting(PlaybookStep::action).containsExactly("notify", "create_task");
        assertThat(sys.taskId()).isNotNull();

        ExceptionRecord other = exceptions.raise(new ExceptionRequest("api", "manual", "Something odd", o.id(), null, null, Map.of()), "no_such_type");
        assertThat(other.type()).isEqualTo("other");
        assertThat(other.playbook()).isEqualTo("manual_review");
        assertThat(other.taskId()).isNotNull();
    }

    @Test
    void assignAndResolveCompleteTheLinkedTask() {
        Order o = newOrder("web", "released", true);
        ExceptionRecord e = exceptions.raise(new ExceptionRequest("carrier", "address_invalid", "Address undeliverable: invalid zip code", o.id(), "L1", null, Map.of()));
        ExceptionRecord assigned = exceptions.assign(e.id(), "alice");
        assertThat(assigned.status()).isEqualTo(ExceptionStatus.IN_PROGRESS);
        assertThat(assigned.assignee()).isEqualTo("alice");
        assertThat(tasks.get(e.taskId()).assignee()).isEqualTo("alice");

        ExceptionRecord resolved = exceptions.resolve(e.id(), "address_corrected", "Customer confirmed new zip");
        assertThat(resolved.status()).isEqualTo(ExceptionStatus.RESOLVED);
        assertThat(resolved.resolution()).isEqualTo("address_corrected");
        assertThat(resolved.resolvedAt()).isNotNull();
        HumanTask task = tasks.get(e.taskId());
        assertThat(task.status()).isEqualTo(TaskStatus.COMPLETED);
        assertThat(task.outcome()).isEqualTo("resolved");
        assertThat(events("order", o.id(), "exception.resolved")).hasSize(1);
        assertThat(exceptions.list(new ExceptionQuery("RESOLVED", "address", null, null, o.id(), null, 0, 10)).items()).extracting(ExceptionRecord::id).containsExactly(e.id());
        List<ExceptionRecord> open = exceptions.list(new ExceptionQuery("active", null, null, null, o.id(), null, 0, 10)).items();
        assertThat(open).isEmpty();
    }
}
