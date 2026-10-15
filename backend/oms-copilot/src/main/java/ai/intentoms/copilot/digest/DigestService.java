package ai.intentoms.copilot.digest;

import ai.intentoms.copilot.config.PromptTemplates;
import ai.intentoms.copilot.llm.CopilotLlmClient;
import ai.intentoms.copilot.llm.Grounding;
import ai.intentoms.copilot.llm.JsonSchema;
import ai.intentoms.platform.audit.AuditLog;
import ai.intentoms.platform.error.OmsException;
import ai.intentoms.platform.error.ValidationException;
import ai.intentoms.platform.ids.Ids;
import ai.intentoms.platform.security.AuthorizationService;
import ai.intentoms.platform.time.TimeSource;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Margin Guardian digest: deterministic facts over the period ({@link DigestFactsRepository}), 3–6 findings written by
 * the model under a strict schema with a numeric grounding post-check (every number in title/metric/value/delta/evidence
 * must appear in the facts; the {@code suggestedPrompt} may introduce the proposed new target), or a deterministic
 * fallback when no key is configured. Findings end with a prompt the user can paste into the configuration copilot.
 */
@Service
public class DigestService {
    private static final Logger log = LoggerFactory.getLogger(DigestService.class);
    private static final JsonSchema SCHEMA = JsonSchema.named("margin_digest", JsonSchema.object(
        "headline", JsonSchema.string("One sentence headline"),
        "findings", JsonSchema.array("3 to 6 findings", JsonSchema.object(
            "title", JsonSchema.string("Short title"),
            "metric", JsonSchema.string("Metric name, e.g. split_rate_pct"),
            "value", JsonSchema.string("Current value with unit, copied from the facts"),
            "delta", JsonSchema.string("Change versus the previous period, or 'n/a'"),
            "evidence", JsonSchema.string("One or two sentences citing fact values"),
            "suggestedPrompt", JsonSchema.string("A concrete configuration copilot prompt with a number")))));

    private final DigestFactsRepository facts;
    private final DigestRepository repo;
    private final CopilotLlmClient llm;
    private final PromptTemplates templates;
    private final TimeSource time;
    private final AuthorizationService authz;
    private final AuditLog audit;

    public DigestService(DigestFactsRepository facts, DigestRepository repo, CopilotLlmClient llm, PromptTemplates templates, TimeSource time, AuthorizationService authz, AuditLog audit) {
        this.facts = facts;
        this.repo = repo;
        this.llm = llm;
        this.templates = templates;
        this.time = time;
        this.authz = authz;
        this.audit = audit;
    }

    public Digest generate(int days) {
        if (days < 1 || days > 90) throw new ValidationException("days must be 1..90");
        Instant to = time.now();
        Instant from = to.minus(Duration.ofDays(days));
        Map<String, Object> f = facts.facts(from, to);
        Map<String, Object> period = new LinkedHashMap<>();
        period.put("days", days);
        period.put("from", from.toString());
        period.put("to", to.toString());
        String id = Ids.prefixed("dig");
        if (llm.available()) {
            try {
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("periodDays", days);
                payload.put("facts", f);
                JsonNode out = llm.complete("digest", templates.get("system-digest.md"), payload, SCHEMA);
                List<Digest.Finding> findings = new ArrayList<>();
                List<String> rejected = new ArrayList<>();
                for (JsonNode n : out.path("findings")) {
                    Digest.Finding fd = new Digest.Finding(n.path("title").asText(), n.path("metric").asText(), n.path("value").asText(), n.path("delta").asText(), n.path("evidence").asText(), n.path("suggestedPrompt").asText());
                    List<String> ungrounded = Grounding.ungroundedNumbers(String.join(" ", fd.title(), fd.metric(), fd.value(), fd.delta(), fd.evidence()), f);
                    if (ungrounded.isEmpty()) findings.add(fd); else rejected.add(fd.title() + " -> " + ungrounded);
                }
                if (findings.size() >= 1) {
                    if (!rejected.isEmpty()) log.warn("digest dropped {} finding(s) with numbers absent from the facts: {}", rejected.size(), rejected);
                    return new Digest(id, period, out.path("headline").asText(), findings, true, llm.model(), f, to, rejected.isEmpty() ? null : "dropped findings with ungrounded numbers: " + rejected);
                }
                return new Digest(id, period, deterministicHeadline(f), deterministicFindings(f), false, null, f, to, "every generated finding contained numbers absent from the facts");
            } catch (OmsException e) {
                log.warn("digest fell back to the deterministic findings: {}", e.getMessage());
                return new Digest(id, period, deterministicHeadline(f), deterministicFindings(f), false, null, f, to, e.getMessage());
            }
        }
        return new Digest(id, period, deterministicHeadline(f), deterministicFindings(f), false, null, f, to, "no model key configured");
    }

