package ai.intentoms.agent.evidence;

import ai.intentoms.agent.CustomerSafeOrderView;
import ai.intentoms.agent.mandate.Mandate;
import ai.intentoms.agent.mandate.MandateService;
import ai.intentoms.agent.registry.AgentIdentity;
import ai.intentoms.agent.registry.AgentRegistry;
import ai.intentoms.decision.model.DecisionRecord;
import ai.intentoms.decision.runtime.DecisionService;
import ai.intentoms.finance.payment.Payment;
import ai.intentoms.order.model.AgentProvenance;
import ai.intentoms.order.model.FulfilmentGroup;
import ai.intentoms.order.model.Order;
import ai.intentoms.order.model.OrderView;
import ai.intentoms.order.model.ShipmentSummary;
import ai.intentoms.platform.error.NotFoundException;
import ai.intentoms.platform.json.Json;
import ai.intentoms.platform.ledger.Event;
import ai.intentoms.platform.ledger.EventStore;
import ai.intentoms.platform.observability.Metrics;
import ai.intentoms.platform.time.TimeSource;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * One-click dispute evidence pack (FR-AGT-09, FR-CAP-07, FR-FIN-06): the agent identity and verification method, the
 * mandate with its signature check, the buyer's instruction text and the injection screen, the full decision trail, the
 * {@code agent_mandate_match} record, a customer-safe order snapshot, payments, fulfilment with proof of delivery and a
 * SHA-256 digest of the order's ledger so the pack can be shown to be untampered. 404 when the order carries no agent
 * provenance. Keys match the UI {@code EvidencePack} ({@code identity}, {@code mandate}, {@code injectionScreen},
 * {@code decisionTrail}, {@code deliveryProof}, {@code hash}) plus the detailed sections.
 */
@Service
public class EvidencePackService {
    private final ai.intentoms.order.service.OrderService orders;
    private final MandateService mandates;
    private final AgentRegistry registry;
    private final DecisionService decisions;
    private final EventStore events;
    private final TimeSource time;
    private final Metrics metrics;

    public EvidencePackService(ai.intentoms.order.service.OrderService orders, MandateService mandates, AgentRegistry registry, DecisionService decisions, EventStore events, TimeSource time, Metrics metrics) {
        this.orders = orders;
        this.mandates = mandates;
        this.registry = registry;
        this.decisions = decisions;
        this.events = events;
        this.time = time;
        this.metrics = metrics;
    }

    public Map<String, Object> build(String orderId) {
        return metrics.time("oms.agent.evidence_pack", () -> {
            OrderView view = orders.view(orderId);
            Order order = view.order();
            AgentProvenance p = order.provenance();
            if (p == null || p.agentId() == null) throw new NotFoundException("Order " + orderId + " has no agent provenance; no evidence pack");
            Optional<AgentIdentity> agent = registry.find(p.agentId());
            Optional<Mandate> mandate = p.mandateId() == null ? Optional.empty() : mandates.find(p.mandateId());
            List<DecisionRecord> records = decisions.forSubject("order", orderId);
            List<Event> ledger = events.load("order", orderId);

            Map<String, Object> pack = new LinkedHashMap<>();
            pack.put("orderId", orderId);
            pack.put("generatedAt", time.now());
            pack.put("agent", agentSection(p, agent));
            pack.put("identity", agent.orElse(null));
            pack.put("mandate", mandate.orElse(null));
            pack.put("mandateReference", p.mandateId());
            pack.put("instructionText", p.instructionText());
            pack.put("provenance", provenance(p));
            Object ack = p.raw() == null ? null : p.raw().get("buyerAcknowledgement");
            pack.put("buyerAcknowledgement", ack == null || String.valueOf(ack).isBlank() ? null : ack);
            pack.put("injectionScreen", injectionScreen(p));
            pack.put("decisionTrail", records.stream().map(EvidencePackService::trailEntry).toList());
            pack.put("mandateDecision", records.stream().filter(r -> "agent_mandate_match".equals(r.decision())).reduce((a, b) -> b).map(EvidencePackService::trailEntry).orElse(null));
            pack.put("orderSnapshot", CustomerSafeOrderView.order(view));
            pack.put("payments", view.payments() == null ? List.of() : view.payments().stream().map(EvidencePackService::payment).toList());
            pack.put("refunds", view.refunds() == null ? List.of() : view.refunds().stream().map(r -> Map.of("id", r.id(), "amount", CustomerSafeOrderView.money(r.amount()), "returnId", String.valueOf(r.returnId()))).toList());
            List<Map<String, Object>> fulfilment = fulfilment(view);
            pack.put("fulfilment", fulfilment);
            pack.put("deliveryProof", deliveryProof(view, fulfilment));
            pack.put("holds", view.holds() == null ? List.of() : view.holds().stream().map(h -> Map.of("id", h.id(), "type", h.type(), "reasonCode", String.valueOf(h.reasonCode()), "placedAt", h.placedAt(), "resolution", String.valueOf(h.resolution()))).toList());
            pack.put("ledgerEvents", ledger.size());
            Map<String, Object> signatures = new LinkedHashMap<>();
            signatures.put("mandateSignature", mandate.map(Mandate::signature).orElse(p.signature()));
            signatures.put("mandateSignatureValid", mandate.map(Mandate::signatureValid).orElse(null));
            signatures.put("agentVerification", agent.map(AgentIdentity::verification).orElse("unknown"));
            pack.put("signatures", signatures);
            String digest = Json.sha256(ledger.stream().map(e -> Map.of("id", e.id(), "type", e.type(), "sequence", e.sequence(), "payload", e.payload(), "at", e.occurredAt().toString())).toList());
            pack.put("ledgerDigest", digest);
            pack.put("hash", digest);
            return pack;
        });
    }

