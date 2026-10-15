package ai.intentoms.returns.store;

import ai.intentoms.finance.model.Money;
import ai.intentoms.platform.error.NotFoundException;
import ai.intentoms.platform.jdbc.Jsonb;
import ai.intentoms.platform.jdbc.Page;
import ai.intentoms.platform.json.Json;
import ai.intentoms.platform.tenant.TenantContext;
import ai.intentoms.returns.model.*;
import com.fasterxml.jackson.core.type.TypeReference;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

/**
 * Persistence for RMAs (tables {@code return_rma}, {@code return_line}). Every statement is scoped by the tenant bound
 * to the thread (ARCHITECTURE §2). Callers append the matching ledger event in the same transaction.
 */
@Repository
public class ReturnRepository {
    private static final TypeReference<List<ReasonCode>> REASON_CODES = new TypeReference<>() {};
    private final JdbcClient jdbc;

    public ReturnRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    @Transactional
    public ReturnRma insert(ReturnRma r) {
        String t = TenantContext.require();
        jdbc.sql("""
            insert into return_rma(rma_id, tenant_id, order_id, channel, customer_ref, customer_email_token, status, reason_code, customer_reason_text, condition, refund_timing,
              risk_score, risk_level, reason_codes, risk_decision_record_id, refund_timing_decision_record_id, reason_decision_record_id, disposition, disposition_decision_record_id,
              eligibility, keep_it, refund, receipt_present, store_node_id, agent_id, mandate_id, review_task_id, review_task_type, scanned_at, received_at, received_node_id,
              inspected_at, inspection_note, refunded_at, created_by, created_at, updated_at)
            values (:rma, :t, :order, :channel, :cref, :email, :status, :reason, :reasonText, :condition, :timing, :score, :level, :codes, :riskRec, :timingRec, :reasonRec,
              :disp, :dispRec, :elig, :keep, :refund, :receipt, :store, :agent, :mandate, :taskId, :taskType, :scanned, :received, :recvNode, :inspected, :note, :refunded,
              :by, :created, :updated)
            """)
            .param("rma", r.rmaId()).param("t", t).param("order", r.orderId()).param("channel", r.channel()).param("cref", r.customerRef()).param("email", r.customerEmailToken())
            .param("status", r.status()).param("reason", r.reasonCode()).param("reasonText", r.customerReasonText()).param("condition", r.condition()).param("timing", r.refundTiming())
            .param("score", r.riskScore()).param("level", r.riskLevel()).param("codes", Jsonb.of(r.reasonCodes())).param("riskRec", r.riskDecisionRecordId())
            .param("timingRec", r.refundTimingDecisionRecordId()).param("reasonRec", r.reasonDecisionRecordId()).param("disp", r.disposition()).param("dispRec", r.dispositionDecisionRecordId())
            .param("elig", r.eligibility() == null ? null : Jsonb.of(r.eligibility())).param("keep", r.keepIt() == null ? null : Jsonb.of(r.keepIt()))
            .param("refund", r.refund() == null ? null : Jsonb.of(r.refund())).param("receipt", r.receiptPresent()).param("store", r.storeNodeId()).param("agent", r.agentId())
            .param("mandate", r.mandateId()).param("taskId", r.reviewTaskId()).param("taskType", r.reviewTaskType()).param("scanned", ts(r.scannedAt())).param("received", ts(r.receivedAt()))
            .param("recvNode", r.receivedNodeId()).param("inspected", ts(r.inspectedAt())).param("note", r.inspectionNote()).param("refunded", ts(r.refundedAt()))
            .param("by", r.createdBy()).param("created", ts(r.createdAt())).param("updated", ts(r.updatedAt()))
            .update();
        writeLines(t, r);
        return r;
    }

