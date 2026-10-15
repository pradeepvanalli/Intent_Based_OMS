package ai.intentoms.copilot.ops;

import ai.intentoms.copilot.config.PromptTemplates;
import ai.intentoms.copilot.llm.CopilotLlmClient;
import ai.intentoms.copilot.llm.JsonSchema;
import ai.intentoms.copilot.llm.Untrusted;
import ai.intentoms.platform.error.OmsException;
import ai.intentoms.platform.error.ValidationException;
import ai.intentoms.platform.time.TimeSource;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Ops copilot (FR-MON-08): "ask the OMS anything about live orders". The model never writes SQL; it selects one of the
 * whitelisted {@link OpsQueryCatalog} templates and typed parameters (GR-04), the catalogue binds them with named
 * parameters, the query runs tenant-scoped, and a second model call (or a deterministic template) writes the answer
 * from the rows framed as data (GR-08). The SQL that ran is returned so the user can see it.
 */
@Service
public class OpsCopilotService {
    private static final Logger log = LoggerFactory.getLogger(OpsCopilotService.class);
    private final OpsQueryCatalog catalog;
    private final CopilotLlmClient llm;
    private final PromptTemplates templates;
    private final JdbcClient jdbc;
    private final TimeSource time;
    private final JsonSchema selectionSchema;
    private static final JsonSchema ANSWER = JsonSchema.named("ops_answer", JsonSchema.object("answer", JsonSchema.string("One short paragraph answering the question from the rows")));

    public OpsCopilotService(OpsQueryCatalog catalog, CopilotLlmClient llm, PromptTemplates templates, JdbcClient jdbc, TimeSource time) {
        this.catalog = catalog;
        this.llm = llm;
        this.templates = templates;
        this.jdbc = jdbc;
        this.time = time;
        this.selectionSchema = selectionSchema(catalog.names());
    }

    /** Strict schema the model must answer with: one template name (or none) plus every typed parameter, null when unused. */
    static JsonSchema selectionSchema(List<String> templateNames) {
        List<String> names = new ArrayList<>(templateNames);
        names.add("none");
        return JsonSchema.named("ops_query_selection", JsonSchema.object(
            "template", JsonSchema.enumOf("The template to run, or none", names),
            "parameters", JsonSchema.object(
                "channel", JsonSchema.nullableString("Sales channel or null"),
                "orderType", JsonSchema.nullableString("Order type name or null"),
                "nodeId", JsonSchema.nullableString("Node id or null"),
                "state", JsonSchema.nullableString("Order status id or null"),
                "destState", JsonSchema.nullableString("Two-letter US state code or null"),
                "hours", JsonSchema.nullableInteger("Look-back or idle window in hours, or null", 1, OpsQueryCatalog.MAX_HOURS),
                "limit", JsonSchema.nullableInteger("Maximum rows or null", 1, OpsQueryCatalog.MAX_LIMIT),
                "sku", JsonSchema.nullableString("SKU or null"),
                "customerRef", JsonSchema.nullableString("Customer reference or null"),
                "orderId", JsonSchema.nullableString("Order id or null"),
                "exceptionType", JsonSchema.nullableString("Exception type or null"),
                "decisionName", JsonSchema.nullableString("Decision name or null"),
                "threshold", JsonSchema.nullableInteger("Numeric threshold or null", 0, 1_000_000)),
            "reason", JsonSchema.string("Why this template answers the question, or why none fits")));
    }

    public List<Map<String, Object>> templates() { return catalog.all().stream().map(OpsQueryTemplate::describe).toList(); }

