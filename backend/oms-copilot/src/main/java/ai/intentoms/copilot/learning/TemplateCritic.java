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
 *   <li>RISKY: the replay estimates the change makes things worse; the plain inverse-propensity cross-check says it is
 *       significantly worse (its whole 95% interval below zero, XP-02 / EX-01) even when the doubly-robust headline looks
 *       good; labelled fraud history shows a higher expected cost (FD-04); an autonomous allow-list would gain refund or
 *       cancel; a new rule blocks orders on a T2 or T3 decision.</li>
 *   <li>NEEDS_CHANGES: no counterfactual estimate; a confidence interval that includes no effect; fewer than
 *       {@value #MIN_SAMPLE} cases behind the estimate; less than {@value #MIN_MATURE_SHARE} of the rewards behind it
 *       final (the rest are provisional and may still move when late outcomes land, OC-04 / §11 delayed-label bias); a
 *       threshold moving more than {@value #MAX_STEP}; a rule that matches every case; retiring a rule.</li>
 *   <li>Otherwise LOOKS_SAFE, with a note when the tier needs a second approver.</li>
 * </ul>
 */
@Component
public class TemplateCritic {
    static final int MIN_SAMPLE = 30;
    static final double MAX_STEP = 0.05;
    /** Below this share of mature (final) rewards the estimate rests mostly on provisional outcomes. */
    static final double MIN_MATURE_SHARE = 0.5;

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
        // XP-02 / EX-01: the inverse-propensity estimate is the cross-check of the doubly-robust headline. When its whole
        // interval is below zero the change looks worse on the records it actually reproduces, whatever the headline says.
        if (report.get("ips") instanceof Map<?, ?> ips && ips.get("confidenceInterval") instanceof Map<?, ?> ipsCi) {
            Double ipsUpper = number(ipsCi.get("upper"));
            Double ipsLower = number(ipsCi.get("lower"));
            Double ipsLift = number(ips.get("liftEstimate"));
            if (ipsUpper != null && ipsUpper < 0)
                risky.add(String.format(Locale.ROOT, "The inverse-propensity cross-check says the change makes outcomes worse (lift %.3f, 95%% interval %.3f to %.3f)",
                    ipsLift == null ? 0.0 : ipsLift, ipsLower == null ? 0.0 : ipsLower, ipsUpper));
        }
        // OC-04: an estimate on mostly provisional rewards can still move when returns and chargebacks arrive.
        if (report.get("rewards") instanceof Map<?, ?> rewards) {
            Double matureShare = number(rewards.get("matureShare"));
            if (matureShare != null && matureShare < MIN_MATURE_SHARE)
                needsChanges.add(String.format(Locale.ROOT, "Only %.0f%% of the rewards behind the estimate are final (below %.0f%%); the rest may still change as late outcomes land",
                    matureShare * 100, MIN_MATURE_SHARE * 100));
        }
        // FD-04: a fraud change is also judged on its labelled fraud history.
        if (report.get("fraudLabels") instanceof Map<?, ?> fl && Boolean.TRUE.equals(fl.get("sufficient"))) {
            Double costChange = number(fl.get("expectedCostChange"));
            Double fpChange = number(fl.get("falsePositiveRateChange"));
            if (costChange != null && costChange > 0) risky.add(String.format(Locale.ROOT, "On labelled fraud history the change raises the expected cost by %.2f", costChange));
            if (fpChange != null && fpChange > 0.02) needsChanges.add(String.format(Locale.ROOT, "On labelled fraud history it blocks %.1f%% more good orders", fpChange * 100));
        }

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
