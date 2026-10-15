package ai.intentoms.sourcing;

import ai.intentoms.sourcing.model.Node;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Fulfilment node registry (FR-SRC-01, FR-SRC-07, FR-SRC-08). Nodes are tenant data; the learned penalty is kept
 * per node and per node+sku and decays with a 7-day half-life so one bad week does not blacklist a store forever.
 */
public interface NodeRegistry {
    /** The node with its effective node-level penalty; throws NotFoundException. */
    Node get(String id);

    Optional<Node> find(String id);

    /** Active nodes, ordered by id, each carrying its effective node-level penalty. */
    List<Node> active();

    /** Every node (active or not), ordered by id. */
    List<Node> all();

    /** Creates or replaces a node definition; writes {@code node.upserted}. */
    Node upsert(Node n);

    /** Adds {@code delta} to the learned penalty of the node (sku null) or node+sku; writes {@code node.penalised} (FR-SRC-07). */
    void penalise(String nodeId, String sku, double delta, String reason);

    /** Effective (decayed) penalty for a node+sku: node-level + sku-level, clamped to [0,1]. {@code sku} may be null. */
    double penalty(String nodeId, String sku);

    /** Node recovered: forget the learned penalties (FR-SRC-07 counterpart); returns rows cleared. */
    int clearPenalties(String nodeId, String reason);

    /** Merges live health signals (backlog, pick_rate, reject_rate, staffing, weather, carrier_capacity); writes {@code node.health_updated} (FR-SRC-08). */
    Node updateHealth(String nodeId, Map<String, Object> signals);
}
