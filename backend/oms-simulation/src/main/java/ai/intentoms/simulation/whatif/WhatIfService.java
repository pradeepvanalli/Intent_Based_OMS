package ai.intentoms.simulation.whatif;

import ai.intentoms.platform.error.ValidationException;
import ai.intentoms.simulation.batch.BatchRequest;
import ai.intentoms.simulation.batch.BatchSimulationService;
import ai.intentoms.simulation.batch.SimulationJob;
import ai.intentoms.sourcing.NodeRegistry;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What-if scenarios as one-call batch jobs (FR-SIM-03; UC-13 node closure, UC-14 carrier outage, demo): each helper
 * builds the right {@link ScenarioOverrides} and submits a {@link BatchSimulationService} job labelled with its kind.
 */
@Service
public class WhatIfService {
    public static final String NODE_CLOSURE = "node_closure";
    public static final String CARRIER_OUTAGE = "carrier_outage";
    public static final String TARIFF_CHANGE = "tariff_change";

    private final BatchSimulationService batches;
    private final NodeRegistry nodes;

    public WhatIfService(BatchSimulationService batches, NodeRegistry nodes) {
        this.batches = batches;
        this.nodes = nodes;
    }

    /** Close one node for every order placed in the range (FR-SIM-03 node closure). */
    public SimulationJob nodeClosure(String nodeId, Instant from, Instant to) { return nodeClosure(Set.of(requireNode(nodeId)), from, to, null); }

    public SimulationJob nodeClosure(Set<String> nodeIds, Instant from, Instant to, Integer sampleLimit) {
        if (nodeIds == null || nodeIds.isEmpty()) throw new ValidationException("at least one nodeId is required");
        nodeIds.forEach(this::requireNode);
        String label = "Node closure: " + String.join(", ", nodeIds);
        return batches.submit(new BatchRequest(from, to, ScenarioOverrides.nodeClosure(nodeIds), sampleLimit, label), NODE_CLOSURE);
    }

    /** Carrier outage: +2 transit days and −0.15 on-time probability on every package (FR-SIM-03). */
    public SimulationJob carrierOutage(Instant from, Instant to) { return carrierOutage(List.of(), from, to, null); }

    public SimulationJob carrierOutage(List<String> carriers, Instant from, Instant to, Integer sampleLimit) {
        String label = carriers == null || carriers.isEmpty() ? "Carrier outage" : "Carrier outage: " + String.join(", ", carriers);
        return batches.submit(new BatchRequest(from, to, ScenarioOverrides.carrierOutage(carriers), sampleLimit, label), CARRIER_OUTAGE);
    }

    /** Tariff change: landed cost × (1 + delta), e.g. 0.25 for +25 % (FR-SIM-03, FR-FIN-05). */
    public SimulationJob tariffChange(double delta, Instant from, Instant to) { return tariffChange(delta, from, to, null); }

    public SimulationJob tariffChange(double delta, Instant from, Instant to, Integer sampleLimit) {
        if (delta <= -1.0) throw new ValidationException("tariffDelta must be greater than -1 (a -100 % tariff is not meaningful)", Map.of("tariffDelta", delta));
        String label = String.format(java.util.Locale.ROOT, "Tariff change: %+.0f%%", delta * 100);
        return batches.submit(new BatchRequest(from, to, ScenarioOverrides.tariffChange(delta), sampleLimit, label), TARIFF_CHANGE);
    }

    private String requireNode(String nodeId) {
        if (nodeId == null || nodeId.isBlank()) throw new ValidationException("nodeId is required");
        if (nodes.find(nodeId).isEmpty()) throw new ValidationException("Unknown node " + nodeId, Map.of("nodeId", nodeId));
        return nodeId;
    }
}
