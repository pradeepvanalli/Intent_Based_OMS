package ai.intentoms.order.validation.steps;

import ai.intentoms.finance.model.Money;
import ai.intentoms.order.capture.CaptureRequest;
import ai.intentoms.order.validation.ValidationContext;
import ai.intentoms.order.validation.ValidationIssue;
import ai.intentoms.order.validation.ValidationStep;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Step {@code agent_provenance} (FR-CAP-07): an agent-placed order must carry agent identity, protocol (ucp | acp | mcp)
 * and the buyer's instruction text as evidence; orders on the {@code agent} channel without provenance block. When the
 * mandate's spend limit is below the order total the order is captured with warning {@code beyond_mandate} and an
 * {@code agent_verification} hold that blocks release; the T3 {@code agent_mandate_match} decision and a human confirm
 * run later in the workflow (GR-07: capture never waits on a model). Instruction text is data only (GR-08).
 */
@Component
public class AgentProvenanceStep implements ValidationStep {
    public static final String NAME = "agent_provenance";
    public static final String HOLD_TYPE = "agent_verification";
    public static final Set<String> HOLD_BLOCKS = Set.of("decision.sourcing_plan", "release", "event.shipped");
    private static final Set<String> PROTOCOLS = Set.of("ucp", "acp", "mcp");

    @Override public String name() { return NAME; }

    @Override
    public List<ValidationIssue> validate(CaptureRequest req, ValidationContext ctx) {
        List<ValidationIssue> out = new ArrayList<>();
        CaptureRequest.ProvenanceInput p = req.provenance();
        if (p == null) {
            if ("agent".equalsIgnoreCase(req.channel())) out.add(ValidationIssue.block("provenance_required", "provenance", "orders on the agent channel must carry agent provenance"));
            return out;
        }
        if (blank(p.agentId())) out.add(ValidationIssue.block("provenance_incomplete", "provenance.agentId", "agentId is required on agent orders"));
        if (blank(p.protocol())) out.add(ValidationIssue.block("provenance_incomplete", "provenance.protocol", "protocol (ucp, acp, mcp) is required on agent orders"));
        else if (!PROTOCOLS.contains(p.protocol().toLowerCase())) out.add(ValidationIssue.warn("protocol_unknown", "provenance.protocol", "unknown agent protocol " + p.protocol()));
        if (blank(p.instructionText())) out.add(ValidationIssue.block("provenance_incomplete", "provenance.instructionText", "the buyer's instruction text is required as evidence"));
        if (blank(p.mandateId())) out.add(ValidationIssue.warn("mandate_missing", "provenance.mandateId", "no mandate reference supplied"));
        Money total = ctx.expectedTotal() == null ? req.merchandiseTotal().add(req.chargeTotal()) : ctx.expectedTotal();
        if (p.spendLimit() != null && p.spendLimit().currency().equals(total.currency()) && p.spendLimit().compareTo(total) < 0) {
            out.add(ValidationIssue.warn("beyond_mandate", "provenance.spendLimit", "order total " + total + " exceeds the mandate spend limit " + p.spendLimit() + "; agent_verification hold placed"));
            ctx.requestHold(HOLD_TYPE, HOLD_BLOCKS, "BEYOND_MANDATE", "Order total " + total + " exceeds mandate spend limit " + p.spendLimit());
        }
        return out;
    }

    private static boolean blank(String s) { return s == null || s.isBlank(); }
}