    public OpsAnswer ask(String question) {
        if (question == null || question.isBlank()) throw new ValidationException("Ask a question about live orders");
        BoundQuery bound;
        String reason;
        boolean generated = false;
        if (llm.available()) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("question", Untrusted.text("ops user", question));
            payload.put("templates", templates());
            payload.put("vocabulary", vocabulary());
            JsonNode sel = llm.complete("ops_select", templates.get("system-ops.md"), payload, selectionSchema);
            String template = sel.path("template").asText();
            reason = sel.path("reason").asText("");
            if ("none".equals(template)) throw new ValidationException("No whitelisted query answers this question: " + reason, Map.of("templates", catalog.names()));
            Map<String, Object> params = new LinkedHashMap<>();
            sel.path("parameters").fields().forEachRemaining(e -> { if (!e.getValue().isNull()) params.put(e.getKey(), e.getValue().isNumber() ? e.getValue().numberValue() : e.getValue().asText()); });
            bound = catalog.bind(template, params);
            generated = true;
        } else {
            bound = catalog.match(question);
            reason = "keyword match (no model key configured)";
        }
        List<Map<String, Object>> rows = run(bound);
        Map<String, Object> shownParams = new LinkedHashMap<>(bound.parameters());
        shownParams.remove("tenant");
        String answer = generated ? answerWithModel(question, bound, shownParams, rows) : deterministicAnswer(bound, shownParams, rows);
        return new OpsAnswer(answer, bound.template().name(), shownParams, bound.sql(), rows, rows.size(), time.now(), generated, generated ? llm.model() : null, reason);
    }

    List<Map<String, Object>> run(BoundQuery q) {
        var spec = jdbc.sql(q.sql());
        for (var e : q.parameters().entrySet()) spec = spec.param(e.getKey(), e.getValue());
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> r : spec.query().listOfRows()) {
            Map<String, Object> clean = new LinkedHashMap<>();
            r.forEach((k, v) -> clean.put(k, v instanceof Timestamp ts ? ts.toInstant().toString() : v instanceof java.sql.Date d ? d.toLocalDate().toString() : v));
            rows.add(clean);
        }
        return rows;
    }

    private String answerWithModel(String question, BoundQuery q, Map<String, Object> params, List<Map<String, Object>> rows) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("question", Untrusted.text("ops user", question));
            payload.put("template", q.template().name());
            payload.put("templateDescription", q.template().description());
            payload.put("parameters", params);
            payload.put("rowCount", rows.size());
            payload.put("rows", rows.size() > 60 ? rows.subList(0, 60) : rows);
            return llm.complete("ops_answer", templates.get("system-ops-answer.md"), payload, ANSWER).path("answer").asText();
        } catch (OmsException e) {
            log.warn("ops answer fell back to the deterministic template: {}", e.getMessage());
            return deterministicAnswer(q, params, rows);
        }
    }

    static String deterministicAnswer(BoundQuery q, Map<String, Object> params, List<Map<String, Object>> rows) {
        StringBuilder sb = new StringBuilder();
        sb.append(q.template().description()).append(": ");
        if (rows.isEmpty()) sb.append("no matching rows");
        else {
            sb.append(rows.size()).append(" row").append(rows.size() == 1 ? "" : "s");
            Map<String, Object> first = rows.get(0);
            if (rows.size() <= 8 && first.size() <= 6) {
                sb.append(" — ");
                sb.append(String.join("; ", rows.stream().map(r -> r.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue()).reduce((a, b) -> a + ", " + b).orElse("")).toList()));
            } else {
                Object total = rows.stream().map(r -> r.get("orders") != null ? r.get("orders") : r.get("total") != null ? r.get("total") : r.get("returns")).filter(v -> v instanceof Number).mapToLong(v -> ((Number) v).longValue()).sum();
                if (((Number) total).longValue() > 0) sb.append(" totalling ").append(total);
            }
        }
        if (!params.isEmpty()) sb.append(" (parameters ").append(params).append(")");
        sb.append(". Query template: ").append(q.template().name()).append('.');
        return sb.toString();
    }

    private Map<String, Object> vocabulary() {
        Map<String, Object> v = new LinkedHashMap<>();
        String t = ai.intentoms.platform.tenant.TenantContext.require();
        v.put("channels", jdbc.sql("select distinct channel from orders where tenant_id = :t order by channel").param("t", t).query(String.class).list());
        v.put("orderTypes", jdbc.sql("select distinct order_type from orders where tenant_id = :t order by order_type").param("t", t).query(String.class).list());
        v.put("statuses", jdbc.sql("select distinct status from orders where tenant_id = :t order by status").param("t", t).query(String.class).list());
        v.put("nodes", jdbc.sql("select distinct primary_node_id from orders where tenant_id = :t and primary_node_id is not null order by 1").param("t", t).query(String.class).list());
        v.put("exceptionTypes", jdbc.sql("select distinct type from exception where tenant_id = :t order by type").param("t", t).query(String.class).list());
        v.put("decisions", jdbc.sql("select distinct decision from decision_record where tenant_id = :t order by decision").param("t", t).query(String.class).list());
        return v;
    }
}
