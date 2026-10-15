package ai.intentoms.monitoring.sweeper;

import ai.intentoms.monitoring.MonitoringTestBase;
import ai.intentoms.monitoring.exception.ExceptionQuery;
import ai.intentoms.monitoring.exception.ExceptionRecord;
import ai.intentoms.monitoring.exception.ExceptionService;
import ai.intentoms.monitoring.playbook.PlaybookStep;
import ai.intentoms.order.model.Order;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** FR-MON-05: an open order without a ledger event inside its expected window gets exactly one stuck exception per window. */
class StuckOrderSweeperIntegrationTest extends MonitoringTestBase {
    @Autowired StuckOrderSweeper sweeper;
    @Autowired ExceptionService exceptions;

    @Test
    void raisesStuckOnceForASilentOrderAndStartsDiagnosis() {
        Order stuck = newOrder("web", "released", true);      // released: expected next event within PT4H (sla.yaml)
        Order fresh = newOrder("web", "released", true);
        Instant later = Instant.now().plus(Duration.ofHours(5));
        time.override(Clock.fixed(later, ZoneOffset.UTC));
        // give the fresh order a recent business event in "the future" so only the first is silent
        jdbc.sql("insert into event(event_id, tenant_id, aggregate_type, aggregate_id, sequence, type, payload, actor, metadata, occurred_at) values (:id, :t, 'order', :o, 99, 'order.noted', '{}'::jsonb, 'system:test', '{}'::jsonb, :at)")
            .param("id", "evt_fresh_" + fresh.id()).param("t", TENANT).param("o", fresh.id()).param("at", java.sql.Timestamp.from(later.minusSeconds(60))).update();

        List<String> flagged = sweeper.sweepOnce();
        assertThat(flagged).contains(stuck.id()).doesNotContain(fresh.id());

        ExceptionRecord exc = exceptions.list(ExceptionQuery.forOrder(stuck.id())).items().get(0);
        assertThat(exc.type()).isEqualTo("stuck");
        assertThat(exc.source()).isEqualTo("sweeper");
        assertThat(exc.code()).isEqualTo("no_event_within_window");
        assertThat(exc.details()).containsEntry("state", "released").containsEntry("windowSeconds", 14_400);
        assertThat(exc.playbook()).isEqualTo("diagnose_stuck");
        assertThat(exc.playbookSteps()).extracting(PlaybookStep::action).containsExactly("classify", "retry_workflow", "create_task");
        assertThat(exc.playbookSteps().get(0).status()).isEqualTo(PlaybookStep.DONE);
        assertThat(exc.playbookSteps().get(1).detail()).isEqualTo("no handler");
        assertThat(exc.playbookSteps().get(2).gatedBy()).isEqualTo("onFailure");

        assertThat(sweeper.sweepOnce()).as("same window: not raised twice").doesNotContain(stuck.id());
        assertThat(exceptions.list(ExceptionQuery.forOrder(stuck.id())).total()).isEqualTo(1);
    }

    @Test
    void terminalOrdersAreIgnored() {
        Order done = newOrder("web", "delivered", false);
        time.override(Clock.fixed(Instant.now().plus(Duration.ofDays(10)), ZoneOffset.UTC));
        assertThat(sweeper.sweepOnce()).doesNotContain(done.id());
    }
}
