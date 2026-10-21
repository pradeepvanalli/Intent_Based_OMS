package ai.intentoms.orchestration.workflow;

import ai.intentoms.inventory.model.ReservationLine;
import ai.intentoms.inventory.service.InventoryService;
import ai.intentoms.learning.model.Episode;
import ai.intentoms.learning.model.OutcomeData;
import ai.intentoms.learning.service.RewardMaturityService;
import ai.intentoms.learning.store.EpisodeRepository;
import ai.intentoms.learning.store.OutcomeRepository;
import ai.intentoms.orchestration.OrchestrationTestBase;
import ai.intentoms.order.model.FulfilmentGroup;
import ai.intentoms.order.model.Order;
import ai.intentoms.platform.json.Json;
import ai.intentoms.workflow.engine.StepRunner;
import ai.intentoms.workflow.model.WorkflowStatus;
import ai.intentoms.workflow.model.WorkflowView;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Audit A1: two orders planned against the same last units both reach the reserve step; the one that reserves second
 * finds the stock gone. It must re-source (or backorder), never fail the step and compensate the order.
 */
class ReservationRaceIntegrationTest extends OrchestrationTestBase {
    private static final String TYPE = StandardShipOrderWorkflow.TYPE;
    private static final String HOODIE = "HOOD-GRY-M";
    private static final String SOURCING_DECISION = "sourcing_node_score";
    @Autowired StepRunner runner;
    @Autowired InventoryService inventory;
    @Autowired EpisodeRepository episodes;
    @Autowired OutcomeRepository outcomeRepo;
    @Autowired RewardMaturityService rewards;

    private List<Map<String, Object>> events(String orderId, String type) {
        return jdbc.sql("select payload::text as p from event where tenant_id = :t and aggregate_id = :o and type = :ty order by position").param("t", tenant).param("o", orderId).param("ty", type)
            .query((rs, i) -> Json.readMap(rs.getString("p"))).list();
    }

    /** Takes the workflow out of the due queue so the runner advances only the other one. */
    private void park(Order o) {
        jdbc.sql("update workflow_instance set next_wake_at = null where tenant_id = :t and type = :ty and business_key = :k").param("t", tenant).param("ty", TYPE).param("k", o.id()).update();
    }

    private void unpark(Order o) {
        jdbc.sql("update workflow_instance set next_wake_at = :now where tenant_id = :t and type = :ty and business_key = :k").param("now", Timestamp.from(time.now())).param("t", tenant).param("ty", TYPE).param("k", o.id()).update();
    }