    /** Rewrites every mutable header column and the lines. */
    @Transactional
    public ReturnRma update(ReturnRma r, Instant now) {
        String t = TenantContext.require();
        int n = jdbc.sql("""
            update return_rma set status = :status, reason_code = :reason, condition = :condition, refund_timing = :timing, risk_score = :score, risk_level = :level,
              reason_codes = :codes, risk_decision_record_id = :riskRec, refund_timing_decision_record_id = :timingRec, reason_decision_record_id = :reasonRec,
              disposition = :disp, disposition_decision_record_id = :dispRec, eligibility = :elig, keep_it = :keep, refund = :refund, review_task_id = :taskId,
              review_task_type = :taskType, scanned_at = :scanned, received_at = :received, received_node_id = :recvNode, inspected_at = :inspected, inspection_note = :note,
              refunded_at = :refunded, updated_at = :updated
            where tenant_id = :t and rma_id = :rma
            """)
            .param("status", r.status()).param("reason", r.reasonCode()).param("condition", r.condition()).param("timing", r.refundTiming()).param("score", r.riskScore())
            .param("level", r.riskLevel()).param("codes", Jsonb.of(r.reasonCodes())).param("riskRec", r.riskDecisionRecordId()).param("timingRec", r.refundTimingDecisionRecordId())
            .param("reasonRec", r.reasonDecisionRecordId()).param("disp", r.disposition()).param("dispRec", r.dispositionDecisionRecordId())
            .param("elig", r.eligibility() == null ? null : Jsonb.of(r.eligibility())).param("keep", r.keepIt() == null ? null : Jsonb.of(r.keepIt()))
            .param("refund", r.refund() == null ? null : Jsonb.of(r.refund())).param("taskId", r.reviewTaskId()).param("taskType", r.reviewTaskType())
            .param("scanned", ts(r.scannedAt())).param("received", ts(r.receivedAt())).param("recvNode", r.receivedNodeId()).param("inspected", ts(r.inspectedAt()))
            .param("note", r.inspectionNote()).param("refunded", ts(r.refundedAt())).param("updated", ts(now)).param("t", t).param("rma", r.rmaId())
            .update();
        if (n == 0) throw new NotFoundException("Return " + r.rmaId() + " not found");
        jdbc.sql("delete from return_line where tenant_id = :t and rma_id = :rma").param("t", t).param("rma", r.rmaId()).update();
        writeLines(t, r);
        return r;
    }

    private void writeLines(String t, ReturnRma r) {
        for (ReturnLine l : r.lines()) {
            jdbc.sql("""
                insert into return_line(tenant_id, rma_id, line_id, sku, description, qty, unit_price, tax, currency, reason_code, customer_reason_text, condition, disposition, attributes)
                values (:t, :rma, :line, :sku, :d, :q, :p, :tax, :cur, :reason, :text, :cond, :disp, :attr)
                """)
                .param("t", t).param("rma", r.rmaId()).param("line", l.lineId()).param("sku", l.sku()).param("d", l.description()).param("q", l.qty())
                .param("p", l.unitPrice().amount()).param("tax", l.tax() == null ? BigDecimal.ZERO : l.tax().amount()).param("cur", l.unitPrice().currency())
                .param("reason", l.reasonCode()).param("text", l.customerReasonText()).param("cond", l.condition()).param("disp", l.disposition())
                .param("attr", Jsonb.of(l.attributes())).update();
        }
    }

    public Optional<ReturnRma> find(String rmaId) {
        String t = TenantContext.require();
        return jdbc.sql("select *, reason_codes::text as reason_codes_json, eligibility::text as eligibility_json, keep_it::text as keep_it_json, refund::text as refund_json from return_rma where tenant_id = :t and rma_id = :rma")
            .param("t", t).param("rma", rmaId).query().listOfRows().stream().findFirst().map(h -> hydrate(t, h));
    }

    public ReturnRma get(String rmaId) { return find(rmaId).orElseThrow(() -> new NotFoundException("Return " + rmaId + " not found")); }

    /** Row lock for state-changing operations; the caller must be inside a transaction. */
    public ReturnRma lock(String rmaId) {
        String t = TenantContext.require();
        return jdbc.sql("select *, reason_codes::text as reason_codes_json, eligibility::text as eligibility_json, keep_it::text as keep_it_json, refund::text as refund_json from return_rma where tenant_id = :t and rma_id = :rma for update")
            .param("t", t).param("rma", rmaId).query().listOfRows().stream().findFirst().map(h -> hydrate(t, h))
            .orElseThrow(() -> new NotFoundException("Return " + rmaId + " not found"));
    }

    public List<ReturnRma> forOrder(String orderId) {
        String t = TenantContext.require();
        return jdbc.sql("select *, reason_codes::text as reason_codes_json, eligibility::text as eligibility_json, keep_it::text as keep_it_json, refund::text as refund_json from return_rma where tenant_id = :t and order_id = :o order by created_at")
            .param("t", t).param("o", orderId).query().listOfRows().stream().map(h -> hydrate(t, h)).toList();
    }

