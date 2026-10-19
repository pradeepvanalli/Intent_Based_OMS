package ai.intentoms.agent.mcp;

import ai.intentoms.learning.event.LearningEvents;
import ai.intentoms.learning.model.Hypothesis;
import ai.intentoms.learning.model.PolicyChange;
import ai.intentoms.learning.model.Proposal;
import ai.intentoms.learning.store.HypothesisRepository;
import ai.intentoms.learning.store.ProposalRepository;
import ai.intentoms.platform.json.Json;
import ai.intentoms.platform.ledger.Event;
import ai.intentoms.platform.ledger.EventStore;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * {@code explain_policy_change} (requirements doc §9.4): a typed summary of one promoted or pending policy change —
 * each change with its from and to values and a plain sentence, the hypothesis behind it (who raised it, why, on what
 * evidence), the evaluation and shadow results, and its status history from the ledger, including any rollback.
 * Read-only.
 */
@Component
public class ExplainPolicyChangeTool implements McpTool {
    private final ProposalRepository proposals;
    private final HypothesisRepository hypotheses;
    private final EventStore events;

    public ExplainPolicyChangeTool(ProposalRepository proposals, HypothesisRepository hypotheses, EventStore events) {
        this.proposals = proposals;
        this.hypotheses = hypotheses;
        this.events = events;
    }

    @Override public String name() { return "explain_policy_change"; }
    @Override public String description() { return "Explain one SOO policy change: each change with its from/to values, the hypothesis behind it, the evidence and evaluation, and its status history including any rollback."; }
    @Override public boolean readOnly() { return true; }
    @Override public String orderIdOf(Map<String, Object> args) { return null; }

    @Override
    public Map<String, Object> inputSchema() {
        return Schemas.object(Schemas.props("proposalId", Schemas.string("The proposal id, e.g. prop_...")), McpTool.required("proposalId"));
    }

    @Override
    public Object call(ToolContext ctx, Map<String, Object> args) {
        Object id = args.get("proposalId");
        if (id == null || id.toString().isBlank()) throw new JsonRpc.RpcException(JsonRpc.INVALID_PARAMS, "proposalId is required");
        Proposal p = proposals.get(id.toString());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("proposalId", p.proposalId());
        out.put("status", p.status().name());
        out.put("target", p.changeset().target().toString());
        out.put("tier", p.tier().name());
        out.put("changes", p.changeset().changes().stream().map(ExplainPolicyChangeTool::change).toList());
        out.put("motivatedBy", p.changeset().motivatedBy());
        out.put("hypothesis", p.hypothesisId() == null ? null : hypotheses.find(p.hypothesisId()).map(this::hypothesis).orElse(null));
        out.put("evidence", evidence(p));
        out.put("criticReview", p.criticReview());
        out.put("evaluationReport", p.evaluationReport());
        out.put("createdBy", p.createdBy());
        out.put("approvedBy", p.approvedBy());
        out.put("promotedAt", p.promotedAt() == null ? null : p.promotedAt().toString());
        out.put("rolledBack", p.rolledBackAt() == null ? null : Map.of("at", p.rolledBackAt().toString(), "reason", String.valueOf(p.rolledBackReason())));
        out.put("statusHistory", history(p.proposalId()));
        return out;
    }

    /** The change's own typed fields (from, to, band, weight key…) plus a one-line description. */
    static Map<String, Object> change(PolicyChange c) {
        Map<String, Object> m = new LinkedHashMap<>(Json.readMap(Json.write(c)));
        m.putIfAbsent("type", c.getClass().getSimpleName());
        m.put("summary", switch (c) {
            case PolicyChange.ThresholdChange t -> String.format(Locale.ROOT, "%s: move the %s threshold from %.2f to %.2f", t.decision(), t.band(), t.from(), t.to());
            case PolicyChange.WeightChange w -> String.format(Locale.ROOT, "%s: move weight %s from %s to %s", w.target(), w.weightKey(), w.from(), w.to());
            case PolicyChange.RuleAdd r -> r.decision() + ": add rule \"" + r.expression() + "\" answering " + r.thenValue();
            case PolicyChange.RuleRetire r -> r.decision() + ": retire rule " + r.ruleId();
            case PolicyChange.ParameterChange pc -> "set " + pc.parameter() + " from " + pc.from() + " to " + pc.to();
            case PolicyChange.AllowListChange a -> (a.add() ? "add " : "remove ") + a.entry() + (a.add() ? " to " : " from ") + a.listName();
        });
        return m;
    }

    private Map<String, Object> hypothesis(Hypothesis h) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("hypothesisId", h.hypothesisId());
        m.put("targetMetric", h.targetMetric());
        m.put("direction", h.expectedDirection().name());
        m.put("rationale", h.rationale());
        m.put("priorConfidence", h.priorConfidence());
        m.put("evidence", h.evidence());
        m.put("raisedBy", hypotheses.raisedBy(h.hypothesisId()).orElse(null));
        m.put("status", h.status().name());
        return m;
    }

    /** What the change was judged on: the replay estimate and interval, the sample, and the shadow verdict if any. */
    private static Map<String, Object> evidence(Proposal p) {
        Map<String, Object> r = p.evaluationReport() == null ? Map.of() : p.evaluationReport();
        Map<String, Object> m = new LinkedHashMap<>();
        for (String k : List.of("method", "liftEstimate", "confidenceInterval", "sampleSize", "decisionsChanged", "replayed")) if (r.get(k) != null) m.put(k, r.get(k));
        if (r.get("shadow") != null) m.put("shadow", r.get("shadow"));
        return m;
    }

    /** Every governance step of the proposal from the ledger: drafted, evaluated, approved, promoted, rolled back… */
    private List<Map<String, Object>> history(String proposalId) {
        return events.load(LearningEvents.AGGREGATE, proposalId).stream().map(ExplainPolicyChangeTool::step).toList();
    }

    private static Map<String, Object> step(Event e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("event", e.type());
        m.put("at", e.occurredAt().toString());
        m.put("by", e.actor() == null ? null : e.actor().ref());
        Map<String, Object> payload = e.payload() == null ? Map.of() : e.payload();
        for (String k : List.of("reason", "reasonCode", "tier", "restored", "applied")) if (payload.get(k) != null) m.put(k, payload.get(k));
        return m;
    }
}
