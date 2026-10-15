package ai.intentoms.statemachine.model;

import java.util.List;

/**
 * A tenant-defined state (FR-SM-01). {@code parent} supports nested sub-states (FR-SM-04, e.g. picking/packed under
 * released); {@code blocksIfHeld} lists hold types that prevent entering this state (FR-SM-07).
 */
public record StateDef(String id, MetaState meta, SlaDef sla, String parent, List<String> blocksIfHeld) {
    public StateDef {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("State id is required");
        meta = meta == null ? MetaState.ACTIVE : meta;
        blocksIfHeld = blocksIfHeld == null ? List.of() : List.copyOf(blocksIfHeld);
    }

    public boolean terminal() { return meta == MetaState.TERMINAL; }
    public boolean initial() { return meta == MetaState.CREATED; }
}
