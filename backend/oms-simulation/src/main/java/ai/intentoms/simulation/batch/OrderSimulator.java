package ai.intentoms.simulation.batch;

import ai.intentoms.decision.model.DecisionDefinition;
import ai.intentoms.decision.model.DecisionRecord;
import ai.intentoms.decision.model.DecisionResult;
import ai.intentoms.decision.model.Mode;
import ai.intentoms.platform.observability.Metrics;
import ai.intentoms.simulation.replay.DecisionReplayer;
import ai.intentoms.simulation.replay.OrderRebuilder;
import ai.intentoms.simulation.replay.Outcome;
import ai.intentoms.simulation.replay.PlanSummary;
import ai.intentoms.simulation.replay.RebuiltOrder;
import ai.intentoms.simulation.replay.StateModelReplay;
import ai.intentoms.simulation.replay.StateModelReplayResult;
import ai.intentoms.simulation.whatif.ScenarioOverrides;
import ai.intentoms.sourcing.SourcingService;
import ai.intentoms.sourcing.model.SimulationContext;
import ai.intentoms.sourcing.model.SimulationOverrides;
import ai.intentoms.sourcing.model.SourcingPlan;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Simulates one order under a scenario for the batch report (FR-SIM-02, FR-SIM-03): the recorded decisions are re-run in
 * SIMULATION mode only where a version override applies (otherwise the recorded outcome is the simulated one too), the
 * order is re-sourced through {@link SourcingService#options} inside {@link SimulationContext} when the scenario touches
 * sourcing, and the recorded transitions are folded through a proposed state model when one is given.
 */
@Component
public class OrderSimulator {
    private static final Logger log = LoggerFactory.getLogger(OrderSimulator.class);
    public static final String ACTOR = "batch-simulation";

    private final OrderRebuilder rebuilder;
    private final DecisionReplayer decisions;
    private final StateModelReplay stateModels;
    private final SourcingService sourcing;
    private final Metrics metrics;

    public OrderSimulator(OrderRebuilder rebuilder, DecisionReplayer decisions, StateModelReplay stateModels, SourcingService sourcing, Metrics metrics) {
        this.rebuilder = rebuilder;
        this.decisions = decisions;
        this.stateModels = stateModels;
        this.sourcing = sourcing;
        this.metrics = metrics;
    }

    public OrderSimulation simulate(String orderId, ScenarioOverrides scenario) {
        return metrics.time("oms.simulation.order", () -> {
            RebuiltOrder rebuilt = rebuilder.rebuild(orderId);

            List<OrderSimulation.DecisionPair> pairs = new ArrayList<>();
            for (DecisionRecord r : decisions.recordsFor(orderId, true)) {
                Outcome before = Outcome.of(r);
                Integer override = scenario.decisionVersionOverrides().get(r.decision());
                if (override == null || override == r.version()) { pairs.add(new OrderSimulation.DecisionPair(r.decision(), r.version(), r.version(), before, before)); continue; }
                DecisionDefinition def = decisions.target(r, scenario.decisionVersionOverrides());
                DecisionResult res = decisions.rerun(r, def, Mode.SIMULATION, ACTOR);
                pairs.add(new OrderSimulation.DecisionPair(r.decision(), r.version(), def.version(), before, Outcome.of(res)));
            }

            String error = null;
            PlanSummary baseline = PlanSummary.recorded(rebuilt.latestPlan());
            PlanSummary simulated;
            try {
                if (baseline == null) {
                    SourcingPlan fresh = PlanSummary.pick(SimulationContext.with(SimulationOverrides.none(), () -> sourcing.options(orderId)), null);
                    baseline = PlanSummary.simulated(fresh);
                }
                if (scenario.affectsSourcing() && baseline != null) {
                    String policy = baseline.policy();
                    SourcingPlan plan = PlanSummary.pick(SimulationContext.with(scenario.sourcing(), () -> sourcing.options(orderId)), policy);
                    simulated = PlanSummary.simulated(plan);
                } else simulated = baseline;
            } catch (RuntimeException e) {
                log.warn("Re-sourcing {} in batch failed: {}", orderId, e.getMessage());
                simulated = null;
                error = e.getMessage();
            }

            int blocked = 0;
            if (scenario.affectsStateModel()) {
                StateModelReplayResult sm = stateModels.replay(rebuilt, scenario.stateModelId(), scenario.stateModelVersion());
                blocked = sm.blockedTransitions();
            }
            return new OrderSimulation(orderId, baseline, simulated, pairs, blocked, error);
        });
    }
}
