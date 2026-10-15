package ai.intentoms.order.model;

import ai.intentoms.finance.model.Money;

import java.util.Map;

/** Payment tender: type card | paypal | gift_card | net_terms | agent_token. token is a PSP token, never a PAN. */
public record Tender(String type, String token, Money amount, String status /*pending|authorized|captured|failed*/, String paymentId, Map<String, Object> details) {
    public Tender withStatus(String s, String pid) { return new Tender(type, token, amount, s, pid, details); }
}
