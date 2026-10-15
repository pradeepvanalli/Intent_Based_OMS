package ai.intentoms.finance.payment;

/**
 * Outcome of a refund call.
 *
 * @param succeeded     true when the PSP accepted the refund
 * @param pspRef        PSP refund reference
 * @param failureCode   gateway failure code when not succeeded
 * @param failureMessage human-readable failure reason
 */
public record RefundResult(boolean succeeded, String pspRef, String failureCode, String failureMessage) {
    public static RefundResult succeeded(String pspRef) { return new RefundResult(true, pspRef, null, null); }
    public static RefundResult failed(String code, String message) { return new RefundResult(false, null, code, message); }
}
