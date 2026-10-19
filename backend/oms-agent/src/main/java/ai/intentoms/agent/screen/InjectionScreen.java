package ai.intentoms.agent.screen;

import ai.intentoms.agent.config.AgentConfigKeys;
import ai.intentoms.decision.model.Answer;
import ai.intentoms.decision.model.DecisionOptions;
import ai.intentoms.decision.model.DecisionResult;
import ai.intentoms.decision.model.Layer;
import ai.intentoms.decision.runtime.DecisionService;
import ai.intentoms.platform.config.TenantConfigStore;
import ai.intentoms.platform.ledger.Actor;
import ai.intentoms.platform.observability.Metrics;
import ai.intentoms.platform.security.AuthorizationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Prompt-injection screen for agent- and customer-supplied text (GR-08, FR-AGT-05): runs the T0 Noul decision
 * {@code injection_screen} with {@code {text: {source, content}}} and flags the text when the decision acts on {@code true}
 * or the probability reaches tenant_config {@code agent.injection_threshold} (default 0.7). When the decision fabric
 * returns its L0 default (provider down, budget exceeded, kill switch) a deterministic phrase list decides instead, so
 * the screen never silently passes. Flagged text is kept as data with {@code injectionRisk}; callers place the
 * {@code agent_verification} hold and never merge the text into a prompt.
 */
@Service
public class InjectionScreen {
    public static final String DECISION = "injection_screen";
    public static final double DEFAULT_THRESHOLD = 0.7;
    private static final Logger log = LoggerFactory.getLogger(InjectionScreen.class);
    private static final List<Pattern> HEURISTICS = List.of(
        Pattern.compile("ignore\\s+(all\\s+)?(the\\s+)?(previous|prior|above|earlier)\\s+(instructions|prompts|rules)"),
        Pattern.compile("system\\s+prompt"),
        Pattern.compile("you\\s+are\\s+now\\b"),
        Pattern.compile("\\bdisregard\\b"),
        Pattern.compile("override\\s+(the\\s+)?(policy|policies|rules|limits?)"),
        Pattern.compile("refund\\s+(all|every|everything)"),
        Pattern.compile("\\bas\\s+an\\s+ai\\b"),
        Pattern.compile("new\\s+instructions?\\s*:"),
        Pattern.compile("developer\\s+mode"),
        Pattern.compile("\\bjailbreak\\b"),
        Pattern.compile("do\\s+not\\s+follow\\s+(the\\s+)?(policy|rules|instructions)"),
        Pattern.compile("forget\\s+(your|all)\\s+(instructions|rules|training)")
    );

    private final DecisionService decisions;
    private final TenantConfigStore config;
    private final AuthorizationService authz;
    private final Metrics metrics;

    public InjectionScreen(DecisionService decisions, TenantConfigStore config, AuthorizationService authz, Metrics metrics) {
        this.decisions = decisions;
        this.config = config;
        this.authz = authz;
        this.metrics = metrics;
    }

    /** Screens text without a subject (mandates, ad-hoc checks). */
    public ScreenResult screen(String source, String text) { return screen(source, text, "agent_text", source); }

    /** Screens text attached to a subject (order, return) so the decision record joins that subject's trail. */
    public ScreenResult screen(String source, String text, String subjectType, String subjectId) {
        // NF-07: contact details never reach the decision record or a model prompt; redaction keeps the instruction text.
        String content = ai.intentoms.platform.pii.FreeTextRedactor.redact(AgentTextSanitizer.sanitize(text));
        if (content == null || content.isBlank()) return ScreenResult.clean(source);
        Actor actor = authz.currentPrincipal().map(p -> Actor.parse(p.actorRef())).orElse(Actor.system("injection-screen"));
        DecisionResult r = decisions.decide(DECISION, Map.of("text", Map.of("source", source, "content", content)), DecisionOptions.live(subjectType, subjectId, actor));
        double threshold = config.getDouble(AgentConfigKeys.INJECTION_THRESHOLD, DEFAULT_THRESHOLD);
        ScreenResult result;
        if (r.layer() == Layer.DEFAULT || r.layer() == null) {
            boolean hit = heuristicMatch(content);
            result = new ScreenResult(source, hit ? 0.95 : 0.05, hit, r.recordId(), String.valueOf(r.layer()), true);
            log.debug("injection_screen fell back to the L0 default; heuristic screen decided {} for source {}", hit, source);
        } else {
            double p = probability(r);
            boolean positive = Boolean.TRUE.equals(r.value()) || (r.value() instanceof Number n && n.doubleValue() >= 0.5);
            boolean flagged = (positive && r.acts()) || p >= threshold;
            result = new ScreenResult(source, p, flagged, r.recordId(), r.layer().name(), false);
        }
        metrics.count("oms.agent.injection_screen", "source", source, "flagged", String.valueOf(result.flagged()), "heuristic", String.valueOf(result.heuristic()));
        if (result.flagged()) log.info("Injection screen flagged text from {} (p={}, record {})", source, result.probability(), result.decisionRecordId());
        return result;
    }

    /** Deterministic phrase screen used when the decision fabric is degraded; public for tests and L0 rules. */
    public static boolean heuristicMatch(String text) {
        if (text == null) return false;
        String t = text.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
        for (Pattern p : HEURISTICS) if (p.matcher(t).find()) return true;
        return false;
    }

    private static double probability(DecisionResult r) {
        Answer a = r.answers().get("injected");
        if (a == null && !r.answers().isEmpty()) a = r.answers().values().iterator().next();
        if (a != null) {
            if (a.value() instanceof Number n) return clamp(n.doubleValue());
            Double pt = a.probabilities().get("true");
            if (pt != null) return clamp(pt);
            if (a.value() instanceof Boolean b) return b ? 1.0 : 0.0;
        }
        if (r.value() instanceof Boolean b) return b ? clamp(r.confidence()) : clamp(1 - r.confidence());
        if (r.value() instanceof Number n) return clamp(n.doubleValue());
        return 0.0;
    }

    private static double clamp(double v) { return Math.max(0.0, Math.min(1.0, v)); }
}
