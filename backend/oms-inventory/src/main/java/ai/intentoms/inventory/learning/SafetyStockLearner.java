package ai.intentoms.inventory.learning;

import ai.intentoms.learning.governance.GovernedLearner;
import ai.intentoms.learning.governance.LearnerDescriptor;
import ai.intentoms.learning.governance.LearnerGovernance;
import ai.intentoms.learning.model.ArtifactRef;
import ai.intentoms.learning.model.FactKey;
import ai.intentoms.learning.model.PolicyChange;
import ai.intentoms.learning.model.PolicyChangeset;
import ai.intentoms.learning.model.Proposal;
import ai.intentoms.learning.model.ProposalStatus;
import ai.intentoms.learning.model.SubjectType;
import ai.intentoms.learning.model.TypedValue;
import ai.intentoms.learning.store.ProposalRepository;
import ai.intentoms.platform.config.TenantConfigStore;
import ai.intentoms.platform.ids.Ids;
import ai.intentoms.platform.ledger.Actor;
import ai.intentoms.platform.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * IU-01: weekly safety-stock proposals per SKU and node, from demand variability. The recommendation is the textbook
 * {@code z(service level) x stdev(daily demand) x sqrt(lead time days)}, rounded up and clamped to the tenant's
 * {@code learning.safety_stock.min} / {@code max} (defaults 0 / 1000). Inputs: order-backed reservations over
 * {@code learning.safety_stock.history_days} (default 56, days without demand count as zero), service level
 * {@code learning.safety_stock.service_level} (0.95), lead time {@code learning.safety_stock.lead_days} (7).
 *
 * <p>A proposal ({@code ParameterChange} against {@code parameter:SKU_NODE:<sku>|<node>:SAFETY_STOCK_UNITS}, tier T1) is
 * drafted only when the recommendation differs from the current value by at least one unit and 10%, there is no open
 * proposal for the same position, and the position had demand on at least 5 days. At most 25 proposals per run, biggest
 * changes first. Promotion writes the fact; ATP uses it only while {@code inventory.safety_stock} is live.
 */
@Component
public class SafetyStockLearner implements GovernedLearner {
    public static final String KEY = "inventory_safety_stock";
    private static final Logger log = LoggerFactory.getLogger(SafetyStockLearner.class);
    private static final int MAX_PROPOSALS_PER_RUN = 25;
    private static final int MIN_DEMAND_DAYS = 5;

    private final InventoryLearningRepository repo;
    private final InventoryLearnerSupport support;
    private final ProposalRepository proposals;
    private final LearnerGovernance governance;
    private final TenantConfigStore config;

    public SafetyStockLearner(InventoryLearningRepository repo, InventoryLearnerSupport support, ProposalRepository proposals, LearnerGovernance governance, TenantConfigStore config) {
        this.repo = repo;
        this.support = support;
        this.proposals = proposals;
        this.governance = governance;
        this.config = config;
    }

    @Scheduled(fixedDelayString = "${oms.inventory.learning.safety-stock-sweep-ms:604800000}", initialDelayString = "${oms.inventory.learning.safety-stock-initial-ms:1800000}")
    public void scheduled() {
        try { sweepOnce(); } catch (RuntimeException e) { log.error("Safety stock learner failed: {}", e.toString()); }
    }

    public void sweepOnce() {
        for (String tenant : support.tenants()) {
            try { TenantContext.runAs(tenant, this::proposeForTenant); }
            catch (RuntimeException e) { log.warn("Safety stock learner failed for tenant {}: {}", tenant, e.toString()); }
        }
    }

    /** One recommendation, before the proposal filter. */
    public record Recommendation(String sku, String nodeId, int current, int recommended, double meanDaily, double stdevDaily, int demandDays) {
        public String parameterRef() { return SubjectType.SKU_NODE.name() + ":" + LearnedInventoryParameters.skuNode(sku, nodeId) + ":" + FactKey.SAFETY_STOCK_UNITS.name(); }
    }

    /** Drafts this tenant's proposals; returns the ids drafted. */
    public List<String> proposeForTenant() {
        if (!governance.begin(KEY).mayPropose()) return List.of();
        governance.recordRun(KEY);
        List<Recommendation> recs = recommend(Instant.now());
        List<String> drafted = new ArrayList<>();
        recs.sort(Comparator.comparingInt((Recommendation r) -> Math.abs(r.recommended() - r.current())).reversed());
        for (Recommendation r : recs) {
            if (drafted.size() >= MAX_PROPOSALS_PER_RUN) break;
            int delta = Math.abs(r.recommended() - r.current());
            if (delta < 1 || delta < 0.10 * r.current() || r.demandDays() < MIN_DEMAND_DAYS) continue;
            ArtifactRef target = new ArtifactRef("parameter", r.parameterRef());
            if (proposals.existsOpenFor(target.toString())) continue;
            Instant now = Instant.now();
            PolicyChangeset cs = new PolicyChangeset(target, 0, List.of(new PolicyChange.ParameterChange(r.parameterRef(), r.current(), r.recommended())),
                null, PolicyChangeset.RiskTier.T1);
            String id = Ids.prefixed("prop");
            proposals.insert(new Proposal(id, cs, null, PolicyChangeset.RiskTier.T1, ProposalStatus.DRAFT, null, null, Actor.system(KEY).ref(), now, now,
                null, null, null, null, null, null, null, null));
            drafted.add(id);
        }
        if (!drafted.isEmpty()) log.info("Drafted {} safety-stock proposals for tenant {}", drafted.size(), TenantContext.require());
        return drafted;
    }