    /** Generates and stores the digest so the UI can show the latest. */
    public Digest run(int days) {
        Digest d = generate(days);
        repo.insert(d, days, authz.actorRef());
        audit.record("copilot.digest_run", "copilot_digest", d.id(), "margin guardian digest over " + days + " days", Map.of("findings", d.findings().size(), "generated", d.generated()));
        return d;
    }

    public Optional<Digest> latest() { return repo.latest(); }
    public List<Digest> history(int limit) { return repo.list(limit); }

    // ------------------------------------------------------------------ deterministic fallback

    @SuppressWarnings("unchecked")
    static String deterministicHeadline(Map<String, Object> f) {
        Map<String, Object> orders = (Map<String, Object>) f.get("orders");
        Map<String, Object> splits = (Map<String, Object>) f.get("splits");
        return orders.get("orders") + " orders, split rate " + splits.get("split_rate_pct") + "% costing about $" + splits.get("estimated_split_cost_usd") + " in extra packages, " + ((Map<String, Object>) f.get("belowFloor")).get("below_floor_orders") + " below the margin floor.";
    }

    @SuppressWarnings("unchecked")
    static List<Digest.Finding> deterministicFindings(Map<String, Object> f) {
        List<Digest.Finding> out = new ArrayList<>();
        Map<String, Object> splits = (Map<String, Object>) f.get("splits");
        Map<String, Object> prevSplits = (Map<String, Object>) f.get("previousSplits");
        out.add(new Digest.Finding("Split shipments", "split_rate_pct", splits.get("split_rate_pct") + "%", "previous period " + prevSplits.get("split_rate_pct") + "%",
            splits.get("split_orders") + " of " + splits.get("sourced_orders") + " sourced orders shipped in more than one package; " + splits.get("extra_packages") + " extra packages at $" + f.get("splitPenaltyUsd") + " each ≈ $" + splits.get("estimated_split_cost_usd") + ".",
            "Raise the split penalty to $" + (((Number) f.get("splitPenaltyUsd")).doubleValue() + 3) + " and cap marketplace orders at 2 packages"));
        Map<String, Object> bf = (Map<String, Object>) f.get("belowFloor");
        out.add(new Digest.Finding("Margin floor approvals", "below_floor_orders", String.valueOf(bf.get("below_floor_orders")), "n/a",
            bf.get("approved") + " below-floor orders were approved; margin given up ≈ $" + bf.get("margin_given_up_usd") + " on $" + bf.get("revenue") + " revenue against a floor of " + f.get("marginFloor") + ".",
            "Require two approvers for plans more than 5 points below the " + f.get("marginFloor") + " margin floor"));
        List<Map<String, Object>> returns = (List<Map<String, Object>>) f.get("returnsByTiming");
        if (!returns.isEmpty()) {
            Map<String, Object> top = returns.get(0);
            out.add(new Digest.Finding("Returns by refund timing", "refund_timing", top.get("refund_timing") + ": " + top.get("returns") + " returns", "n/a",
                "Refund value $" + top.get("refund_value_usd") + " with average risk score " + top.get("avg_risk_score") + " for timing '" + top.get("refund_timing") + "'.",
                "Give instant refunds only to customers with a receipt and at most 1 return in 12 months under $250"));
        }
        List<Map<String, Object>> exc = (List<Map<String, Object>>) f.get("topExceptions");
        if (!exc.isEmpty()) {
            Map<String, Object> top = exc.get(0);
            out.add(new Digest.Finding("Top exception type", "exceptions", top.get("type") + ": " + top.get("exceptions"), "n/a", top.get("open") + " of " + top.get("exceptions") + " '" + top.get("type") + "' exceptions are still open.",
                "Add a playbook step for " + top.get("type") + " exceptions that re-sources after 60 minutes"));
        }
        List<Map<String, Object>> decs = (List<Map<String, Object>>) f.get("decisions");
        if (!decs.isEmpty()) {
            Map<String, Object> top = decs.get(0);
            out.add(new Digest.Finding("Decision escalations", "escalation_rate_pct", top.get("decision") + ": " + top.get("escalation_rate_pct") + "%", "n/a",
                top.get("escalations") + " of " + top.get("volume") + " " + top.get("decision") + " decisions escalated; override rate " + top.get("override_rate_pct") + "%.",
                "Add an L0 fast-lane rule to " + top.get("decision") + " for the clear-cut cases so fewer than 10% escalate"));
        }
        Map<String, Object> ai = (Map<String, Object>) f.get("aiCost");
        out.add(new Digest.Finding("AI cost", "total_usd", "$" + ai.get("total_usd"), "n/a", "Decisions $" + ai.get("decisions_usd") + ", copilot $" + ai.get("copilot_usd") + "; daily budget " + ai.get("budget_usd_per_day") + ".",
            "Set the AI budget to $25 per day and cache fulfilment_intent for 30 minutes"));
        return out.size() > 6 ? out.subList(0, 6) : out;
    }
}
