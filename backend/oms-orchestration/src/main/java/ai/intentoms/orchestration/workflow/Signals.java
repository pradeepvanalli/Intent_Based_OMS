package ai.intentoms.orchestration.workflow;

/** Signal names the order and return workflows wait for (FR-WF-05); webhooks and ops push them. */
public final class Signals {
    private Signals() {}
    public static final String SHIPPED = "shipped";
    public static final String STORE_REJECTED = "store_rejected";
    public static final String SHORT_PICK = "short_pick";
    public static final String PICK_STARTED = "pick_started";
    public static final String PACKED = "packed";
    public static final String PICKED = "picked";
    public static final String STAGED_FOR_PICKUP = "staged_for_pickup";
    public static final String PICKED_UP = "picked_up";
    public static final String CANCEL_REQUESTED = "cancel_requested";
    public static final String RESOURCED = "resourced";
    public static final String HOLD_RELEASED = "hold_released";
    public static final String INVENTORY_AVAILABLE = "inventory_available";
    public static final String DELIVERED = "delivered";
    public static final String CARRIER_EXCEPTION = "carrier_exception";
    public static final String RETURNED_TO_SENDER = "returned_to_sender";
    public static final String AGENT_VERIFIED = "agent_verified";
    public static final String AGENT_REJECTED = "agent_rejected";
    public static final String ORDER_MODIFIED = "order.modified";
    public static final String PICKUP_WINDOW_EXPIRED = "pickup_window_expired";
}
