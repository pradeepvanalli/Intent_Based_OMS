package ai.intentoms.returns.model;

/** Filter for {@code ReturnService.list}; null fields are ignored. */
public record ReturnQuery(String status, String orderId, String customerRef, String riskLevel, String channel, int offset, int limit) {
    public ReturnQuery {
        if (offset < 0) offset = 0;
        if (limit <= 0 || limit > 500) limit = 50;
    }
}
