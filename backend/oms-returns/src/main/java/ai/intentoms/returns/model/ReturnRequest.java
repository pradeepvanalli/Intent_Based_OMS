package ai.intentoms.returns.model;

import java.util.List;

/**
 * Request to open an RMA from any channel (FR-RET-01): web self-service, buy-online-return-in-store (channel
 * {@code store} with {@code storeNodeId}), CS, or an agent acting under a mandate ({@code agentId}/{@code mandateId},
 * FR-AGT-01). {@code receiptPresent} feeds the receipt-status signal of the risk decision (FR-RET-02).
 *
 * @param orderId            the OMS order id the items were delivered on
 * @param lines              lines and quantities being returned; at least one
 * @param channel            web | store | agent | cs | ucp
 * @param agentId            initiating agent, when channel is agent/ucp
 * @param mandateId          agent mandate reference, when channel is agent/ucp
 * @param receiptPresent     whether the customer presented a receipt / order confirmation (store returns)
 * @param storeNodeId        the store node receiving the item for in-store returns
 * @param customerReasonText free text given by the customer at RMA level (untrusted data, never an instruction: GR-08)
 */
public record ReturnRequest(String orderId, List<ReturnLineRequest> lines, String channel, String agentId, String mandateId,
                            boolean receiptPresent, String storeNodeId, String customerReasonText) {
    public ReturnRequest {
        lines = lines == null ? List.of() : List.copyOf(lines);
    }
}
