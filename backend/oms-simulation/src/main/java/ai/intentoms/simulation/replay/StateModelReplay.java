package ai.intentoms.simulation.replay;

import ai.intentoms.platform.error.ValidationException;
import ai.intentoms.statemachine.StateModelRegistry;
import ai.intentoms.statemachine.model.StateDef;
import ai.intentoms.statemachine.model.StateModel;
import ai.intentoms.statemachine.model.TransitionDef;
import ai.intentoms.statemachine.service.RollupEvaluator;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Replays an order's recorded transitions through a proposed state-model version (FR-SIM-01 "replay any order through
 * the current or a proposed state model"). The pure {@link #fold} starts every entity pinned to the model in the proposed
 * model's Created state with its recorded initial quantity and applies each recorded trigger in ledger order:
 * <ul>
 *   <li>a trigger with a transition from the simulated source state is applied (the candidate whose target matches the
 *       recorded target wins, else the first declared); guards are not evaluated because the guard variables of the
 *       moment ({@code plan}, {@code approval}, ...) are not part of the ledger payload</li>
 *   <li>a recorded source state the proposed model does not declare is reported as {@code unmapped}; the fold then looks
 *       for the trigger from any state currently holding quantity</li>
 *   <li>a trigger with no transition from the simulated state is {@code blocked} — the proposed model would have stopped
 *       this order here</li>
 *   <li>header roll-ups ({@code rollup} trigger) are derived, not replayed; the header is recomputed from the simulated
 *       line buckets with {@link RollupEvaluator}</li>
 * </ul>
 */
@Component
public class StateModelReplay {
    private final StateModelRegistry registry;
    private final RollupEvaluator rollups;

    public StateModelReplay(StateModelRegistry registry, RollupEvaluator rollups) {
        this.registry = registry;
        this.rollups = rollups;
    }

    /**
     * Replay the rebuilt order through {@code modelId} version {@code proposedVersion}. {@code modelId} null = the model
     * of the order's line entities (header model when the order has no lines).
     */
    public StateModelReplayResult replay(RebuiltOrder rebuilt, String modelId, int proposedVersion) {
        String id = modelId;
        if (id == null || id.isBlank()) {
            id = rebuilt.lines().stream().map(RebuiltOrder.EntityState::modelId).filter(m -> m != null).findFirst()
                .orElseGet(() -> rebuilt.header() == null ? null : rebuilt.header().modelId());
        }
        if (id == null) throw new ValidationException("Order " + rebuilt.orderId() + " has no state-machine entities to replay");
        if (!registry.exists(id, proposedVersion)) throw new ValidationException("State model " + id + " version " + proposedVersion + " is not published", Map.of("modelId", id, "version", proposedVersion));
        StateModel proposed = registry.get(id, proposedVersion);
        StateModelReplayResult folded = fold(rebuilt, proposed);

        String before = rebuilt.headerStatus();
        String after = before;
        RebuiltOrder.EntityState header = rebuilt.header();
        if (header != null && header.modelId() != null) {
            boolean proposedIsHeader = header.modelId().equals(proposed.id());
            StateModel headerModel = proposedIsHeader ? proposed : registry.get(header.modelId(), header.modelVersion());
            Map<String, Integer> childQty = new LinkedHashMap<>();
            Set<String> zeroFill = new LinkedHashSet<>();
            Set<String> childTerminal = new LinkedHashSet<>();
            if (proposedIsHeader) {
                // Header model proposed: re-derive from the recorded line buckets.
                for (RebuiltOrder.EntityState line : rebuilt.lines()) line.states().forEach((s, q) -> childQty.merge(s, q, Integer::sum));
                rebuilt.lines().stream().map(RebuiltOrder.EntityState::modelId).filter(m -> m != null).distinct().forEach(m -> {
                    StateModel lm = registry.get(m, rebuilt.lines().stream().filter(l -> m.equals(l.modelId())).findFirst().map(RebuiltOrder.EntityState::modelVersion).orElse(1));
                    lm.states().forEach(s -> { zeroFill.add(s.id()); if (s.terminal()) childTerminal.add(s.id()); });
                });
            } else {
                // Line model proposed: roll the simulated line buckets up with the current header rules.
                folded.simulatedBuckets().forEach((entityId, buckets) -> buckets.forEach((s, q) -> childQty.merge(s, q, Integer::sum)));
                proposed.states().forEach(s -> { zeroFill.add(s.id()); if (s.terminal()) childTerminal.add(s.id()); });
            }
            if (!headerModel.rollup().isEmpty() && !childQty.isEmpty()) {
                String derived = rollups.evaluate(headerModel, childQty, zeroFill, childTerminal);
                if (derived != null && headerModel.hasState(derived)) after = derived;
            }
        }
        return folded.withHeader(before, after);
    }

