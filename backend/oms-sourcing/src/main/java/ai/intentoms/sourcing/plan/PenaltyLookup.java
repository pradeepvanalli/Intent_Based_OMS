package ai.intentoms.sourcing.plan;

import ai.intentoms.sourcing.model.Node;

/** Effective learned penalty for a node and sku (FR-SRC-07); the node-level value alone when no lookup is wired. */
@FunctionalInterface
public interface PenaltyLookup {
    double penalty(String nodeId, String sku);

    static PenaltyLookup nodeLevel() { return (n, s) -> 0.0; }

    /** Package penalty: node-level penalty plus the worst sku-level penalty among the allocations. */
    default double forPackage(Node node, Iterable<String> skus) {
        double worst = 0;
        for (String sku : skus) worst = Math.max(worst, penalty(node.id(), sku));
        return Math.max(0.0, Math.min(1.0, node.penalty() + worst));
    }
}
