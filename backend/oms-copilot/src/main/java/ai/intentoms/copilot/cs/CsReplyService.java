package ai.intentoms.copilot.cs;

import ai.intentoms.copilot.config.PromptTemplates;
import ai.intentoms.copilot.llm.CopilotLlmClient;
import ai.intentoms.copilot.llm.Grounding;
import ai.intentoms.copilot.llm.JsonSchema;
import ai.intentoms.copilot.llm.Untrusted;
import ai.intentoms.decision.model.DecisionOptions;
import ai.intentoms.decision.model.DecisionRecord;
import ai.intentoms.decision.model.DecisionResult;
import ai.intentoms.decision.runtime.DecisionService;
import ai.intentoms.finance.payment.Refund;
import ai.intentoms.order.contact.CustomerContactService;
import ai.intentoms.order.model.FulfilmentGroup;
import ai.intentoms.order.model.Order;
import ai.intentoms.order.model.OrderLine;
import ai.intentoms.order.model.OrderView;
import ai.intentoms.order.model.ReturnSummary;
import ai.intentoms.order.model.ShipmentSummary;
import ai.intentoms.order.service.OrderService;
import ai.intentoms.platform.error.OmsException;
import ai.intentoms.platform.error.ValidationException;
import ai.intentoms.platform.ledger.Actor;
import ai.intentoms.platform.security.AuthorizationService;
import ai.intentoms.platform.time.TimeSource;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * CS reply copilot (FR-CS-05, GR-15): drafts a customer reply grounded only in {@link OrderView} facts and decision
 * outputs. The customer message is classified with the {@code customer_request_intent} decision and framed as untrusted
 * data (GR-08). Every date and amount in the draft must come from the order; otherwise a deterministic, slot-filled
 * template is returned instead. The copilot never promises anything outside policy: refund amounts come only from
 * issued/recorded refunds, dates only from promise dates, shipment ETAs or delivery timestamps.
 */
@Service
public class CsReplyService {
    private static final Logger log = LoggerFactory.getLogger(CsReplyService.class);
    public static final String DISCLAIMER = "Generated draft grounded in order facts and decision outputs; dates and amounts are copied from the order record. Review before sending (FR-CS-05, GR-15).";
    private static final JsonSchema SCHEMA = JsonSchema.named("cs_reply", JsonSchema.object(
        "draft", JsonSchema.string("The reply text"),
        "factsUsed", JsonSchema.array("Keys of the facts used", JsonSchema.string(null))));

    private final OrderService orders;
    private final DecisionService decisions;
    private final CopilotLlmClient llm;
    private final PromptTemplates templates;
    private final AuthorizationService authz;
    private final TimeSource time;
    private final CustomerContactService contacts;

    public CsReplyService(OrderService orders, DecisionService decisions, CopilotLlmClient llm, PromptTemplates templates, AuthorizationService authz, TimeSource time,
                          CustomerContactService contacts) {
        this.orders = orders;
        this.decisions = decisions;
        this.llm = llm;
        this.templates = templates;
        this.authz = authz;
        this.time = time;
        this.contacts = contacts;
    }

    /** NF-07: the order's own names, addresses and contact details, removed from the message too. */
    private ai.intentoms.platform.pii.KnownPii knownPii(String orderId) {
        try { return ai.intentoms.order.model.OrderPii.known(orders.get(orderId)); } catch (RuntimeException e) { return ai.intentoms.platform.pii.KnownPii.NONE; }
    }

