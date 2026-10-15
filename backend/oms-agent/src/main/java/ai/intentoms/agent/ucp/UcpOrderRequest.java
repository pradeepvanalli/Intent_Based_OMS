package ai.intentoms.agent.ucp;

import ai.intentoms.agent.mandate.MandateCreate;
import ai.intentoms.finance.model.Money;
import ai.intentoms.order.capture.CaptureRequest;
import ai.intentoms.platform.error.ValidationException;
import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * UCP {@code POST /ucp/v1/orders} body (FR-AGT-02, snake_case per the UCP order capability): a mandate reference or an
 * inline mandate, the buyer, line items, ship-to, delegated payment and the buyer's instructions. {@link #toCapture}
 * maps it onto the canonical {@code CaptureRequest} subset the agent order service completes with provenance.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record UcpOrderRequest(
    MandateRef mandate,
    Buyer buyer,
    @JsonProperty("line_items") List<LineItem> lineItems,
    @JsonProperty("ship_to") ShipTo shipTo,
    Payment payment,
    String instructions,
    @JsonProperty("ship_method") String shipMethod,
    @JsonProperty("order_type") String orderType,
    @JsonProperty("client_order_id") String clientOrderId,
    List<Charge> charges,
    String currency,
    @JsonProperty("buyer_acknowledgement") String buyerAcknowledgement
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record MandateRef(String id, @JsonProperty("buyer_ref") String buyerRef, Object scope, @JsonProperty("spend_limit") Money spendLimit,
                             @JsonProperty("instruction_text") String instructionText, String signature, @JsonProperty("payment_token") String paymentToken,
                             @JsonProperty("expires_at") java.time.Instant expiresAt) {
        public boolean inline() { return (id == null || id.isBlank()) && spendLimit != null; }
    }
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Buyer(String ref, String email, String name, String phone, String tier) {}
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record LineItem(@JsonProperty("line_id") String lineId, String sku, @JsonAlias({"qty"}) int quantity, @JsonProperty("unit_price") Money unitPrice, Money discount) {}
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ShipTo(String name, @JsonAlias({"address_line1", "line_1"}) String line1, @JsonAlias({"address_line2", "line_2"}) String line2, String city, @JsonAlias({"region"}) String state,
                         @JsonAlias({"postal_code", "zip_code"}) String zip, String country, String phone) {}
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Payment(String token, String type, Money amount) {}
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Charge(String type, String code, Money amount) {}

    public CaptureRequest toCapture() {
        if (lineItems == null || lineItems.isEmpty()) throw new ValidationException("line_items must contain at least one item");
        List<CaptureRequest.LineInput> lines = new ArrayList<>();
        int i = 1;
        for (LineItem li : lineItems) {
            if (li.sku() == null || li.sku().isBlank()) throw new ValidationException("line_items[].sku is required");
            if (li.quantity() <= 0) throw new ValidationException("line_items[].quantity must be positive");
            if (li.unitPrice() == null) throw new ValidationException("line_items[].unit_price is required");
            lines.add(new CaptureRequest.LineInput(li.lineId() == null || li.lineId().isBlank() ? "L" + i : li.lineId(), li.sku(), null, li.quantity(), li.unitPrice(), li.discount(), null, null));
            i++;
        }
        List<CaptureRequest.AddressInput> addresses = new ArrayList<>();
        if (shipTo != null) addresses.add(new CaptureRequest.AddressInput("ship_to", shipTo.name() == null && buyer != null ? buyer.name() : shipTo.name(), shipTo.line1(), shipTo.line2(), shipTo.city(), shipTo.state(), shipTo.zip(),
            shipTo.country() == null ? "US" : shipTo.country(), shipTo.phone(), null));
        List<CaptureRequest.TenderInput> tenders = new ArrayList<>();
        if (payment != null && payment.token() != null && !payment.token().isBlank()) {
            tenders.add(new CaptureRequest.TenderInput(payment.type() == null ? "agent_token" : payment.type(), payment.token(), payment.amount(), Map.of()));
        }
        List<CaptureRequest.ChargeInput> chargeInputs = new ArrayList<>();
        if (charges != null) for (Charge c : charges) if (c.amount() != null) chargeInputs.add(new CaptureRequest.ChargeInput(c.type() == null ? "shipping" : c.type(), c.code(), c.amount(), null));
        CaptureRequest.CustomerInput customer = buyer == null ? null : new CaptureRequest.CustomerInput(buyer.ref(), buyer.name(), buyer.email(), buyer.phone(), buyer.tier(), null, null, Map.of());
        return new CaptureRequest("agent", clientOrderId, orderType, buyer == null ? null : buyer.ref(), customer, lines, addresses, chargeInputs, tenders, List.of(), List.of(),
            shipMethod, null, currency, Map.of(), null);
    }

    /** Inline mandate → issuance request for the given agent. */
    public MandateCreate inlineMandate(String agentId) {
        MandateRef m = mandate;
        return new MandateCreate(null, agentId, m.buyerRef() != null ? m.buyerRef() : buyer == null ? null : buyer.ref(), m.scope(), m.spendLimit(),
            m.instructionText() != null ? m.instructionText() : instructions, m.signature(), m.paymentToken() != null ? m.paymentToken() : payment == null ? null : payment.token(), m.expiresAt(), null);
    }
}
