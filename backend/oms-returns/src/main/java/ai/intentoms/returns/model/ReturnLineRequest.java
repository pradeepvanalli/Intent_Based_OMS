package ai.intentoms.returns.model;

/**
 * One line of a {@link ReturnRequest}.
 *
 * @param lineId             order line id
 * @param qty                units returned (1..delivered − already returned)
 * @param reasonCode         optional taxonomy key the channel already knows (wrong_size, defective, ...)
 * @param customerReasonText optional free text the customer typed for this line
 */
public record ReturnLineRequest(String lineId, int qty, String reasonCode, String customerReasonText) {}
