package ai.intentoms.simulation.replay;

import ai.intentoms.decision.model.Mode;
import ai.intentoms.platform.observability.Metrics;
import ai.intentoms.platform.time.TimeSource;
import ai.intentoms.sourcing.SourcingService;
import ai.intentoms.sourcing.model.SimulationContext;
import ai.intentoms.sourcing.model.SimulationOverrides;
import ai.intentoms.sourcing.model.SourcingPlan;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Replays any order through the current or a proposed decision set and state model from the event ledger and shows the
 * diff (FR-SIM-01; ARCHITECTURE §3.11). Steps: rebuild the order from the ledger ({@link OrderRebuilder}); re-run each
 * recorded decision with its stored input in REPLAY mode against the override or current live version
 * ({@link DecisionReplayer}); fold the recorded transitions through the proposed state model ({@link StateModelReplay});
 * re-source under the what-if overrides and compare with the recorded plan.
 */
@Service
public class ReplayService {
    private static final Logger log = LoggerFactory.getLogger(ReplayService.class);
    public static final String ACTOR = "replay";

    private final OrderRebuilder rebuilder;
    private final DecisionReplayer decisions;
    private final StateModelReplay stateModels;
    private final SourcingService sourcing;
    private final TimeSource time;
    private final Metrics metrics;

    public ReplayService(OrderRebuilder rebuilder, DecisionReplayer decisions, StateModelReplay stateModels, SourcingService sourcing, TimeSource time, Metrics metrics) {
        this.rebuilder = rebuilder;
        this.decisions = decisions;
        this.stateModels = stateModels;
        this.sourcing = sourcing;
        this.time = time;
        this.metrics = metrics;
    }

    public ReplayResult replay(String orderId, ReplayOptions options) {
        ReplayOptions opts = options == null ? ReplayOptions.current() : options;
        return metrics.time("oms.simulation.replay", () -> {
            RebuiltOrder rebuilt = rebuilder.rebuild(orderId);

            List<ReplayResult.DecisionReplay> replayed = decisions.replay(orderId, opts.decisionVersionOverrides(), opts.allRecords(), Mode.REPLAY, ACTOR);

            StateModelReplayResult sm = opts.stateModelVersion() == null ? null : stateModels.replay(rebuilt, opts.stateModelId(), opts.stateModelVersion());

            ReplayResult.SourcingReplay sourcingReplay = resource(rebuilt, opts);

            int flipped = 0, bandChanged = 0, changed = 0;
            double cost = 0;
            for (ReplayResult.DecisionReplay d : replayed) {
                if (d.flipped()) flipped++;
                if (d.bandChanged()) bandChanged++;
                if (d.changed()) changed++;
                cost += d.costDeltaUsd();
            }
            int blocked = sm == null ? 0 : sm.blockedTransitions();
            metrics.count("oms.simulation.replays", "flipped", String.valueOf(flipped > 0), "blocked", String.valueOf(blocked > 0));
            String after = sm == null ? rebuilt.headerStatus() : sm.finalStateAfter();
            return new ReplayResult(orderId, time.now(), rebuilt.eventsFolded(), rebuilt.headerStatus(), after, replayed, sm, sourcingReplay,
                new ReplayResult.Summary(replayed.size(), flipped, bandChanged, changed, blocked, ReplayResult.round(cost)));
        });
    }

    /** Recorded plan vs a fresh set of options under the overrides, picking the plan with the recorded policy. */
    private ReplayResult.SourcingReplay resource(RebuiltOrder rebuilt, ReplayOptions opts) {
        PlanSummary before = PlanSummary.recorded(rebuilt.latestPlan());
        if (before == null && !opts.hasSourcingOverrides()) return null;
        SimulationOverrides overrides = opts.effectiveOverrides();
        try {
            List<SourcingPlan> options = SimulationContext.with(overrides, () -> sourcing.options(rebuilt.orderId()));
            SourcingPlan chosen = PlanSummary.pick(options, before == null ? null : before.policy());
            return ReplayResult.SourcingReplay.of(before, PlanSummary.simulated(chosen), overrides, chosen == null ? "no feasible plan under the overrides" : null);
        } catch (RuntimeException e) {
            log.warn("Re-sourcing {} for replay failed: {}", rebuilt.orderId(), e.getMessage());
            return ReplayResult.SourcingReplay.of(before, null, overrides, e.getMessage());
        }
    }
}
