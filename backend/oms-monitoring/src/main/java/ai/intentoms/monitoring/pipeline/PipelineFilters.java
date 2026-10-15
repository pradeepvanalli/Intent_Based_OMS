package ai.intentoms.monitoring.pipeline;

/** Optional filters of the pipeline view (FR-MON-01); null means "all". */
public record PipelineFilters(String channel, String orderType, String nodeId) {
    public static final PipelineFilters NONE = new PipelineFilters(null, null, null);

    public PipelineFilters {
        channel = blankToNull(channel);
        orderType = blankToNull(orderType);
        nodeId = blankToNull(nodeId);
    }

    private static String blankToNull(String s) { return s == null || s.isBlank() ? null : s.trim(); }

    public String cacheKey() { return (channel == null ? "" : channel) + "|" + (orderType == null ? "" : orderType) + "|" + (nodeId == null ? "" : nodeId); }
}
