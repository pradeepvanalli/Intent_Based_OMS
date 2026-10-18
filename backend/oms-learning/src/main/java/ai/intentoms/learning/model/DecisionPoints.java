package ai.intentoms.learning.model;

import ai.intentoms.decision.model.DecisionRecord;

import java.util.Map;

/**
 * What one "decision point" is, shared by the episode assembler, the off-policy evaluator and the reference-data
 * seeder so they always agree: (decision, subject type, subject id, line, fulfilment group). The key formula must
 * stay identical to the one in migration V1603.
 */
public final class DecisionPoints {
    private DecisionPoints() {}

    public static String key(String decision, String subjectType, String subjectId, String orderId, String lineId, String fulfillmentGroupId) {
        return decision + "|" + nz(subjectType) + "|" + (subjectId != null ? subjectId : nz(orderId)) + "|" + nz(lineId) + "|" + nz(fulfillmentGroupId);
    }

    public static String key(DecisionRecord r, String correlationId) {
        return key(r.decision(), r.subjectType(), r.subjectId(), orderId(r, correlationId), lineId(r), fulfillmentGroupId(r));
    }

    /**
     * The order a decision is about: its subject when the subject is an order (or a line, "<orderId>:<lineId>"),
     * else the correlation id, else an order id carried in the input ({@code order.id}, {@code orderId},
     * {@code order_id}). Null when none applies — such an episode is joined to outcomes by its subject instead.
     */
    @SuppressWarnings("unchecked")
    public static String orderId(DecisionRecord r, String correlationId) {
        if ("order".equals(r.subjectType())) return r.subjectId();
        if (("line".equals(r.subjectType()) || "order_line".equals(r.subjectType())) && r.subjectId() != null && r.subjectId().contains(":"))
            return r.subjectId().substring(0, r.subjectId().indexOf(':'));
        if (correlationId != null) return correlationId;
        if (r.correlationId() != null) return r.correlationId();
        Map<String, Object> in = r.input() == null ? Map.of() : r.input();
        if (in.get("order") instanceof Map<?, ?> o && o.get("id") != null) return String.valueOf(o.get("id"));
        Object direct = in.getOrDefault("orderId", in.get("order_id"));
        return direct == null ? null : String.valueOf(direct);
    }

    public static String lineId(DecisionRecord r) {
        if (("line".equals(r.subjectType()) || "order_line".equals(r.subjectType())) && r.subjectId() != null) {
            int i = r.subjectId().indexOf(':');
            return i < 0 ? r.subjectId() : r.subjectId().substring(i + 1);
        }
        Object l = r.input() == null ? null : r.input().get("lineId");
        return l == null ? null : String.valueOf(l);
    }

    public static String fulfillmentGroupId(DecisionRecord r) {
        Object fg = r.input() == null ? null : r.input().get("fulfillmentGroupId");
        return fg == null ? null : String.valueOf(fg);
    }

    private static String nz(String s) { return s == null ? "" : s; }
}
