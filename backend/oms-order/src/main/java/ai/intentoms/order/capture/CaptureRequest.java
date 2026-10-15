package ai.intentoms.order.capture;

import ai.intentoms.finance.model.Money;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Inbound order as any channel, connector, storefront webhook or agent protocol submits it (FR-CAP-01). This is the
 * JSON body of {@code POST /api/v1/orders}; adapters (Shopify, UCP, MCP) map their formats onto it. Everything is
 * optional except channel, channelOrderId and at least one line; {@code orderType} defaults to {@code standard_ship}.
 * Untrusted free text (notes, agent instruction text) is carried as data only (GR-08).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CaptureRequest(
    String channel,
    String channelOrderId,
    String orderType,
    String customerRef,
    CustomerInput customer,
    List<LineInput> lines,
    List<AddressInput> addresses,
    List<ChargeInput> charges,
    List<TenderInput> tenders,
    List<NoteInput> notes,
    List<String> intentChoices,
    String shipMethod,
    Instant placedAt,
    String currency,
    Map<String, Object> extensions,
    ProvenanceInput provenance
) {
    public static final String DEFAULT_ORDER_TYPE = "standard_ship";

    public CaptureRequest {
        lines = lines == null ? List.of() : List.copyOf(lines);
        addresses = addresses == null ? List.of() : List.copyOf(addresses);
        charges = charges == null ? List.of() : List.copyOf(charges);
        tenders = tenders == null ? List.of() : List.copyOf(tenders);
        notes = notes == null ? List.of() : List.copyOf(notes);
        intentChoices = intentChoices == null ? List.of() : List.copyOf(intentChoices);
        extensions = extensions == null ? Map.of() : Map.copyOf(extensions);
    }

    public String effectiveOrderType() { return orderType == null || orderType.isBlank() ? DEFAULT_ORDER_TYPE : orderType.trim(); }

    /** Order currency: explicit, else the first line's, else USD. */
    public String effectiveCurrency() {
        if (currency != null && !currency.isBlank()) return currency.trim().toUpperCase();
        for (LineInput l : lines) if (l.unitPrice() != null) return l.unitPrice().currency();
        return "USD";
    }

    public AddressInput address(String type) {
        for (AddressInput a : addresses) if (type.equals(a.type())) return a;
        return null;
    }

    public AddressInput shipTo() { return address("ship_to"); }

    public boolean isAgentOrder() { return provenance != null && provenance.agentId() != null && !provenance.agentId().isBlank(); }

    /** Merchandise total (price × qty − discount) across lines, in the order currency; used before tax is known. */
    public Money merchandiseTotal() {
        String ccy = effectiveCurrency();
        Money sum = Money.zero(ccy);
        for (LineInput l : lines) {
            if (l.unitPrice() == null) continue;
            Money ext = l.unitPrice().multiply(Math.max(0, l.qty()));
            if (l.discount() != null && l.discount().currency().equals(ccy)) ext = ext.subtract(l.discount());
            if (ext.currency().equals(ccy)) sum = sum.add(ext);
        }
        return sum;
    }

    /** Positive header charges (shipping, handling, fees) and negative discounts, in the order currency. */
    public Money chargeTotal() {
        String ccy = effectiveCurrency();
        Money sum = Money.zero(ccy);
        for (ChargeInput c : charges) if (c.amount() != null && c.amount().currency().equals(ccy) && !"tax".equals(c.type())) sum = sum.add(c.amount());
        return sum;
    }

    public Money tenderTotal() {
        String ccy = effectiveCurrency();
        Money sum = Money.zero(ccy);
        for (TenderInput t : tenders) if (t.amount() != null && t.amount().currency().equals(ccy)) sum = sum.add(t.amount());
        return sum;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CustomerInput(String ref, String name, String email, String phone, String tier, Boolean ageVerified, Integer age, Map<String, Object> history) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record LineInput(String lineId, String sku, String description, int qty, Money unitPrice, Money discount, String taxCode, Map<String, Object> attributes) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record AddressInput(String type, String name, String line1, String line2, String city, String state, String zip, String country, String phone, String addressType) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ChargeInput(String type, String code, Money amount, String description) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TenderInput(String type, String token, Money amount, Map<String, Object> details) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record NoteInput(String source, String author, String text, Boolean customerVisible) {}

    /** Agent provenance (FR-CAP-07): identity, protocol, mandate artefact, spend limit and the buyer's instruction text. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ProvenanceInput(String agentId, String protocol, String mandateId, Money spendLimit, String instructionText, String signature, String buyerRef, Map<String, Object> raw) {}
}
