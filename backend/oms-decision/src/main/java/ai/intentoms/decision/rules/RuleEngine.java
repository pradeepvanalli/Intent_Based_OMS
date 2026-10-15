package ai.intentoms.decision.rules;

import ai.intentoms.decision.model.RuleDef;
import ai.intentoms.platform.cel.CelEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * L0 rules (FR-DEC-02, FR-DEC-03): rules are evaluated in priority order (lowest first, then declaration order) and the
 * first whose {@code when} is true wins. A rule whose expression fails to evaluate is skipped and logged, never treated
 * as a match, so a broken rule cannot silently block or release.
 */
@Component
public class RuleEngine {
    private static final Logger log = LoggerFactory.getLogger(RuleEngine.class);
    private final CelEngine cel;

    public RuleEngine(CelEngine cel) { this.cel = cel; }

    /** Outcome of a rule evaluation: the matched rule plus any rules that errored (for the trace). */
    public record Match(RuleDef rule, List<String> errored) {}

    public Optional<Match> firstMatch(List<RuleDef> rules, Map<String, Object> vars) {
        if (rules == null || rules.isEmpty()) return Optional.empty();
        List<String> errored = new java.util.ArrayList<>();
        for (RuleDef r : rules) {
            try {
                if (cel.evalBool(r.when(), vars)) return Optional.of(new Match(r, errored));
            } catch (RuntimeException e) {
                log.warn("L0 rule {} failed to evaluate and was skipped: {}", r.id(), e.getMessage());
                errored.add(r.id());
            }
        }
        return Optional.empty();
    }
}