    public CsDraft draft(String orderId, String customerMessage, String intentHint) {
        if (customerMessage == null || customerMessage.isBlank()) throw new ValidationException("The customer message is required");
        OrderView view = orders.view(orderId);
        // NF-07: contact details typed into the message never reach the decision log or the model prompt.
        String redacted = ai.intentoms.platform.pii.FreeTextRedactor.redact(customerMessage, knownPii(orderId));
        Map<String, Object> classification = classify(view, redacted, intentHint);
        String intent = String.valueOf(classification.get("intent"));
        recordWismo(orderId, intent, customerMessage);
        Map<String, Object> facts = facts(view);
        Map<String, Object> slots = slots(view, facts);
        if (llm.available()) {
            try {
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("customerMessage", Untrusted.text("customer", redacted));
                payload.put("classifiedIntent", intent);
                payload.put("facts", facts);
                payload.put("slots", slots);
                payload.put("policy", Map.of("mayPromiseRefund", slots.get("refundAmount") != null, "mayStateDate", slots.get("promiseDate") != null || facts.get("deliveredAt") != null,
                    "returnWindowDays", facts.getOrDefault("returnWindowDays", "unknown")));
                JsonNode out = llm.complete("cs_reply", templates.get("system-cs.md"), payload, SCHEMA);
                String text = out.path("draft").asText();
                List<String> used = new ArrayList<>();
                out.path("factsUsed").forEach(n -> used.add(n.asText()));
                List<String> ungrounded = Grounding.ungroundedDatesAndAmounts(text, facts);
                if (ungrounded.isEmpty() && !text.isBlank()) return new CsDraft(text, used, slots, DISCLAIMER, classification, true, llm.model(), null, facts);
                log.warn("cs draft for {} rejected: dates/amounts not in order facts {}", orderId, ungrounded);
                return new CsDraft(template(view, intent, slots, facts), List.copyOf(slotsUsed(slots)), slots, DISCLAIMER, classification, false, null, "generated text contained dates or amounts absent from the order: " + ungrounded, facts);
            } catch (OmsException e) {
                log.warn("cs draft for {} fell back: {}", orderId, e.getMessage());
                return new CsDraft(template(view, intent, slots, facts), List.copyOf(slotsUsed(slots)), slots, DISCLAIMER, classification, false, null, e.getMessage(), facts);
            }
        }
        return new CsDraft(template(view, intent, slots, facts), List.copyOf(slotsUsed(slots)), slots, DISCLAIMER, classification, false, null, "no model key configured", facts);
    }

    /**
     * OC-03: a customer asking where their order is counts against the decisions that shaped it (the reward's contact
     * term). Recorded once per message (the message hash is the contact's external id), so re-drafting the same
     * message does not count twice. Never fails the draft.
     */
    private void recordWismo(String orderId, String intent, String message) {
        if (!"wismo".equals(intent)) return;
        try {
            contacts.record(orderId, new CustomerContactService.Contact("wismo", "cs", "cs_copilot", "cs-msg:" + ai.intentoms.platform.json.Json.sha256(message).substring(0, 16)));
        } catch (RuntimeException e) {
            log.warn("Could not record WISMO contact for order {}: {}", orderId, e.toString());
        }
    }

    private Map<String, Object> classify(OrderView view, String message, String hint) {
        Order o = view.order();
        long days = o.placedAt() == null ? 0 : Math.max(0, Duration.between(o.placedAt(), time.now()).toDays());
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("request", Map.of("channel", "cs", "text", message));
        input.put("order", Map.of("status", o.status() == null ? "unknown" : o.status(), "days_since_placed", days));
        DecisionResult r = decisions.decide("customer_request_intent", input, DecisionOptions.live("order", o.id(), Actor.parse(authz.actorRef())));
        Map<String, Object> c = new LinkedHashMap<>();
        String decided = r.value() == null ? "other" : String.valueOf(r.value());
        c.put("intent", hint != null && !hint.isBlank() ? hint : decided);
        c.put("decidedIntent", decided);
        c.put("hint", hint);
        c.put("confidence", r.confidence());
        c.put("band", r.band() == null ? null : r.band().name());
        c.put("layer", r.layer() == null ? null : r.layer().name());
        c.put("decisionRecordId", r.recordId());
        return c;
    }

