package ai.intentoms.order.validation.steps;

import ai.intentoms.order.capture.CaptureRequest;
import ai.intentoms.order.model.CatalogItem;
import ai.intentoms.order.validation.AddressVerification;
import ai.intentoms.order.validation.AddressVerifier;
import ai.intentoms.order.validation.ValidationContext;
import ai.intentoms.order.validation.ValidationIssue;
import ai.intentoms.order.validation.ValidationStep;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Step {@code address} (FR-CAP-05 address verification): a ship-to address is required unless every line is digital or
 * the order type is pickup-only (BOPIS types carry a {@code pickup} address instead); each address is run through the
 * {@link AddressVerifier}: {@code invalid} and {@code undeliverable} block, PO boxes are typed {@code po_box} with a
 * warning (compliance uses the type for lithium rules). Verification outcomes are recorded on the context so the order
 * stores {@code verified} and the normalised address type.
 */
@Component
public class AddressValidationStep implements ValidationStep {
    public static final String NAME = "address";
    private final AddressVerifier verifier;

    public AddressValidationStep(AddressVerifier verifier) { this.verifier = verifier; }

    @Override public String name() { return NAME; }

    @Override
    public List<ValidationIssue> validate(CaptureRequest req, ValidationContext ctx) {
        List<ValidationIssue> out = new ArrayList<>();
        boolean pickupType = ctx.orderType() != null && ctx.orderType().lineStateModel() != null && ctx.orderType().lineStateModel().contains("bopis");
        if (req.shipTo() == null && !allDigital(req, ctx) && !(pickupType && req.address("pickup") != null)) {
            out.add(ValidationIssue.block("ship_to_required", "addresses[ship_to]", "a ship_to address is required for physical goods"));
        }
        for (CaptureRequest.AddressInput a : req.addresses()) {
            if (a.type() == null) continue;
            String ref = "addresses[" + a.type() + "]";
            AddressVerification v = verifier.verify(a);
            ctx.addressVerified(a.type(), v);
            switch (v.status()) {
                case "undeliverable" -> out.add(ValidationIssue.block("address_undeliverable", ref, String.join("; ", v.messages())));
                case "invalid" -> out.add(ValidationIssue.block("address_invalid", ref, String.join("; ", v.messages())));
                case "unverified" -> out.add(ValidationIssue.warn("address_unverified", ref, String.join("; ", v.messages())));
                default -> { }
            }
            if ("po_box".equals(v.addressType()) && "ship_to".equals(a.type())) out.add(ValidationIssue.warn("po_box", ref + ".addressType", "ship-to address is a PO box"));
        }
        return out;
    }

    private static boolean allDigital(CaptureRequest req, ValidationContext ctx) {
        if (req.lines().isEmpty()) return false;
        for (CaptureRequest.LineInput l : req.lines()) {
            CatalogItem item = ctx.catalogItem(l.sku());
            boolean digital = item != null && item.flag("digital") || l.attributes() != null && Boolean.TRUE.equals(l.attributes().get("digital"));
            if (!digital) return false;
        }
        return true;
    }
}
