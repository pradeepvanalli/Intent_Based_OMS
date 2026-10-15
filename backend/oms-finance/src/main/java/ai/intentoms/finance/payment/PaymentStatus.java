package ai.intentoms.finance.payment;

import java.util.EnumSet;
import java.util.Set;

/** Lifecycle of a {@link Payment} (FR-FIN-01). */
public enum PaymentStatus {
    AUTHORIZED, REAUTHORIZED, CAPTURED, PARTIALLY_CAPTURED, VOIDED, FAILED;

    /** Statuses under which uncaptured authorised funds may still be captured or re-authorised. */
    public static final Set<PaymentStatus> OPEN = EnumSet.of(AUTHORIZED, REAUTHORIZED, PARTIALLY_CAPTURED);
    /** Statuses under which captured funds exist and may be refunded. */
    public static final Set<PaymentStatus> REFUNDABLE = EnumSet.of(CAPTURED, PARTIALLY_CAPTURED);

    public boolean isOpen() { return OPEN.contains(this); }
}