    /** Facts a reply may state. Keys are stable so the model can cite them. */
    static Map<String, Object> facts(OrderView v) {
        Order o = v.order();
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("orderId", o.id());
        f.put("channelOrderId", o.channelOrderId());
        f.put("status", o.status());
        f.put("headerState", v.header() == null ? null : v.header().state());
        f.put("placedAt", o.placedAt() == null ? null : o.placedAt().toString());
        f.put("orderType", o.orderType());
        f.put("shipMethod", o.shipMethod());
        f.put("grandTotal", o.totals() == null || o.totals().grandTotal() == null ? null : o.totals().grandTotal().amount().toPlainString() + " " + o.totals().grandTotal().currency());
        f.put("customerTier", o.customer() == null ? null : o.customer().tier());
        f.put("lines", o.lines().stream().map(l -> lineFacts(l)).toList());
        f.put("lineStates", v.lineStatuses() == null ? List.of() : v.lineStatuses().stream().map(s -> Map.of("line", s.entityId().substring(s.entityId().indexOf(':') + 1), "state", s.state())).toList());
        o.shipTo().ifPresent(a -> f.put("shipToCityState", (a.city() == null ? "" : a.city()) + (a.state() == null ? "" : ", " + a.state())));
        List<Map<String, Object>> groups = new ArrayList<>();
        for (FulfilmentGroup g : v.fulfilmentGroups()) {
            Map<String, Object> gm = new LinkedHashMap<>();
            gm.put("nodeId", g.nodeId());
            gm.put("status", g.status());
            if (g.promiseDate() != null) gm.put("promiseDate", g.promiseDate().toString());
            gm.put("lines", g.allocations().stream().map(FulfilmentGroup.Allocation::lineId).toList());
            groups.add(gm);
        }
        f.put("fulfilmentGroups", groups);
        List<Map<String, Object>> shipments = new ArrayList<>();
        for (ShipmentSummary s : v.shipments()) {
            Map<String, Object> sm = new LinkedHashMap<>();
            sm.put("carrier", s.carrier());
            sm.put("tracking", s.tracking());
            sm.put("status", s.status());
            if (s.shippedAt() != null) sm.put("shippedAt", s.shippedAt().toString());
            if (s.deliveredAt() != null) { sm.put("deliveredAt", s.deliveredAt().toString()); f.put("deliveredAt", s.deliveredAt().toString()); }
            if (s.eta() != null) sm.put("eta", s.eta().toString());
            shipments.add(sm);
        }
        f.put("shipments", shipments);
        f.put("refunds", v.refunds().stream().map(r -> refundFacts(r)).toList());
        f.put("returns", v.returns().stream().map(r -> returnFacts(r)).toList());
        f.put("activeHolds", v.holds().stream().filter(h -> h.resolvedAt() == null).map(h -> h.type()).toList());
        if (v.nextExpectedEvent() != null) { f.put("nextExpectedEvent", v.nextExpectedEvent().name()); if (v.nextExpectedEvent().expectedBy() != null) f.put("nextExpectedBy", v.nextExpectedEvent().expectedBy().toString()); }
        List<Map<String, Object>> decs = new ArrayList<>();
        for (DecisionRecord d : v.decisions()) {
            Map<String, Object> dm = new LinkedHashMap<>();
            dm.put("decision", d.decision());
            dm.put("value", d.effectiveValue());
            dm.put("action", d.action());
            decs.add(dm);
        }
        f.put("decisions", decs);
        return f;
    }

