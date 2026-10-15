package ai.intentoms.simulation.replay;

import ai.intentoms.platform.ledger.Actor;
import ai.intentoms.platform.ledger.Event;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Builds synthetic order ledgers for the pure fold tests. */
final class LedgerFixtures {
    static final String ORDER = "ord_test";
    static final Instant T0 = Instant.parse("2026-10-05T10:00:00Z");
    private final List<Event> events = new ArrayList<>();

    private LedgerFixtures() {}

    static LedgerFixtures ledger() { return new LedgerFixtures(); }

    LedgerFixtures event(String type, Map<String, Object> payload) {
        long seq = events.size() + 1;
        events.add(new Event("evt_" + seq, "test", "order", ORDER, seq, type, payload, Actor.system("test"), null, ORDER, Map.of(), T0.plusSeconds(seq)));
        return this;
    }

    LedgerFixtures captured(String channel, String orderType) { return event("order.captured", map("orderId", ORDER, "channel", channel, "orderType", orderType)); }

    LedgerFixtures initialized(String entityType, String entityId, String modelId, int version, String state, int qty) {
        return event("order.state_initialized", map("entityType", entityType, "entityId", entityId, "modelId", modelId, "modelVersion", version, "state", state, "qty", qty));
    }

    LedgerFixtures transitioned(String entityType, String entityId, String modelId, int version, String from, String to, int qty, String trigger, Map<String, Integer> before, Map<String, Integer> after) {
        Map<String, Object> p = map("entityType", entityType, "entityId", entityId, "modelId", modelId, "modelVersion", version, "from", from, "to", to, "qty", qty, "trigger", trigger);
        p.put("before", before);
        p.put("after", after);
        return event("order.transitioned", p);
    }

    LedgerFixtures holdPlaced(String holdId, String type, String scopeType, String scopeId) {
        return event("hold.placed", map("holdId", holdId, "type", type, "scopeType", scopeType, "scopeId", scopeId, "blocks", List.of("release"), "reasonCode", "TEST"));
    }

    LedgerFixtures holdReleased(String holdId, String type) { return event("hold.released", map("holdId", holdId, "type", type, "resolution", "cleared")); }

    LedgerFixtures sourced(String planId, String policy, List<Map<String, Object>> packages, double totalCost, double margin) {
        return event("order.sourced", map("planId", planId, "policy", policy, "packages", packages, "totalCost", totalCost, "margin", margin, "meetsFloor", true, "decisionRecordId", "rec_1"));
    }

    static Map<String, Object> pkg(String node, double onTime, double cost, String eta) { return map("nodeId", node, "allocations", List.of(), "eta", eta, "onTime", onTime, "cost", cost); }

    List<Event> events() { return List.copyOf(events); }

    static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    static Map<String, Integer> qty(Object... kv) {
        Map<String, Integer> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], (Integer) kv[i + 1]);
        return m;
    }
}
