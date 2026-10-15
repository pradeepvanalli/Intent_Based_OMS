package ai.intentoms.order.validation;

import ai.intentoms.order.capture.CaptureRequest;

import java.util.List;

/**
 * One configurable step of the capture validation pipeline (FR-CAP-05). Steps are Spring beans discovered by name;
 * the tenant orders and enables them through {@code tenant_config capture.validation_steps}. A step must be pure and
 * fast (no model calls, GR-07) and may record side requests on the {@link ValidationContext} (holds to place, verified
 * addresses) that the order service applies after the order row exists.
 */
public interface ValidationStep {
    /** Stable name used in {@code capture.validation_steps}: schema, item_price_sanity, address, payment_status, compliance, agent_provenance, ... */
    String name();

    List<ValidationIssue> validate(CaptureRequest req, ValidationContext ctx);
}
