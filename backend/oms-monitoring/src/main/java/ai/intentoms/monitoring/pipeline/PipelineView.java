package ai.intentoms.monitoring.pipeline;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Live pipeline (FR-MON-01, UI contract item 8): open orders by state x age bucket, plus roll-ups by channel, node and
 * order type. {@code atRisk} counts orders whose predicted breach risk is high or critical, {@code held} orders with an
 * active hold.
 */
public record PipelineView(List<String> states, List<String> ageBuckets, List<Cell> cells, Map<String, Long> byChannel, Map<String, Long> byNode,
                           Map<String, Long> byOrderType, long openOrders, long atRisk, long held, Instant generatedAt) {
    public record Cell(String state, String ageBucket, long count) {}
}
