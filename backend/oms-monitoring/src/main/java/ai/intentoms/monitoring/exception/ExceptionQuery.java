package ai.intentoms.monitoring.exception;

/** Filter for the exceptions inbox (UI contract item 10). Null fields are ignored; newest first. */
public record ExceptionQuery(String status, String type, String severity, String queue, String orderId, String assignee, int offset, int limit) {
    public ExceptionQuery {
        if (offset < 0) offset = 0;
        if (limit <= 0 || limit > 500) limit = 50;
    }
    public static ExceptionQuery open() { return new ExceptionQuery(ExceptionStatus.OPEN, null, null, null, null, null, 0, 50); }
    public static ExceptionQuery forOrder(String orderId) { return new ExceptionQuery(null, null, null, null, orderId, null, 0, 100); }
}