    private static Map<String, Object> lineFacts(OrderLine l) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("lineId", l.lineId());
        m.put("sku", l.sku());
        if (l.description() != null) m.put("description", l.description());
        m.put("qty", l.qty());
        m.put("unitPrice", l.unitPrice().amount().toPlainString());
        if (l.status() != null) m.put("status", l.status());
        return m;
    }

    private static Map<String, Object> refundFacts(Refund r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("amount", r.amount() == null ? null : r.amount().amount().toPlainString() + " " + r.amount().currency());
        m.put("status", r.status());
        return m;
    }

    private static Map<String, Object> returnFacts(ReturnSummary r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("rma", r.rma());
        m.put("status", r.status());
        if (r.refundTiming() != null) m.put("refundTiming", r.refundTiming());
        if (r.refundAmount() != null) m.put("refundAmount", r.refundAmount().amount().toPlainString() + " " + r.refundAmount().currency());
        return m;
    }

    /** Typed slots (GR-15): only values the order record actually carries. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> slots(OrderView v, Map<String, Object> facts) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("status", facts.get("status"));
        LocalDate promise = v.fulfilmentGroups().stream().map(FulfilmentGroup::promiseDate).filter(Objects::nonNull).max(LocalDate::compareTo).orElse(null);
        if (promise == null) promise = v.shipments().stream().map(ShipmentSummary::eta).filter(Objects::nonNull).map(i -> i.atZone(java.time.ZoneOffset.UTC).toLocalDate()).max(LocalDate::compareTo).orElse(null);
        s.put("promiseDate", promise == null ? null : promise.toString());
        String refund = v.refunds().stream().filter(r -> r.amount() != null).map(r -> r.amount().amount().toPlainString() + " " + r.amount().currency()).findFirst().orElse(null);
        if (refund == null) for (Map<String, Object> rf : (List<Map<String, Object>>) facts.get("returns")) if (rf.get("refundAmount") != null) { refund = String.valueOf(rf.get("refundAmount")); break; }
        s.put("refundAmount", refund);
        s.put("trackingUrl", v.shipments().stream().filter(sh -> sh.tracking() != null).map(sh -> (sh.carrier() == null ? "" : sh.carrier() + " ") + sh.tracking()).findFirst().orElse(null));
        return s;
    }

    private static List<String> slotsUsed(Map<String, Object> slots) {
        List<String> used = new ArrayList<>();
        slots.forEach((k, v) -> { if (v != null) used.add(k); });
        return used;
    }

    /** Deterministic reply filled only from typed slots; the fallback for every failure mode. */
    static String template(OrderView v, String intent, Map<String, Object> slots, Map<String, Object> facts) {
        StringBuilder sb = new StringBuilder("Thank you for contacting us about order ").append(facts.get("channelOrderId") == null ? facts.get("orderId") : facts.get("channelOrderId")).append(". ");
        String status = slots.get("status") == null ? "being processed" : String.valueOf(slots.get("status")).replace('_', ' ');
        switch (intent == null ? "other" : intent) {
            case "wismo" -> {
                sb.append("Your order is currently ").append(status).append('.');
                if (slots.get("promiseDate") != null) sb.append(" The expected delivery date is ").append(slots.get("promiseDate")).append('.');
                if (slots.get("trackingUrl") != null) sb.append(" Tracking: ").append(slots.get("trackingUrl")).append('.');
                if (slots.get("promiseDate") == null && slots.get("trackingUrl") == null) sb.append(" We will confirm the delivery date as soon as the shipment is scheduled.");
            }
            case "return" -> {
                sb.append("We have noted your request to return an item from this order.");
                if (slots.get("refundAmount") != null) sb.append(" A refund of ").append(slots.get("refundAmount")).append(" is recorded on this order.");
                else sb.append(" Our team will confirm the refund amount and timing once the return is reviewed.");
            }
            case "cancel_order", "cancel_line" -> sb.append("We have received your cancellation request. Your order is currently ").append(status).append("; our team will confirm whether it can still be changed.");
            case "change_address", "change_item" -> sb.append("We have received your change request. Your order is currently ").append(status).append("; our team will confirm whether the change is still possible.");
            default -> sb.append("Your order is currently ").append(status).append(". Our team will follow up with the details you asked about.");
        }
        return sb.toString();
    }
}
