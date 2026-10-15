package ai.intentoms.finance.payment;

import ai.intentoms.platform.error.ValidationException;

import java.util.Locale;

/** Tender types accepted by {@link PaymentService#authorize} (FR-FIN-01 split tender, FR-FIN-06 agent tokens). */
public enum TenderType {
    CARD, PAYPAL, GIFT_CARD, NET_TERMS, AGENT_TOKEN;

    /** Persisted / wire form: lower-case snake case ("gift_card"). */
    public String code() { return name().toLowerCase(Locale.ROOT); }

    public static TenderType parse(String code) {
        if (code == null) throw new ValidationException("tenderType is required");
        try { return TenderType.valueOf(code.trim().toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException e) { throw new ValidationException("Unknown tender type '" + code + "'"); }
    }
}
