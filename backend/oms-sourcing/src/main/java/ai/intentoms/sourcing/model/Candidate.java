package ai.intentoms.sourcing.model;

import java.util.Map;

/**
 * A feasible node for a sourcing request (FR-SRC-01).
 *
 * @param coverage    sku -> units this node can supply towards the request (capped by request qty and remaining capacity)
 * @param complete    the node covers every requested unit
 * @param distanceKm  great-circle distance from the node to the destination
 * @param transitDays days from now until delivery for the requested ship method (includes the cutoff roll-over and any
 *                    simulated carrier outage)
 */
public record Candidate(Node node, Map<String, Integer> coverage, boolean complete, double distanceKm, int transitDays) {
    public Candidate {
        coverage = coverage == null ? Map.of() : Map.copyOf(coverage);
    }

    public int coveredUnits() { int n = 0; for (int q : coverage.values()) n += q; return n; }
    public int covered(String sku) { return coverage.getOrDefault(sku, 0); }
    public String nodeId() { return node.id(); }
}
