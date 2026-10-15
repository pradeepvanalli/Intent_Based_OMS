package ai.intentoms.order.validation.steps;

import ai.intentoms.finance.compliance.ComplianceCustomer;
import ai.intentoms.finance.compliance.ComplianceDestination;
import ai.intentoms.finance.compliance.ComplianceInput;
import ai.intentoms.finance.compliance.ComplianceLine;
import ai.intentoms.finance.compliance.ComplianceService;
import ai.intentoms.finance.compliance.ComplianceViolation;
import ai.intentoms.order.capture.CaptureRequest;
import ai.intentoms.order.capture.LineEnricher;
import ai.intentoms.order.validation.AddressVerification;
import ai.intentoms.order.validation.ValidationContext;
import ai.intentoms.order.validation.ValidationIssue;
import ai.intentoms.order.validation.ValidationStep;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Step {@code compliance} (FR-CAP-05, FR-FIN-03, GR-01): restricted items by state and ZIP, age-restricted goods, hazmat
 * routing, lithium and export controls through the finance {@link ComplianceService} (deterministic L0, no model).
 * {@code block} violations block capture; {@code hold} violations are captured with a warning and a {@code compliance}
 * hold that blocks release until an operator clears it; {@code require_signature} is recorded as a warning.
 */
@Component
public class ComplianceStep implements ValidationStep {
    public static final String NAME = "compliance";
    public static final String HOLD_TYPE = "compliance";
    /** Transitions a compliance hold blocks: release to a node and any shipment. */
    public static final Set<String> HOLD_BLOCKS = Set.of("decision.sourcing_plan", "release", "event.shipped");

    private final ComplianceService compliance;

    public ComplianceStep(ComplianceService compliance) { this.compliance = compliance; }

    @Override public String name() { return NAME; }

    @Override
    public List<ValidationIssue> validate(CaptureRequest req, ValidationContext ctx) {
        List<ValidationIssue> out = new ArrayList<>();
        List<ComplianceLine> lines = new ArrayList<>();
        for (CaptureRequest.LineInput l : req.lines()) {
            if (l.sku() == null) continue;
            lines.add(new ComplianceLine(l.lineId(), l.sku(), l.qty(), LineEnricher.attributes(l, ctx.catalogItem(l.sku()))));
        }
        CaptureRequest.AddressInput ship = req.shipTo() != null ? req.shipTo() : req.address("pickup");
        AddressVerification v = ship == null ? null : ctx.addressResults().get(ship.type());
        ComplianceDestination dest = ship == null ? new ComplianceDestination(null, null, null, null)
            : new ComplianceDestination(ship.state(), ship.zip(), ship.country() == null ? "US" : ship.country(), v != null && v.addressType() != null ? v.addressType() : ship.addressType());
        ComplianceCustomer customer = req.customer() == null ? ComplianceCustomer.unverified()
            : new ComplianceCustomer(Boolean.TRUE.equals(req.customer().ageVerified()), req.customer().age());
        List<ComplianceViolation> violations = compliance.check(new ComplianceInput(lines, dest, customer, req.shipMethod()));
        ctx.addViolations(violations);
        for (ComplianceViolation cv : violations) {
            String field = cv.lineId() == null ? "addresses[ship_to]" : "lines[" + cv.lineId() + "]";
            switch (cv.action()) {
                case "block" -> out.add(ValidationIssue.block(cv.reasonCode(), field, cv.message()));
                case "hold" -> {
                    out.add(ValidationIssue.warn(cv.reasonCode(), field, cv.message() + " (compliance hold placed)"));
                    ctx.requestHold(HOLD_TYPE, HOLD_BLOCKS, cv.reasonCode(), cv.message());
                }
                default -> out.add(ValidationIssue.warn(cv.reasonCode(), field, cv.message()));
            }
        }
        return out;
    }
}
