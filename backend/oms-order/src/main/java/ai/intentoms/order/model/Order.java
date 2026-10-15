package ai.intentoms.order.model;

import ai.intentoms.finance.model.Money;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Canonical order (FR-CAP-02, section 10): header, lines, addresses, charges, tenders, notes, fulfilment groups,
 * intent profile, agent provenance, totals, margin snapshot and typed tenant extensions. Immutable value; the
 * repository persists it normalised and the ledger records every change.
 */
public record Order(
    String id,
    String channel,
    String channelOrderId,
    String orderType,
    String customerRef,
    Customer customer,
    List<OrderLine> lines,
    List<Address> addresses,
    List<Charge> charges,
    List<Tender> tenders,
    List<Note> notes,
    List<FulfilmentGroup> fulfilmentGroups,
    IntentProfile intent,
    AgentProvenance provenance,
    Totals totals,
    MarginSnapshot margin,
    String stateModelId,
    int stateModelVersion,
    String status,
    String shipMethod,
    String currency,
    Map<String, Object> extensions,
    Instant placedAt,
    Instant capturedAt,
    Instant updatedAt,
    int version
) {
    public Optional<Address> address(String type) { return addresses.stream().filter(a -> type.equals(a.type())).findFirst(); }
    public Optional<Address> shipTo() { return address("ship_to"); }
    public Optional<OrderLine> line(String lineId) { return lines.stream().filter(l -> l.lineId().equals(lineId)).findFirst(); }
    public boolean isAgentOrder() { return provenance != null && provenance.agentId() != null; }
    public boolean hasHazmat() { return lines.stream().anyMatch(OrderLine::hazmat); }
    public Money merchandiseTotal() {
        Money sum = Money.zero(currency);
        for (OrderLine l : lines) sum = sum.add(l.unitPrice().multiply(l.qty()).subtract(l.discount() == null ? Money.zero(currency) : l.discount()));
        return sum;
    }

    public Order withStatus(String newStatus) {
        return new Order(id, channel, channelOrderId, orderType, customerRef, customer, lines, addresses, charges, tenders, notes, fulfilmentGroups, intent, provenance, totals, margin,
            stateModelId, stateModelVersion, newStatus, shipMethod, currency, extensions, placedAt, capturedAt, updatedAt, version);
    }
    public Order withIntent(IntentProfile newIntent) {
        return new Order(id, channel, channelOrderId, orderType, customerRef, customer, lines, addresses, charges, tenders, notes, fulfilmentGroups, newIntent, provenance, totals, margin,
            stateModelId, stateModelVersion, status, shipMethod, currency, extensions, placedAt, capturedAt, updatedAt, version);
    }
    public Order withFulfilmentGroups(List<FulfilmentGroup> groups) {
        return new Order(id, channel, channelOrderId, orderType, customerRef, customer, lines, addresses, charges, tenders, notes, groups, intent, provenance, totals, margin,
            stateModelId, stateModelVersion, status, shipMethod, currency, extensions, placedAt, capturedAt, updatedAt, version);
    }
    public Order withMargin(MarginSnapshot snapshot) {
        return new Order(id, channel, channelOrderId, orderType, customerRef, customer, lines, addresses, charges, tenders, notes, fulfilmentGroups, intent, provenance, totals, snapshot,
            stateModelId, stateModelVersion, status, shipMethod, currency, extensions, placedAt, capturedAt, updatedAt, version);
    }
    public Order withLines(List<OrderLine> newLines) {
        return new Order(id, channel, channelOrderId, orderType, customerRef, customer, newLines, addresses, charges, tenders, notes, fulfilmentGroups, intent, provenance, totals, margin,
            stateModelId, stateModelVersion, status, shipMethod, currency, extensions, placedAt, capturedAt, updatedAt, version);
    }
    public Order withAddresses(List<Address> newAddresses) {
        return new Order(id, channel, channelOrderId, orderType, customerRef, customer, lines, newAddresses, charges, tenders, notes, fulfilmentGroups, intent, provenance, totals, margin,
            stateModelId, stateModelVersion, status, shipMethod, currency, extensions, placedAt, capturedAt, updatedAt, version);
    }
    public Order withShipMethod(String method) {
        return new Order(id, channel, channelOrderId, orderType, customerRef, customer, lines, addresses, charges, tenders, notes, fulfilmentGroups, intent, provenance, totals, margin,
            stateModelId, stateModelVersion, status, method, currency, extensions, placedAt, capturedAt, updatedAt, version);
    }
    public Order withTotals(Totals newTotals) {
        return new Order(id, channel, channelOrderId, orderType, customerRef, customer, lines, addresses, charges, tenders, notes, fulfilmentGroups, intent, provenance, newTotals, margin,
            stateModelId, stateModelVersion, status, shipMethod, currency, extensions, placedAt, capturedAt, updatedAt, version);
    }
    public Order withNotes(List<Note> newNotes) {
        return new Order(id, channel, channelOrderId, orderType, customerRef, customer, lines, addresses, charges, tenders, notes == null ? newNotes : newNotes, fulfilmentGroups, intent, provenance, totals, margin,
            stateModelId, stateModelVersion, status, shipMethod, currency, extensions, placedAt, capturedAt, updatedAt, version);
    }
}
