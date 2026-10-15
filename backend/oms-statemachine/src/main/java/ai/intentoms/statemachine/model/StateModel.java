package ai.intentoms.statemachine.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A parsed, immutable state model version (FR-SM-01, FR-SM-06, FR-SM-09). {@code yaml} is the source as published so
 * the designer can round-trip it. {@code quantityLevel} models keep per-state quantity buckets (FR-SM-02).
 */
public record StateModel(String id, int version, String entity, boolean quantityLevel, List<StateDef> states,
                         List<TransitionDef> transitions, List<RollupRule> rollup, String yaml) {
    public StateModel {
        states = states == null ? List.of() : List.copyOf(states);
        transitions = transitions == null ? List.of() : List.copyOf(transitions);
        rollup = rollup == null ? List.of() : List.copyOf(rollup);
    }

    /** The single {@link MetaState#CREATED} state; validated models always have exactly one. */
    public StateDef initial() {
        return states.stream().filter(StateDef::initial).findFirst()
            .orElseThrow(() -> new IllegalStateException("State model " + id + " v" + version + " has no Created state"));
    }

    public Optional<StateDef> state(String stateId) {
        for (StateDef s : states) if (s.id().equals(stateId)) return Optional.of(s);
        return Optional.empty();
    }

    public boolean hasState(String stateId) { return state(stateId).isPresent(); }

    public boolean isTerminal(String stateId) { return state(stateId).map(StateDef::terminal).orElse(false); }

    /** Transitions whose source list contains {@code stateId}, in declaration order. */
    public List<TransitionDef> transitionsFrom(String stateId) {
        List<TransitionDef> out = new ArrayList<>();
        for (TransitionDef t : transitions) if (t.from().contains(stateId)) out.add(t);
        return out;
    }

    /** Transitions from {@code stateId} fired by {@code trigger}, in declaration order. */
    public List<TransitionDef> transitionsFrom(String stateId, String trigger) {
        List<TransitionDef> out = new ArrayList<>();
        for (TransitionDef t : transitions) if (t.from().contains(stateId) && t.trigger().equals(trigger)) out.add(t);
        return out;
    }

    public Optional<TransitionDef> transition(String transitionId) {
        for (TransitionDef t : transitions) if (t.id().equals(transitionId)) return Optional.of(t);
        return Optional.empty();
    }

    /** Position of a state in declaration order (used for deterministic tie-breaks). */
    public int indexOf(String stateId) {
        for (int i = 0; i < states.size(); i++) if (states.get(i).id().equals(stateId)) return i;
        return Integer.MAX_VALUE;
    }

    /** Serialisable view stored in {@code state_model.parsed} and returned by the REST API. */
    public Map<String, Object> toMap() {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("stateModel", id);
        m.put("version", version);
        m.put("entity", entity);
        m.put("quantityLevel", quantityLevel);
        List<Map<String, Object>> st = new ArrayList<>();
        for (StateDef s : states) {
            Map<String, Object> sm = new java.util.LinkedHashMap<>();
            sm.put("id", s.id());
            sm.put("meta", s.meta().name());
            if (s.sla() != null) sm.put("sla", Map.of("maxAge", s.sla().maxAge().toString(), "onBreach", s.sla().onBreach()));
            if (s.parent() != null) sm.put("parent", s.parent());
            if (!s.blocksIfHeld().isEmpty()) sm.put("blocksIfHeld", s.blocksIfHeld());
            st.add(sm);
        }
        m.put("states", st);
        List<Map<String, Object>> tr = new ArrayList<>();
        for (TransitionDef t : transitions) {
            Map<String, Object> tm = new java.util.LinkedHashMap<>();
            tm.put("id", t.id());
            tm.put("from", t.from());
            tm.put("to", t.to());
            tm.put("trigger", t.trigger());
            tm.put("guards", t.guards());
            tm.put("actions", t.actions());
            if (t.permission() != null) tm.put("permission", t.permission());
            if (!t.extras().isEmpty()) tm.put("extras", t.extras());
            tr.add(tm);
        }
        m.put("transitions", tr);
        List<Map<String, Object>> ru = new ArrayList<>();
        for (RollupRule r : rollup) ru.add(Map.of("parentState", r.parentState(), "when", r.expression(), "priority", r.priority()));
        m.put("rollup", ru);
        return m;
    }
}
