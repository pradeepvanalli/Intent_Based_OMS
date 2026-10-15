package ai.intentoms.order.validation;

/**
 * One finding of the capture validation pipeline (FR-CAP-05). {@code severity} is {@code block} (capture is refused,
 * 422 with the full list) or {@code warn} (captured, recorded on the ledger; may also place a hold). {@code field} is a
 * JSON pointer-ish path into the {@code CaptureRequest} ("lines[L1].unitPrice", "addresses[ship_to].zip").
 */
public record ValidationIssue(String severity, String code, String field, String message, String step) {
    public static final String BLOCK = "block";
    public static final String WARN = "warn";

    public static ValidationIssue block(String code, String field, String message) { return new ValidationIssue(BLOCK, code, field, message, null); }
    public static ValidationIssue warn(String code, String field, String message) { return new ValidationIssue(WARN, code, field, message, null); }

    public boolean blocks() { return BLOCK.equals(severity); }
    public ValidationIssue withStep(String s) { return new ValidationIssue(severity, code, field, message, s); }
}