    /** Units already returned (or in flight) per order line, excluding rejected / cancelled RMAs. */
    public Map<String, Integer> returnedQtyByLine(String orderId) {
        String t = TenantContext.require();
        Map<String, Integer> out = new HashMap<>();
        jdbc.sql("""
            select l.line_id, sum(l.qty) as q from return_line l join return_rma r on r.rma_id = l.rma_id and r.tenant_id = l.tenant_id
            where l.tenant_id = :t and r.order_id = :o and r.status in (:statuses) group by l.line_id
            """)
            .param("t", t).param("o", orderId).param("statuses", new ArrayList<>(ReturnStatus.COUNTS_AS_RETURNED)).query().listOfRows()
            .forEach(r -> out.put((String) r.get("line_id"), ((Number) r.get("q")).intValue()));
        return out;
    }

    public Page<ReturnSummary> list(ReturnQuery q) {
        String t = TenantContext.require();
        StringBuilder where = new StringBuilder(" where r.tenant_id = :t");
        Map<String, Object> params = new HashMap<>();
        params.put("t", t);
        if (q.status() != null && !q.status().isBlank()) { where.append(" and r.status = :status"); params.put("status", q.status()); }
        if (q.orderId() != null && !q.orderId().isBlank()) { where.append(" and r.order_id = :order"); params.put("order", q.orderId()); }
        if (q.customerRef() != null && !q.customerRef().isBlank()) { where.append(" and r.customer_ref = :cref"); params.put("cref", q.customerRef()); }
        if (q.riskLevel() != null && !q.riskLevel().isBlank()) { where.append(" and r.risk_level = :risk"); params.put("risk", q.riskLevel()); }
        if (q.channel() != null && !q.channel().isBlank()) { where.append(" and r.channel = :channel"); params.put("channel", q.channel()); }
        long total = bind(jdbc.sql("select count(*) from return_rma r" + where), params).query(Long.class).single();
        var rows = bind(jdbc.sql("""
            select r.rma_id, r.order_id, r.status, r.created_at, r.updated_at, r.channel, r.customer_ref, r.risk_score, r.risk_level, r.refund_timing, r.disposition, r.refund::text as refund_json,
                   (select count(*) from return_line l where l.tenant_id = r.tenant_id and l.rma_id = r.rma_id) as line_count,
                   (select coalesce(sum(l.qty * l.unit_price), 0) from return_line l where l.tenant_id = r.tenant_id and l.rma_id = r.rma_id) as merch,
                   (select min(l.currency) from return_line l where l.tenant_id = r.tenant_id and l.rma_id = r.rma_id) as currency
            from return_rma r""" + where + " order by r.created_at desc limit :limit offset :offset"), params)
            .param("limit", q.limit()).param("offset", q.offset()).query().listOfRows();
        List<ReturnSummary> items = rows.stream().map(ReturnRepository::summary).toList();
        return Page.of(items, total, q.offset(), q.limit());
    }

    public List<ReturnSummary> summariesForOrder(String orderId) {
        return list(new ReturnQuery(null, orderId, null, null, null, 0, 500)).items();
    }

    /**
     * Returns history for identity resolution (FR-RET-07): RMAs in the window matching the customer ref or the tokenised
     * e-mail, across every channel, excluding the RMA being decided.
     */
    public List<ReturnRma> history(String customerRef, String emailToken, Instant since, String excludeRmaId) {
        String t = TenantContext.require();
        if ((customerRef == null || customerRef.isBlank()) && (emailToken == null || emailToken.isBlank())) return List.of();
        StringBuilder sql = new StringBuilder("select *, reason_codes::text as reason_codes_json, eligibility::text as eligibility_json, keep_it::text as keep_it_json, refund::text as refund_json from return_rma where tenant_id = :t and created_at >= :since and rma_id <> :ex and status not in ('cancelled') and (");
        Map<String, Object> params = new HashMap<>(Map.of("t", t, "since", Timestamp.from(since), "ex", excludeRmaId == null ? "" : excludeRmaId));
        List<String> ors = new ArrayList<>();
        if (customerRef != null && !customerRef.isBlank()) { ors.add("customer_ref = :cref"); params.put("cref", customerRef); }
        if (emailToken != null && !emailToken.isBlank()) { ors.add("customer_email_token = :email"); params.put("email", emailToken); }
        sql.append(String.join(" or ", ors)).append(") order by created_at desc");
        return bind(jdbc.sql(sql.toString()), params).query().listOfRows().stream().map(h -> hydrate(t, h)).toList();
    }

