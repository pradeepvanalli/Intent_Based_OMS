package ai.intentoms.finance.payment;

import ai.intentoms.finance.model.Money;

/**
 * Outcome of a capture call.
 *
 * @param succeeded     true when funds were captured
 * @param pspRef        PSP capture / charge reference
 * @param captured      amount actually captured (equals the request on success)
 * @param failureCode   gateway failure code when not succeeded (e.g. {@code auth_expired})
 * @param failureMessage human-readable failure reason
 */
public record CaptureResult(boolean succeeded, String pspRef, Money captured, String failureCode, String failureMessage) {
    public static CaptureResult succeeded(String pspRef, Money captured) { return new CaptureResult(true, pspRef, captured, null, null); }
    public static CaptureResult failed(String code, String message) { return new CaptureResult(false, null, null, code, message); }
}