    private static Map<String, Object> agentSection(AgentProvenance p, Optional<AgentIdentity> agent) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("agentId", p.agentId());
        m.put("owner", agent.map(AgentIdentity::owner).orElse(null));
        m.put("protocol", p.protocol());
        m.put("trustTier", agent.map(AgentIdentity::trustTier).orElse(null));
        m.put("verification", agent.map(AgentIdentity::verification).orElse("unregistered"));
        m.put("registered", agent.isPresent());
        return m;
    }

    private static Map<String, Object> provenance(AgentProvenance p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("agentId", p.agentId());
        m.put("protocol", p.protocol());
        m.put("mandateId", p.mandateId());
        m.put("spendLimit", CustomerSafeOrderView.money(p.spendLimit()));
        m.put("instructionText", p.instructionText());
        m.put("signature", p.signature());
        m.put("buyerRef", p.buyerRef());
        m.put("withinMandate", p.withinMandate());
        m.put("mandateDecisionRecordId", p.mandateDecisionRecordId());
        m.put("raw", p.raw());
        return m;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> injectionScreen(AgentProvenance p) {
        Object s = p.raw() == null ? null : p.raw().get("injectionScreen");
        Map<String, Object> m = new LinkedHashMap<>();
        if (s instanceof Map<?, ?> sm) {
            Map<String, Object> src = (Map<String, Object>) sm;
            m.put("flagged", Boolean.TRUE.equals(src.get("flagged")));
            m.put("probability", src.get("probability") instanceof Number n ? n.doubleValue() : 0.0);
            m.put("recordId", src.get("recordId"));
            m.put("heuristic", src.get("heuristic"));
        } else {
            m.put("flagged", false);
            m.put("probability", 0.0);
            m.put("screened", false);
        }
        return m;
    }

    static Map<String, Object> trailEntry(DecisionRecord r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("recordId", r.id());
        m.put("id", r.id());
        m.put("decision", r.decision());
        m.put("version", r.version());
        m.put("value", r.effectiveValue());
        m.put("band", r.band());
        m.put("confidence", r.confidence());
        m.put("layer", r.layer());
        m.put("action", r.action());
        m.put("requiresHuman", r.requiresHuman());
        m.put("reasonCodes", r.reasonCodes());
        m.put("overridden", r.overriddenAt() != null);
        m.put("at", r.createdAt());
        m.put("createdAt", r.createdAt());
        return m;
    }

    private static Map<String, Object> payment(Payment p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", p.id());
        m.put("tenderType", p.tenderType());
        m.put("status", p.status());
        m.put("amountAuthorized", CustomerSafeOrderView.money(p.amountAuthorized()));
        m.put("amountCaptured", CustomerSafeOrderView.money(p.amountCaptured()));
        m.put("agentMandate", p.agentMandate());
        m.put("createdAt", p.createdAt());
        return m;
    }

    private static List<Map<String, Object>> fulfilment(OrderView v) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (v.fulfilmentGroups() != null) for (FulfilmentGroup g : v.fulfilmentGroups()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("groupId", g.id());
            m.put("nodeId", g.nodeId());
            m.put("promiseDate", g.promiseDate());
            m.put("status", g.status());
            m.put("lineIds", g.allocations() == null ? List.of() : g.allocations().stream().map(FulfilmentGroup.Allocation::lineId).toList());
            List<Map<String, Object>> ships = new ArrayList<>();
            if (v.shipments() != null) for (ShipmentSummary s : v.shipments()) if (g.nodeId() == null || g.nodeId().equals(s.nodeId())) ships.add(shipment(s));
            m.put("shipments", ships);
            m.put("shippedAt", ships.stream().map(s -> s.get("shippedAt")).filter(x -> x != null).findFirst().orElse(null));
            m.put("deliveredAt", ships.stream().map(s -> s.get("deliveredAt")).filter(x -> x != null).findFirst().orElse(null));
            m.put("tracking", ships.stream().map(s -> s.get("tracking")).filter(x -> x != null).toList());
            out.add(m);
        }
        if (out.isEmpty() && v.shipments() != null) for (ShipmentSummary s : v.shipments()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("nodeId", s.nodeId());
            m.put("shipments", List.of(shipment(s)));
            m.put("shippedAt", s.shippedAt());
            m.put("deliveredAt", s.deliveredAt());
            m.put("tracking", s.tracking() == null ? List.of() : List.of(s.tracking()));
            out.add(m);
        }
        return out;
    }

    private static Map<String, Object> shipment(ShipmentSummary s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", s.id());
        m.put("nodeId", s.nodeId());
        m.put("carrier", s.carrier());
        m.put("service", s.service());
        m.put("tracking", s.tracking());
        m.put("status", s.status());
        m.put("shippedAt", s.shippedAt());
        m.put("deliveredAt", s.deliveredAt());
        return m;
    }

    private static Map<String, Object> deliveryProof(OrderView v, List<Map<String, Object>> fulfilment) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("headerStatus", v.header() == null ? v.order().status() : v.header().state());
        m.put("lineStatuses", v.lineStatuses() == null ? List.of() : v.lineStatuses().stream().map(s -> Map.of("entityId", s.entityId(), "state", s.state(), "quantities", s.qtyMap())).toList());
        m.put("delivered", fulfilment.stream().anyMatch(f -> f.get("deliveredAt") != null) || (v.header() != null && v.header().state() != null && v.header().state().contains("delivered")));
        m.put("shipments", v.shipments() == null ? List.of() : v.shipments().stream().map(EvidencePackService::shipment).toList());
        return m;
    }
}
