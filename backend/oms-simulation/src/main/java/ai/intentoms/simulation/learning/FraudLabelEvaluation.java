package ai.intentoms.simulation.learning;

import ai.intentoms.decision.model.Band;
import ai.intentoms.decision.model.DecisionRecord;
import ai.intentoms.decision.model.Layer;
import ai.intentoms.decision.record.DecisionRecordRepository;
import ai.intentoms.learning.learner.FraudLabels;
import ai.intentoms.platform.config.TenantConfigStore;
import ai.intentoms.platform.json.Json;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * FD-04: a change to a fraud decision (payment_risk, rto_risk, return_risk, address_risk), a new abuse rule above all,
 * is tested on the decision's labelled fraud history, not only on episode reward: which of the labelled orders the
 * current policy and the proposed one would block, and with it precision, recall, the false-positive rate, the fraud
 * loss let through and the cost of blocking good orders.
 *
 * <p>Only confirmed labels count, and goods and frauds come from the same window: decisions made between
 * {@code lookback} and the chargeback window ago ({@code learning.fraud.chargeback_window_days}, default 120), whose
 * chargeback window has closed, so a good order is as settled as a fraud one. Taking frauds of any age but goods only
 * after the window would make the sample fraud-heavy and the false-positive rate meaningless (audit C4).
 */
@Component
public class FraudLabelEvaluation {
    public static final Set<String> FRAUD_DECISIONS = Set.of("payment_risk", "rto_risk", "return_risk", "address_risk");
    /** Tenant config: what blocking one good order costs (default 25), the same weight the fraud threshold tuner uses. */
    public static final String BLOCK_COST_KEY = "learning.fraud.block_cost";

    private final DecisionRecordRepository records;
    private final TenantConfigStore config;

    @Value("${oms.learning.fraud-eval.lookback-days:365}") private int lookbackDays = 365;
    @Value("${oms.learning.fraud-eval.min-labelled:30}") private int minLabelled = 30;

    public FraudLabelEvaluation(DecisionRecordRepository records, TenantConfigStore config) {
        this.records = records;
        this.config = config;
    }

    /** What the logged decision did: blocked when it said "risky" and acted on it (or a rule decided). */
    static boolean loggedBlocks(DecisionRecord r) {
        return risky(r.value()) && (r.band() == Band.ACT || r.layer() == Layer.L0_RULE);
    }

    static boolean risky(Object value) {
        if (value instanceof Boolean b) return b;
        String s = value == null ? "" : String.valueOf(value).toLowerCase(Locale.ROOT);
        return s.equals("true") || s.equals("block") || s.equals("decline") || s.equals("hold") || s.equals("high");
    }

    /**
     * Compares the current and the proposed policy on the labelled history of {@code decision}. {@code proposedBlocks}
     * says whether the proposed policy would block a logged decision.
     */
    public Map<String, Object> evaluate(String decision, Predicate<DecisionRecord> proposedBlocks) {
        int window = Math.max(0, config.getInt(FraudLabels.WINDOW_KEY, FraudLabels.DEFAULT_WINDOW_DAYS));
        double blockCost = config.getDouble(BLOCK_COST_KEY, 25.0);
        Instant now = Instant.now();
        Instant until = now.minus(Duration.ofDays(window));
        Instant since = until.minus(Duration.ofDays(Math.max(1, lookbackDays)));
        Tally current = new Tally(), proposed = new Tally();
        int skipped = 0;
        for (int[] v : records.activeVersions(decision, since)) {
            for (DecisionRecord r : records.labelledBetween(decision, v[0], since, until)) {
                Boolean fraud = FraudLabels.usable(r, now, window);
                if (fraud == null) { skipped++; continue; }
                double amount = amount(r);
                current.add(fraud, loggedBlocks(r), amount, blockCost);
                proposed.add(fraud, proposedBlocks.test(r), amount, blockCost);
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("method", "fraud_label_replay");
        out.put("decision", decision);
        out.put("window", Map.of("from", since.toString(), "to", until.toString(), "chargebackWindowDays", window,
            "rule", "goods and frauds decided in the same period, after their chargeback window closed; confirmed labels only"));
        out.put("labelled", current.n());
        out.put("frauds", current.frauds);
        out.put("goods", current.goods);
        out.put("skippedUnconfirmed", skipped);
        out.put("blockCost", blockCost);
        if (current.frauds == 0 || current.goods == 0 || current.n() < minLabelled) {
            out.put("sufficient", false);
            out.put("reason", String.format(Locale.ROOT, "only %d confirmed labels (%d fraud, %d good) on %s decided between %s and %s; need %d with both kinds",
                current.n(), current.frauds, current.goods, decision, since.toString().substring(0, 10), until.toString().substring(0, 10), minLabelled));
            return out;
        }
        out.put("sufficient", true);
        out.put("current", current.view());
        out.put("proposed", proposed.view());
        double delta = proposed.cost() - current.cost();
        out.put("expectedCostChange", round(delta));
        out.put("falsePositiveRateChange", round(proposed.fpRate() - current.fpRate()));
        out.put("recallChange", round(proposed.recall() - current.recall()));
        out.put("verdict", delta < -1e-9 ? "lower_cost" : delta > 1e-9 ? "higher_cost" : "no_change");
        out.put("summary", String.format(Locale.ROOT, "On %d labelled orders (%d fraud): catches %d of %d frauds instead of %d, blocks %d good orders instead of %d; expected cost %.2f instead of %.2f",
            current.n(), current.frauds, proposed.tp, current.frauds, current.tp, proposed.fp, current.fp, proposed.cost(), current.cost()));
        return out;
    }

    private static double amount(DecisionRecord r) {
        Object total = r.input() == null ? null : Json.path(r.input(), "order.total");
        if (total == null && r.input() != null) total = r.input().get("order.total");
        if (total == null && r.input() != null) total = Json.path(r.input(), "order.value");
        return total instanceof Number n ? n.doubleValue() : 0.0;
    }

    static double round(double v) { return Math.round(v * 10000) / 10000.0; }

    /** Confusion counts and costs of one policy on the labelled orders. */
    static final class Tally {
        int tp, fp, fn, tn, frauds, goods;
        double lossLetThrough, blockingCost;

        void add(boolean fraud, boolean blocked, double amount, double blockCost) {
            if (fraud) { frauds++; if (blocked) tp++; else { fn++; lossLetThrough += amount; } }
            else { goods++; if (blocked) { fp++; blockingCost += blockCost; } else tn++; }
        }

        int n() { return frauds + goods; }
        double precision() { return tp + fp == 0 ? 0 : (double) tp / (tp + fp); }
        double recall() { return frauds == 0 ? 0 : (double) tp / frauds; }
        double fpRate() { return goods == 0 ? 0 : (double) fp / goods; }
        double cost() { return lossLetThrough + blockingCost; }

        Map<String, Object> view() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("blocked", tp + fp);
            m.put("fraudCaught", tp);
            m.put("fraudMissed", fn);
            m.put("goodBlocked", fp);
            m.put("precision", round(precision()));
            m.put("recall", round(recall()));
            m.put("falsePositiveRate", round(fpRate()));
            m.put("fraudLoss", round(lossLetThrough));
            m.put("blockingCost", round(blockingCost));
            m.put("expectedCost", round(cost()));
            return m;
        }
    }
}
