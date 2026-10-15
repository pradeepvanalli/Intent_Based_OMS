package ai.intentoms.monitoring.sla;

import ai.intentoms.monitoring.MonitoringTestBase;
import ai.intentoms.monitoring.pipeline.AtRiskOrder;
import ai.intentoms.monitoring.pipeline.PipelineViewService;
import ai.intentoms.order.model.Order;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** FR-MON-02: the sweep stores a 0-4 score with its decision record and the at-risk endpoint serves it in the UI shape. */
class BreachRiskScorerIntegrationTest extends MonitoringTestBase {
    @Autowired BreachRiskScorer scorer;
    @Autowired PipelineViewService pipeline;
    @Autowired RiskLookup lookup;
    @Autowired ai.intentoms.order.service.OrderRiskProvider orderRiskProvider;

    @Test
    void scoresOpenOrdersAndServesAtRiskList() {
        Order o = newOrder("web", "released", true);
        backdateState(o.id(), Duration.ofHours(6));

        Optional<OrderRisk> risk = scorer.scoreNow(o.id());
        assertThat(risk).isPresent();
        assertThat(risk.get().score()).isBetween(0, 4);
        assertThat(risk.get().level()).isEqualTo(RiskLevel.of(risk.get().score()).key());
        assertThat(risk.get().decisionRecordId()).isNotNull();
        assertThat(risk.get().reasons()).anyMatch(r -> r.startsWith("in state released for"));
        assertThat(lookup.riskFor(o.id())).contains(risk.get());
        assertThat(orderRiskProvider).isInstanceOf(MonitoringOrderRiskProvider.class);
        ai.intentoms.order.model.OrderRisk viewRisk = orderRiskProvider.riskFor(o.id()).orElseThrow();
        assertThat(viewRisk.score()).isEqualTo((double) risk.get().score());
        assertThat(viewRisk.level()).isEqualTo(risk.get().level());
        assertThat(viewRisk.decisionRecordId()).isEqualTo(risk.get().decisionRecordId());
        assertThat(orderRiskProvider.riskFor(java.util.List.of(o.id(), "nope"))).containsOnlyKeys(o.id());
        assertThat(events("order", o.id(), "order.risk_scored")).hasSize(1);

        // re-scoring with the same level is silent
        scorer.scoreNow(o.id());
        assertThat(events("order", o.id(), "order.risk_scored")).hasSize(1);

        int scored = scorer.sweepOnce();
        assertThat(scored).isGreaterThanOrEqualTo(1);
        OrderRisk current = lookup.riskFor(o.id()).orElseThrow();   // the sweep re-scored (new decision record)
        assertThat(current.score()).isEqualTo(risk.get().score());

        if (current.score() >= 1) {
            List<AtRiskOrder> atRisk = pipeline.atRisk(100);
            AtRiskOrder row = atRisk.stream().filter(a -> a.orderId().equals(o.id())).findFirst().orElseThrow();
            assertThat(row.channelOrderId()).isEqualTo(o.channelOrderId());
            assertThat(row.status()).isEqualTo("released");
            assertThat(row.orderType()).isEqualTo("standard_ship");
            assertThat(row.channel()).isEqualTo("web");
            assertThat(row.nodeId()).isEqualTo("DC-EAST");
            assertThat(row.riskScore()).isEqualTo(current.score());
            assertThat(row.riskLevel()).isEqualTo(current.level());
            assertThat(row.reasons()).isNotEmpty();
            assertThat(row.ageMinutes()).isGreaterThanOrEqualTo(0);
            assertThat(row.promiseDate()).isNotNull();
            assertThat(row.decisionRecordId()).isEqualTo(current.decisionRecordId());
            assertThat(row.updatedAt()).isNotNull();
            assertThat(atRisk).isSortedAccordingTo((a, b) -> Integer.compare(b.riskScore(), a.riskScore()));
        }
    }

    @Test
    void closedOrdersAreNotScored() {
        Order o = newOrder("web", "delivered", false);
        assertThat(scorer.scoreNow(o.id())).isEmpty();
    }
}