    /** Pure fold of the entities pinned to {@code proposed.id()} through the proposed model. */
    public static StateModelReplayResult fold(RebuiltOrder rebuilt, StateModel proposed) {
        Map<String, Map<String, Integer>> sim = new LinkedHashMap<>();
        Map<String, String> lastEntered = new LinkedHashMap<>();
        Map<String, String> before = new LinkedHashMap<>();
        int currentVersion = 0;
        String initial = proposed.initial().id();
        for (RebuiltOrder.EntityState e : rebuilt.entities().values()) {
            if (!proposed.id().equals(e.modelId())) continue;
            Map<String, Integer> buckets = new LinkedHashMap<>();
            buckets.put(initial, Math.max(1, e.initialQty() > 0 ? e.initialQty() : e.totalQty()));
            sim.put(e.entityId(), buckets);
            lastEntered.put(e.entityId(), initial);
            before.put(e.entityId(), e.dominantState());
            if (currentVersion == 0) currentVersion = e.modelVersion();
        }
        Set<String> unmapped = new LinkedHashSet<>();
        List<StateModelReplayResult.Step> steps = new ArrayList<>();
        int replayed = 0, blocked = 0, retargeted = 0;
        for (RebuiltOrder.RecordedTransition t : rebuilt.transitions()) {
            Map<String, Integer> buckets = sim.get(t.entityId());
            if (buckets == null) continue;
            if (t.isRollup()) {
                steps.add(new StateModelReplayResult.Step(t.entityId(), t.trigger(), t.from(), t.to(), null, null, t.qty(), StateModelReplayResult.SKIPPED, "header roll-up is derived, not replayed"));
                continue;
            }
            replayed++;
            String status = StateModelReplayResult.APPLIED;
            String reason = null;
            String src = null;
            if (t.from() != null && !proposed.hasState(t.from())) {
                unmapped.add(t.from());
                status = StateModelReplayResult.UNMAPPED;
                reason = "recorded state '" + t.from() + "' is not declared by " + proposed.id() + " v" + proposed.version();
            } else if (t.from() != null && buckets.getOrDefault(t.from(), 0) > 0 && !proposed.transitionsFrom(t.from(), t.trigger()).isEmpty()) {
                src = t.from();
            }
            if (src == null) {
                // Drifted or unmapped: any simulated state holding quantity with this trigger.
                for (var b : buckets.entrySet()) {
                    if (b.getValue() > 0 && !proposed.transitionsFrom(b.getKey(), t.trigger()).isEmpty()) { src = b.getKey(); break; }
                }
            }
            if (src == null) {
                blocked++;
                String held = String.join(",", statesWithQty(buckets));
                steps.add(new StateModelReplayResult.Step(t.entityId(), t.trigger(), t.from(), t.to(), held, null, t.qty(), StateModelReplayResult.BLOCKED,
                    (reason == null ? "" : reason + "; ") + "no transition on '" + t.trigger() + "' from simulated state(s) [" + held + "]"));
                continue;
            }
            List<TransitionDef> candidates = proposed.transitionsFrom(src, t.trigger());
            TransitionDef chosen = candidates.stream().filter(c -> c.to().equals(t.to())).findFirst().orElse(candidates.get(0));
            if (!chosen.to().equals(t.to())) {
                retargeted++;
                reason = (reason == null ? "" : reason + "; ") + "recorded target '" + t.to() + "' replaced by '" + chosen.to() + "'";
            }
            int available = buckets.getOrDefault(src, 0);
            int moved = Math.min(Math.max(1, t.qty()), available);
            buckets.merge(src, -moved, Integer::sum);
            buckets.merge(chosen.to(), moved, Integer::sum);
            buckets.values().removeIf(q -> q <= 0);
            lastEntered.put(t.entityId(), chosen.to());
            steps.add(new StateModelReplayResult.Step(t.entityId(), t.trigger(), t.from(), t.to(), src, chosen.to(), moved, status, reason));
        }
        Map<String, String> after = new LinkedHashMap<>();
        sim.forEach((id, buckets) -> after.put(id, LedgerFold.dominant(buckets, lastEntered.get(id))));
        return new StateModelReplayResult(proposed.id(), currentVersion, proposed.version(), replayed, blocked, retargeted, new ArrayList<>(unmapped), null, null, before, after, sim, steps);
    }

    private static List<String> statesWithQty(Map<String, Integer> buckets) {
        List<String> out = new ArrayList<>();
        buckets.forEach((s, q) -> { if (q > 0) out.add(s); });
        return out;
    }

    /** Terminal state ids of a model (exposed for callers composing roll-ups). */
    public static Set<String> terminalStates(StateModel m) {
        Set<String> out = new LinkedHashSet<>();
        for (StateDef s : m.states()) if (s.terminal()) out.add(s.id());
        return out;
    }
}