    /** One step at a time, so the workflow can be stopped exactly before a step runs. */
    private WorkflowView stepUntil(Order o, Predicate<WorkflowView> done) {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(30));
        WorkflowView w = null;
        while (Instant.now().isBefore(deadline)) {
            w = engine.find(TYPE, o.id()).orElse(null);
            if (w != null && done.test(w)) return w;
            boolean ran = runner.runNext();
            drainOutbox();
            if (!ran) sleep(20);
        }
        throw new AssertionError("Workflow " + o.id() + " did not reach the expected state; last = " + (w == null ? "absent" : w.status() + "@" + w.currentStep() + " err=" + w.lastError()));
    }

    private static boolean atReserve(WorkflowView w) { return WorkflowStatus.RUNNING.equals(w.status()) && "reserve".equals(w.currentStep()); }

    private String hoodieNode(Order o) {
        return orders.get(o.id()).fulfilmentGroups().stream().filter(g -> !"rejected".equalsIgnoreCase(g.status()) && !"cancelled".equalsIgnoreCase(g.status()))
            .filter(g -> g.allocations().stream().anyMatch(a -> HOODIE.equals(a.sku()))).map(FulfilmentGroup::nodeId).findFirst().orElseThrow();
    }

    /** Two orders race for the last two hoodies at one node; returns the node, the winner and the loser. */
    private record Race(String node, Order a, Order b, WorkflowView wb) {}

    @Test
    void theOrderThatLosesTheLastUnitsReSourcesInsteadOfBeingCompensated() {
        Race race = race();
        Order a = race.a(), b = race.b();
        String node = race.node();
        WorkflowView wb = race.wb();
        assertThat(wb.status()).isNotIn(WorkflowStatus.COMPENSATED, WorkflowStatus.FAILED, WorkflowStatus.COMPENSATING, WorkflowStatus.CANCELLED);
        assertThat(wb.lastError()).isNull();
        assertThat(wb.currentStep()).isIn("await_fulfilment", "backorder");
        assertThat(events(b.id(), "order.resourced")).singleElement().satisfies(e -> {
            assertThat(e).containsEntry("reason", "insufficient_atp");
            assertThat(String.valueOf(e.get("rejectedNodes"))).isEqualTo("[" + node + "]");
        });
        if ("await_fulfilment".equals(wb.currentStep())) assertThat(hoodieNode(b)).isNotEqualTo(node);
        assertThat(lineStates(orders.get(b.id())).values()).doesNotContain("cancelled");
        assertThat(lineStates(orders.get(a.id())).values()).doesNotContain("cancelled", "backordered");
    }

    /**
     * Audit B3-1 (FS-02, FS-06): the node never refused anything; the OMS's own reservation lost a race for stock another
     * order had just taken. The re-sourcing must neither penalise the node nor teach the node reliability learner a reject.
     */
    @Test
    void aReservationRaceIsNotLearnedAsAStoreRejectAndCarriesNoPenalty() {
        Race race = race();
        String node = race.node();
        drainOutbox();
        drainOutbox();
        assertThat(events(race.b().id(), "order.resourced")).singleElement().satisfies(e -> {
            assertThat(e).containsEntry("reason", "insufficient_atp").containsEntry("nodeRejected", false);
            assertThat(((Number) e.get("penaltyDelta")).doubleValue()).isZero();
            assertThat((Map<?, ?>) e.get("penaltyByNode")).isEmpty();
        });
        assertThat(jdbc.sql("select count(*) from event where tenant_id = :t and type = 'node.penalised' and aggregate_id = :n").param("t", tenant).param("n", node)
            .query(Long.class).single()).as("node.penalised for a node that never refused").isZero();
        double rejects = jdbc.sql("select coalesce(sum(reject_alpha - 1), 0) from node_reliability_stat where tenant_id = :t and node_id = :n").param("t", tenant).param("n", node)
            .query(Double.class).single();
        assertThat(rejects).as("rejects learned for " + node).isZero();
        // Nothing has shipped yet, so the only way a reject rate could have been published for the node is the race.
        assertThat(jdbc.sql("select count(*) from learned_fact where tenant_id = :t and subject_id like :n and fact_key = 'NODE_REJECT_RATE'")
            .param("t", tenant).param("n", node + "%").query(Long.class).single()).as("NODE_REJECT_RATE facts for " + node).isZero();
    }

    /**
     * Audit A4-1 (OC-05): the losing group is marked {@code rejected} for a shortage, not a refusal
     * ({@code nodeRejected: false}). That status alone must not make {@code order.shipped.storeRejected} true once the
     * replacement ships and delivers cleanly — nothing went wrong, so no episode of the order should blame the node.
     */
    @Test
    void aReservationRaceCarriesNoNodeBlameInAttributionOnceTheReplacementDeliversCleanly() {
        Race race = race();
        Order b = race.b();
        assertThat(race.wb().currentStep()).as("re-sourced to a replacement node, not backordered").isEqualTo("await_fulfilment");
        String replacementNode = nodeOf(b);
        assertThat(replacementNode).isNotEqualTo(race.node());

        inbound.wms(replacementNode, wmsEvent("ship-" + b.id(), "shipped", b.id(), Map.of("carrier", "UPS", "service", "ground", "tracking", "T-" + b.id())));
        driveToStep(TYPE, b.id(), WorkflowStatus.WAITING_SIGNAL, "await_delivery");
        inbound.carrier(Map.of("eventId", "del-" + b.id(), "type", "delivered", "orderId", b.id(), "tracking", "T-" + b.id()));
        driveToTerminal(TYPE, b.id());
        drainOutbox();
        rewards.sweepOnce();

        List<Episode> forB = episodes.forOrder(b.id());
        assertThat(forB).isNotEmpty();
        for (Episode ep : forB) {
            Episode scored = episodes.find(ep.episodeId()).orElseThrow();
            assertThat(scored.rewardComponents().keySet())
                .as(ep.decisionName() + " episode " + ep.episodeId() + " carries no attribution — the replacement shipped and delivered cleanly")
                .noneMatch(k -> k.startsWith("attr."));
        }
    }

    /**
     * Audit A4-2 (OC-02 / OC-07): after a shortage re-source the original choice's episode must not be left open
     * forever with no outcome at all (it would mature with a null reward and count as uncovered). It gets a definite,
     * non-blaming end of its own; the replacement's outcomes score the re-sourcing decision as before (A-N1).
     */
    @Test
    void theOriginalChoicesEpisodeGetsADefiniteNonBlamingEndAfterAShortageReSource() {
        Race race = race();
        Order b = race.b();
        assertThat(race.wb().currentStep()).as("re-sourced to a replacement node, not backordered").isEqualTo("await_fulfilment");
        Episode original = episodes.forOrder(b.id()).stream()
            .filter(e -> SOURCING_DECISION.equals(e.decisionName()) && e.fulfillmentGroupId() == null).findFirst().orElseThrow();

        List<ai.intentoms.learning.model.OutcomeRecord> outs = outcomeRepo.forEpisode(original.episodeId());
        assertThat(outs).as("the abandoned choice gets a definite outcome instead of staying open with none at all").isNotEmpty();
        assertThat(outs).allSatisfy(r -> assertThat(r.payload()).isInstanceOfSatisfying(OutcomeData.FulfillmentOutcome.class,
            f -> assertThat(f.storeRejected()).as("a shortage is nobody's refusal: no node blame").isFalse()));

        drainOutbox();
        rewards.sweepOnce();
        Episode scored = episodes.find(original.episodeId()).orElseThrow();
        assertThat(scored.status()).as("not left OPEN forever").isNotEqualTo(ai.intentoms.learning.model.EpisodeStatus.OPEN);
        assertThat(scored.rewardComponents().keySet()).noneMatch(k -> k.startsWith("attr."));
    }

    private Race race() {
        Order a = capture(webOrder("RACE-A", "tok_visa_ok"));
        Order b = capture(webOrder("RACE-B", "tok_visa_ok"));
        drainOutbox();

        // Both orders are planned against the same stock before either reserves.
        park(b);
        stepUntil(a, ReservationRaceIntegrationTest::atReserve);
        park(a);
        unpark(b);
        stepUntil(b, ReservationRaceIntegrationTest::atReserve);
        String node = hoodieNode(a);
        assertThat(hoodieNode(b)).isEqualTo(node);

        // Only two hoodies are left there: the last units, enough for exactly one of the two orders.
        int available = inventory.atp(HOODIE, node).available();
        assertThat(available).isGreaterThanOrEqualTo(2);
        if (available > 2) reservations.reserveAll(List.of(new ReservationLine(HOODIE, node, available - 2)), "cart", "other-carts-" + tenant, Duration.ofHours(1));

        // A wins the race.
        park(b);
        unpark(a);
        WorkflowView wa = stepUntil(a, w -> !atReserve(w) && !"release".equals(w.currentStep()) && !WorkflowStatus.RUNNING.equals(w.status()));
        assertThat(wa.status()).isEqualTo(WorkflowStatus.WAITING_SIGNAL);
        assertThat(wa.currentStep()).isEqualTo("await_fulfilment");
        assertThat(inventory.atp(HOODIE, node).available()).isZero();

        // B loses: the shortage is a result of the reserve step, so B re-sources away from that node (or backorders).
        unpark(b);
        WorkflowView wb = stepUntil(b, w -> WorkflowStatus.isTerminal(w.status()) || (!WorkflowStatus.RUNNING.equals(w.status()) && !WorkflowStatus.COMPENSATING.equals(w.status())));
        return new Race(node, a, b, wb);
    }
}
