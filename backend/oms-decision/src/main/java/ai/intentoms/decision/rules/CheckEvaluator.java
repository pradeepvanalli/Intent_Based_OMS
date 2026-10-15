package ai.intentoms.decision.rules;

import ai.intentoms.decision.model.CheckResult;
import ai.intentoms.platform.cel.CelEngine;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Pre- and post-check evaluation (GR-01). Every expression is evaluated (not short-circuited) so the trace shows each
 * result; an expression that errors counts as failed because an unverifiable constraint must not let an answer through.
 */
@Component
public class CheckEvaluator {
    private final CelEngine cel;

    public CheckEvaluator(CelEngine cel) { this.cel = cel; }

    public List<CheckResult> evaluate(List<String> expressions, Map<String, Object> vars) {
        List<CheckResult> out = new ArrayList<>();
        if (expressions == null) return out;
        for (String expr : expressions) {
            try {
                out.add(new CheckResult(expr, cel.evalBool(expr, vars), null));
            } catch (RuntimeException e) {
                out.add(new CheckResult(expr, false, e.getMessage()));
            }
        }
        return out;
    }

    public static boolean allPassed(List<CheckResult> results) { return results.stream().allMatch(CheckResult::passed); }
}