    private static JdbcClient.StatementSpec bind(JdbcClient.StatementSpec spec, Map<String, Object> params) {
        for (var e : params.entrySet()) spec = spec.param(e.getKey(), e.getValue());
        return spec;
    }

    private static ReturnSummary summary(Map<String, Object> r) {
        String currency = r.get("currency") == null ? "USD" : (String) r.get("currency");
        Money refundAmount = null;
        if (r.get("refund_json") != null) {
            RefundInfo ri = Json.read(r.get("refund_json").toString(), RefundInfo.class);
            if (ri.amount() != null) refundAmount = new Money(ri.amount(), ri.currency() == null ? currency : ri.currency());
        }
        if (refundAmount == null && r.get("merch") != null) refundAmount = new Money((BigDecimal) r.get("merch"), currency);
        return new ReturnSummary((String) r.get("rma_id"), (String) r.get("order_id"), (String) r.get("status"), ts(r.get("created_at")), (String) r.get("channel"),
            (String) r.get("customer_ref"), ((Number) r.get("line_count")).intValue(), refundAmount, (Double) r.get("risk_score"), (String) r.get("risk_level"),
            (String) r.get("refund_timing"), (String) r.get("disposition"), ts(r.get("updated_at")));
    }

    private ReturnRma hydrate(String t, Map<String, Object> h) {
        String rmaId = (String) h.get("rma_id");
        List<ReturnLine> lines = jdbc.sql("select *, attributes::text as attributes_json from return_line where tenant_id = :t and rma_id = :rma order by id").param("t", t).param("rma", rmaId)
            .query().listOfRows().stream().map(r -> {
                String cur = (String) r.get("currency");
                return new ReturnLine((String) r.get("line_id"), (String) r.get("sku"), (String) r.get("description"), ((Number) r.get("qty")).intValue(),
                    new Money((BigDecimal) r.get("unit_price"), cur), new Money((BigDecimal) r.get("tax"), cur), (String) r.get("reason_code"), (String) r.get("customer_reason_text"),
                    (String) r.get("condition"), (String) r.get("disposition"), Json.readMap((String) r.get("attributes_json")));
            }).toList();
        return new ReturnRma(rmaId, (String) h.get("order_id"), (String) h.get("channel"), (String) h.get("customer_ref"), (String) h.get("customer_email_token"), (String) h.get("status"),
            (String) h.get("reason_code"), (String) h.get("customer_reason_text"), (String) h.get("condition"), (String) h.get("refund_timing"), (Double) h.get("risk_score"), (String) h.get("risk_level"),
            h.get("reason_codes_json") == null ? List.of() : Json.read((String) h.get("reason_codes_json"), REASON_CODES),
            (String) h.get("risk_decision_record_id"), (String) h.get("refund_timing_decision_record_id"), (String) h.get("reason_decision_record_id"), (String) h.get("disposition"),
            (String) h.get("disposition_decision_record_id"), h.get("eligibility_json") == null ? null : Json.read((String) h.get("eligibility_json"), Eligibility.class),
            h.get("keep_it_json") == null ? null : Json.read((String) h.get("keep_it_json"), KeepIt.class), h.get("refund_json") == null ? null : Json.read((String) h.get("refund_json"), RefundInfo.class),
            Boolean.TRUE.equals(h.get("receipt_present")), (String) h.get("store_node_id"), (String) h.get("agent_id"), (String) h.get("mandate_id"), (String) h.get("review_task_id"),
            (String) h.get("review_task_type"), ts(h.get("scanned_at")), ts(h.get("received_at")), (String) h.get("received_node_id"), ts(h.get("inspected_at")), (String) h.get("inspection_note"),
            ts(h.get("refunded_at")), (String) h.get("created_by"), ts(h.get("created_at")), ts(h.get("updated_at")), lines);
    }

    private static Timestamp ts(Instant i) { return i == null ? null : Timestamp.from(i); }
    private static Instant ts(Object o) { return o == null ? null : ((Timestamp) o).toInstant(); }
}
