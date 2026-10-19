package ai.intentoms.copilot.learning;

import ai.intentoms.learning.model.PolicyChange;
import ai.intentoms.learning.model.PolicyChangeset;
import ai.intentoms.learning.model.Proposal;
import ai.intentoms.learning.model.Review;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The rule-based Critic used when no copilot model key is configured: the same kind of evidence the critic model gives a
 * reviewer, from fixed checks on the changeset and its evaluation report. Its review is stored with source
 * {@value Review#RULES}; like the model's, it never approves, rejects or blocks on its own.
 * <ul>
 *   <li>RISKY: the replay estimates the change makes things worse; an autonomous allow-list would gain refund or cancel;
 *       a new rule blocks orders on a T2 or T3 decision.</li>
 *   <li>NEEDS_CHANGES: no counterfactual estimate; a confidence interval that includes no effect; fewer than
 *       {@value #MIN_SAMPLE} cases behind the estimate; a threshold moving more than {@value #MAX_STEP}; a rule that
 *       matches every case; retiring a rule.</li>
 *   <li>Otherwise LOOKS_SAFE, with a note when the tier needs a second approver.</li>
 * </ul>
 */
@Component
public class TemplateCritic {
    static final int MIN_SAMPLE = 30;
    static final double MAX_STEP = 0.05;

    public Review review(Proposal p) {
        List<String> risky = new ArrayList<>();
        List<String> needsChanges = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        Map<String, Object> report = p.evaluationReport() == null ? Map.of() : p.evaluationReport();

        String method = report.get("method") == null ? "" : String.valueOf(report.get("method")).toLowerCase(Locale.ROOT);
        Double lift = number(report.get("liftEstimate"));
        Double lower = report.get("confidenceInterval") instanceof Map<?, ?> ci ? number(ci.get("lower")) : null;
        Double sample = number(report.get("sampleSize"));
        if (method.contains("no_counterfactual") || method.contains("insufficient") || lift == null) {
            needsChanges.add("No counterfactual estimate: the replay could not say what this change would have done");
        } else {
            if (lift < 0) risky.add(String.format(Locale.ROOT, "The replay estimates the change makes things worse (lift %.3f)", lift));
            if (lower != null && lower <= 0 && lift >= 0) needsChanges.add(String.format(Locale.ROOT, "The confidence interval includes no effect (lower bound %.3f)", lower));
        }
        if (sample != null && sample < MIN_SAMPLE) needsChanges.add(String.format(Locale.ROOT, "Only %.0f cases behind the estimate (fewer than %d)", sample, MIN_SAMPLE));

        for (PolicyChange c : p.changeset().changes()) {
            switch (c) {
                case PolicyChange.ThresholdChange tc -> {
                    if (Math.abs(tc.to() - tc.from()) > MAX_STEP + 1e-9)
                        needsChanges.add(String.format(Locale.ROOT, "Threshold %s moves %.3f, more than %.2f in one step", tc.band(), Math.abs(tc.to() - tc.from()), MAX_STEP));
                }
                case PolicyChange.RuleAdd ra -> {
                    String when = ra.expression() == null ? "" : ra.expression().trim();
                    if (when.isEmpty() || "true".equals(when)) needsChanges.add("The new rule matches every case");
                    if ("block".equalsIgnoreCase(ra.thenValue()) && (p.tier() == PolicyChangeset.RiskTier.T2 || p.tier() == PolicyChangeset.RiskTier.T3))
                        risky.add("The new rule blocks orders on a " + p.tier() + " decision");
                }
                case PolicyChange.RuleRetire rr -> needsChanges.add("Retiring rule " + rr.ruleId() + " removes a guardrail; check the replay covers the cases it decided");
                case PolicyChange.AllowListChange al -> {
                    if (al.add() && List.of("refund", "cancel").contains(al.entry().toLowerCase(Locale.ROOT)))
                        risky.add("Adds " + al.entry() + " to an autonomous allow-list");
                }
                default -> { }
            }
        }
        if (p.tier() == PolicyChangeset.RiskTier.T2 || p.tier() == PolicyChangeset.RiskTier.T3) notes.add("Tier " + p.tier() + ": needs an approver other than its author");

        List<String> risks = new ArrayList<>(risky);
        risks.addAll(needsChanges);
        risks.addAll(notes);
        Review.Verdict verdict = !risky.isEmpty() ? Review.Verdict.RISKY : !needsChanges.isEmpty() ? Review.Verdict.NEEDS_CHANGES : Review.Verdict.LOOKS_SAFE;
        return new Review(verdict, risks, Review.RULES);
    }

    private static Double number(Object o) {
        if (o instanceof Number n) return n.doubleValue();
        try { return o == null ? null : Double.parseDouble(String.valueOf(o)); } catch (NumberFormatException e) { return null; }
    }
}
