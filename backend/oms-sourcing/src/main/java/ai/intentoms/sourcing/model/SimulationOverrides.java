package ai.intentoms.sourcing.model;

import java.util.Set;

/**
 * What-if knobs the simulation module (FR-SIM-02) applies to a sourcing run: closed nodes are excluded, a carrier
 * outage adds two transit days and lowers on-time probability by 0.15, {@code tariffDelta} multiplies landed cost by
 * {@code (1 + tariffDelta)}.
 */
public record SimulationOverrides(Set<String> closedNodes, boolean carrierOutage, double tariffDelta) {
    public static final int OUTAGE_EXTRA_DAYS = 2;
    public static final double OUTAGE_ON_TIME_DELTA = -0.15;

    public SimulationOverrides {
        closedNodes = closedNodes == null ? Set.of() : Set.copyOf(closedNodes);
    }

    public static SimulationOverrides none() { return new SimulationOverrides(Set.of(), false, 0.0); }
    public boolean closed(String nodeId) { return closedNodes.contains(nodeId); }
    public double tariffMultiplier() { return Math.max(0.0, 1.0 + tariffDelta); }
    public int extraTransitDays() { return carrierOutage ? OUTAGE_EXTRA_DAYS : 0; }
    public double onTimeDelta() { return carrierOutage ? OUTAGE_ON_TIME_DELTA : 0.0; }
}
