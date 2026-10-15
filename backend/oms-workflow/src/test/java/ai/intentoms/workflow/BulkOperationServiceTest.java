package ai.intentoms.workflow;

import ai.intentoms.platform.error.ConflictException;
import ai.intentoms.platform.error.ValidationException;
import ai.intentoms.platform.ledger.Event;
import ai.intentoms.platform.ledger.EventStore;
import ai.intentoms.workflow.bulk.BulkOperation;
import ai.intentoms.workflow.bulk.BulkStatus;
import ai.intentoms.workflow.bulk.DefaultBulkOperationService;
import ai.intentoms.workflow.testflows.TestBulkHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BulkOperationServiceTest extends WorkflowTestBase {
    @Autowired DefaultBulkOperationService bulk;
    @Autowired EventStore events;

    @BeforeEach
    void clear() { TestBulkHandler.APPLIED.clear(); }

    @Test
    void dryRunRecordsResultsWithoutSideEffects() {
        BulkOperation submitted = bulk.submit(TestBulkHandler.ACTION, List.of("o1", "o2", "bad-1"), Map.of("tag", "x"), true, 0);
        assertThat(submitted.status()).isEqualTo(BulkStatus.QUEUED);
        BulkOperation b = bulk.await(submitted.id(), Duration.ofSeconds(10));
        assertThat(b.status()).isEqualTo(BulkStatus.COMPLETED);
        assertThat(b.dryRun()).isTrue();
        assertThat(b.total()).isEqualTo(3);
        assertThat(b.done()).isEqualTo(3);
        assertThat(b.failed()).isEqualTo(1);
        assertThat(b.results()).extracting(r -> r.get("status")).containsExactly("ok", "ok", "error");
        assertThat(b.results().get(0).get("applied")).isEqualTo(Map.of("subjectId", "o1", "tag", "x", "dryRun", true));
        assertThat(TestBulkHandler.APPLIED).isEmpty();
        assertThatThrownBy(() -> bulk.rollback(b.id())).isInstanceOf(ConflictException.class);
        assertThat(events.load("workflow", b.id()).stream().map(Event::type)).containsExactly("bulk.submitted", "bulk.completed");
    }

    @Test
    void applyThenRollbackInReverse() {
        BulkOperation b = bulk.await(bulk.submit(TestBulkHandler.ACTION, List.of("a", "bad-2", "c"), Map.of("tag", "hold"), false, 0).id(), Duration.ofSeconds(10));
        assertThat(b.status()).isEqualTo(BulkStatus.COMPLETED);
        assertThat(b.done()).isEqualTo(3);
        assertThat(b.failed()).isEqualTo(1);
        assertThat(TestBulkHandler.APPLIED.keySet()).containsExactlyInAnyOrder("a", "c");
        assertThat(b.startedAt()).isNotNull();
        assertThat(b.finishedAt()).isNotNull();

        BulkOperation rolled = bulk.rollback(b.id());
        assertThat(rolled.status()).isEqualTo(BulkStatus.ROLLED_BACK);
        assertThat(rolled.rolledBackAt()).isNotNull();
        assertThat(TestBulkHandler.APPLIED).isEmpty();
        assertThat(rolled.results().get(0).get("rollback")).asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP).containsEntry("status", "ok");
        assertThat(rolled.results().get(1)).doesNotContainKey("rollback");
        assertThat(events.load("workflow", b.id()).stream().map(Event::type)).containsExactly("bulk.submitted", "bulk.completed", "bulk.rolled_back");
        assertThat(bulk.list(0, 10).items()).extracting(BulkOperation::id).contains(b.id());
        assertThatThrownBy(() -> bulk.rollback(b.id())).isInstanceOf(ConflictException.class);
    }

    @Test
    void throttleLimitsRate() {
        long t0 = System.nanoTime();
        BulkOperation b = bulk.await(bulk.submit(TestBulkHandler.ACTION, List.of("r1", "r2", "r3", "r4"), Map.of(), true, 2).id(), Duration.ofSeconds(10));
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000;
        assertThat(b.status()).isEqualTo(BulkStatus.COMPLETED);
        assertThat(elapsedMs).as("4 subjects at 2/s need about one second after the initial burst").isGreaterThanOrEqualTo(900);
    }

    @Test
    void unknownActionIsRejected() {
        assertThatThrownBy(() -> bulk.submit("nope", List.of("x"), Map.of(), false, 0)).isInstanceOf(ValidationException.class);
        assertThat(bulk.actions()).contains(TestBulkHandler.ACTION);
    }
}