    /** Recommendations for every position with demand in the window. */
    public List<Recommendation> recommend(Instant now) {
        int historyDays = Math.max(14, config.getInt("learning.safety_stock.history_days", 56));
        double serviceLevel = Math.min(0.999, Math.max(0.5, config.getDouble("learning.safety_stock.service_level", 0.95)));
        double leadDays = Math.max(1, config.getDouble("learning.safety_stock.lead_days", 7));
        int min = config.getInt("learning.safety_stock.min", 0);
        int max = config.getInt("learning.safety_stock.max", 1000);
        double z = inverseNormal(serviceLevel);
        LocalDate from = InventoryLearningRepository.day(now.minus(Duration.ofDays(historyDays)));
        Map<String, Map<LocalDate, Integer>> demand = new HashMap<>();
        for (InventoryLearningRepository.DailyDemand d : repo.dailyDemand(now.minus(Duration.ofDays(historyDays)))) {
            if (d.day().isBefore(from)) continue;
            demand.computeIfAbsent(LearnedInventoryParameters.skuNode(d.sku(), d.nodeId()), k -> new HashMap<>()).merge(d.day(), d.units(), Integer::sum);
        }
        Map<String, Integer> staticSafety = new HashMap<>();
        for (InventoryLearningRepository.Stock s : repo.stock()) staticSafety.put(LearnedInventoryParameters.skuNode(s.sku(), s.nodeId()), s.safety());
        List<Recommendation> out = new ArrayList<>();
        for (var e : demand.entrySet()) {
            double[] stats = meanStdev(e.getValue().values(), historyDays);
            int rec = (int) Math.ceil(z * stats[1] * Math.sqrt(leadDays));
            rec = Math.max(min, Math.min(max, rec));
            String[] parts = e.getKey().split("\\|", 2);
            int current = support.active(SubjectType.SKU_NODE, e.getKey(), FactKey.SAFETY_STOCK_UNITS)
                .filter(f -> f.value() instanceof TypedValue.NumberValue).map(f -> (int) Math.round(((TypedValue.NumberValue) f.value()).value()))
                .orElse(staticSafety.getOrDefault(e.getKey(), 0));
            out.add(new Recommendation(parts[0], parts[1], current, rec, stats[0], stats[1], e.getValue().size()));
        }
        return out;
    }

    /** Mean and population stdev of daily demand over {@code days} days, days absent from {@code nonZero} counting as zero. */
    static double[] meanStdev(java.util.Collection<Integer> nonZero, int days) {
        double sum = 0, sumSq = 0;
        for (int v : nonZero) { sum += v; sumSq += (double) v * v; }
        double mean = sum / days;
        double var = Math.max(0, sumSq / days - mean * mean);
        return new double[]{mean, Math.sqrt(var)};
    }

    /** Acklam's rational approximation of the standard normal quantile (abs error below 1.2e-9). */
    static double inverseNormal(double p) {
        double[] a = {-3.969683028665376e+01, 2.209460984245205e+02, -2.759285104469687e+02, 1.383577518672690e+02, -3.066479806614716e+01, 2.506628277459239e+00};
        double[] b = {-5.447609879822406e+01, 1.615858368580409e+02, -1.556989798598866e+02, 6.680131188771972e+01, -1.328068155288572e+01};
        double[] c = {-7.784894002430293e-03, -3.223964580411365e-01, -2.400758277161838e+00, -2.549732539343734e+00, 4.374664141464968e+00, 2.938163982698783e+00};
        double[] d = {7.784695709041462e-03, 3.224671290700398e-01, 2.445134137142996e+00, 3.754408661907416e+00};
        double pl = 0.02425;
        if (p < pl) {
            double q = Math.sqrt(-2 * Math.log(p));
            return (((((c[0] * q + c[1]) * q + c[2]) * q + c[3]) * q + c[4]) * q + c[5]) / ((((d[0] * q + d[1]) * q + d[2]) * q + d[3]) * q + 1);
        }
        if (p > 1 - pl) return -inverseNormal(1 - p);
        double q = p - 0.5, r = q * q;
        return (((((a[0] * r + a[1]) * r + a[2]) * r + a[3]) * r + a[4]) * r + a[5]) * q / (((((b[0] * r + b[1]) * r + b[2]) * r + b[3]) * r + b[4]) * r + 1);
    }

    @Override
    public LearnerDescriptor descriptor() {
        return LearnerDescriptor.of(KEY, "Safety stock", "Suggests how much buffer stock each location should hold, from how much its demand swings, within your limits.",
            LearnerDescriptor.Loop.FAST, "IU-01").feeds(LearnedInventoryParameters.SAFETY_STOCK);
    }

    @Override public boolean runnable() { return true; }

    @Override public void runNow() { sweepOnce(); }
}
