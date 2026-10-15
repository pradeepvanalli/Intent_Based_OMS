package ai.intentoms.simulation.replay;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static ai.intentoms.simulation.replay.LedgerFixtures.ORDER;
import static ai.intentoms.simulation.replay.LedgerFixtures.ledger;
import static ai.intentoms.simulation.replay.LedgerFixtures.pkg;
import static ai.intentoms.simulation.replay.LedgerFixtures.qty;
import static org.assertj.core.api.Assertions.assertThat;

/** Design principle 4 / FR-SIM-01: the ledger fold reproduces quantity buckets, header status, holds and plans. */
class LedgerFoldTest {
    static final String LINE = "order_line.standard_ship";
    static final String HEADER = "order.standard_ship";

    @Test
    void foldsQuantityBucketsPerEntityAndDerivesHeaderStatus() {
        String l1 = ORDER + ":L1", l2 = ORDER + ":L2";
        var events = ledger().captured("web", "standard_ship")
            .initialized("order", ORDER, HEADER, 1, "created", 1)
            .initialized("order_line", l1, LINE, 3, "created", 10)
            .initialized("order_line", l2, LINE, 3, "created", 1)
            .transitioned("order_line", l1, LINE, 3, "created", "awaiting_sourcing", 10, "event.order_validated", qty("created", 10), qty("created", 0, "awaiting_sourcing", 10))
            .transitioned("order_line", l1, LINE, 3, "awaiting_sourcing", "released", 6, "decision.sourcing_plan", qty("awaiting_sourcing", 10), qty("awaiting_sourcing", 4, "released", 6))
            .transitioned("order_line", l1, LINE, 3, "awaiting_sourcing", "backordered", 4, "decision.sourcing_plan", qty("awaiting_sourcing", 4, "released", 6), qty("awaiting_sourcing", 0, "released", 6, "backordered", 4))
            .transitioned("order", ORDER, HEADER, 1, "created", "sourcing", 1, "rollup", qty("created", 1), qty("created", 0, "sourcing", 1))
            .events();

        RebuiltOrder r = LedgerFold.fold(ORDER, events);

        assertThat(r.eventsFolded()).isEqualTo(8);
        assertThat(r.channel()).isEqualTo("web");
        assertThat(r.orderType()).isEqualTo("standard_ship");
        assertThat(r.headerStatus()).isEqualTo("sourcing");
        assertThat(r.entities()).containsKeys(ORDER, l1, l2);
        RebuiltOrder.EntityState line1 = r.entities().get(l1);
        assertThat(line1.states()).containsExactlyInAnyOrderEntriesOf(Map.of("released", 6, "backordered", 4));
        assertThat(line1.totalQty()).isEqualTo(10);
        assertThat(line1.initialQty()).isEqualTo(10);
        assertThat(line1.dominantState()).isEqualTo("released");
        assertThat(r.entities().get(l2).states()).containsExactlyEntriesOf(Map.of("created", 1));
        assertThat(r.lines()).hasSize(2);
        assertThat(r.header().modelId()).isEqualTo(HEADER);
        assertThat(r.transitions()).hasSize(4);
        assertThat(r.transitions().get(3).isRollup()).isTrue();
        assertThat(r.timeline()).hasSize(8).allMatch(t -> t.summary() != null);
        assertThat(r.firstEventAt()).isBefore(r.lastEventAt());
    }

    @Test
    void transitionWithoutBucketsMovesQuantityArithmetically() {
        String l1 = ORDER + ":L1";
        var events = ledger().initialized("order_line", l1, LINE, 3, "created", 5)
            .transitioned("order_line", l1, LINE, 3, "created", "awaiting_sourcing", 2, "event.order_validated", Map.of(), Map.of())
            .events();
        RebuiltOrder r = LedgerFold.fold(ORDER, events);
        assertThat(r.entities().get(l1).states()).containsExactlyInAnyOrderEntriesOf(Map.of("created", 3, "awaiting_sourcing", 2));
        assertThat(r.entities().get(l1).dominantState()).isEqualTo("created");
    }

    @Test
    void dominantStateTiesGoToTheMostRecentlyEnteredState() {
        assertThat(LedgerFold.dominant(Map.of("a", 2, "b", 2), "b")).isEqualTo("b");
        assertThat(LedgerFold.dominant(Map.of("a", 3, "b", 2), "b")).isEqualTo("a");
        assertThat(LedgerFold.dominant(Map.of(), "x")).isEqualTo("x");
    }

    @Test
    void holdsArePlacedMinusReleased() {
        var events = ledger().initialized("order", ORDER, HEADER, 1, "created", 1)
            .holdPlaced("h1", "fraud", "order", ORDER)
            .holdPlaced("h2", "compliance", "order_line", ORDER + ":L1")
            .holdReleased("h1", "fraud")
            .events();
        RebuiltOrder r = LedgerFold.fold(ORDER, events);
        assertThat(r.holdsPlaced()).isEqualTo(2);
        assertThat(r.holdsReleased()).isEqualTo(1);
        assertThat(r.holds()).singleElement().satisfies(h -> {
            assertThat(h.holdId()).isEqualTo("h2");
            assertThat(h.scopeType()).isEqualTo("order_line");
            assertThat(h.blocks()).containsExactly("release");
        });
    }

    @Test
    void sourcedPayloadsBecomePlanSummaries() {
        var events = ledger().initialized("order", ORDER, HEADER, 1, "created", 1)
            .sourced("plan_1", "ship_as_available", List.of(pkg("DC-NJ", 0.96, 8.5, "2026-10-08"), pkg("DC-TX", 0.91, 11.25, "2026-10-10")), 31.75, 0.41)
            .events();
        RebuiltOrder r = LedgerFold.fold(ORDER, events);
        RebuiltOrder.SourcedPlan p = r.latestPlan();
        assertThat(p).isNotNull();
        assertThat(p.planId()).isEqualTo("plan_1");
        assertThat(p.policy()).isEqualTo("ship_as_available");
        assertThat(p.packages()).isEqualTo(2);
        assertThat(p.nodeIds()).containsExactly("DC-NJ", "DC-TX");
        assertThat(p.totalCost()).isEqualByComparingTo("31.75");
        assertThat(p.marginRatio()).isEqualTo(0.41);
        assertThat(p.onTimeProbability()).isEqualTo(0.91);
        assertThat(p.latestEta()).isEqualTo("2026-10-10");
        PlanSummary s = PlanSummary.recorded(p);
        assertThat(s.split()).isTrue();
        assertThat(s.cost()).isEqualTo(31.75);
        assertThat(s.source()).isEqualTo(PlanSummary.RECORDED);
    }
}
