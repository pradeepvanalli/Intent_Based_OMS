package ai.intentoms.returns.model;

import java.util.List;

/**
 * Buy-online-return-in-store convenience request (BORIS, FR-RET-01). The store associate scans either the OMS order id
 * or the channel order number printed on the receipt; the items are handed over on the spot, so the RMA is created,
 * decided and received at {@code storeNodeId} in one call.
 *
 * @param orderId        OMS order id, or null when {@code channelOrderId} is given
 * @param channel        originating sales channel of the order (needed to resolve {@code channelOrderId}); defaults to web
 * @param channelOrderId channel order number as printed on the receipt
 * @param lines          lines returned
 * @param storeNodeId    node id of the receiving store
 * @param receiptPresent whether a receipt was shown (default true when the channel order id was scanned)
 */
public record InStoreReturnRequest(String orderId, String channel, String channelOrderId, List<ReturnLineRequest> lines, String storeNodeId, Boolean receiptPresent) {
    public InStoreReturnRequest {
        lines = lines == null ? List.of() : List.copyOf(lines);
    }
}
