package ai.intentoms.statemachine.model;

import java.util.List;
import java.util.Map;

/**
 * A transition (FR-SM-03): trigger kinds are {@code event.*}, {@code api.*}, {@code timer.*} and {@code decision.*};
 * guards are CEL expressions over the standard roots; actions are names handed to the workflow layer through
 * {@code TransitionApplied}; {@code permission} is mandatory for {@code api.*} triggers (FR-SM-10).
 * The id is {@code from->to#trigger} (several sources joined with commas).
 */
public record TransitionDef(String id, List<String> from, String to, String trigger, List<String> guards, List<String> actions,
                            String permission, Map<String, Object> extras) {
    public TransitionDef {
        from = from == null ? List.of() : List.copyOf(from);
        guards = guards == null ? List.of() : List.copyOf(guards);
        actions = actions == null ? List.of() : List.copyOf(actions);
        extras = extras == null ? Map.of() : Map.copyOf(extras);
        if (id == null) id = String.join(",", from) + "->" + to + "#" + trigger;
    }

    public boolean isApi() { return trigger != null && trigger.startsWith("api."); }
    public boolean isTimer() { return trigger != null && trigger.startsWith("timer."); }
    public boolean guarded() { return !guards.isEmpty(); }
}
