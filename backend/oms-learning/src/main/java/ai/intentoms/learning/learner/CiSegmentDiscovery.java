package ai.intentoms.learning.learner;

import ai.intentoms.learning.governance.GovernedLearner;
import ai.intentoms.learning.governance.LearnerDescriptor;
import ai.intentoms.learning.governance.LearnerGovernance;
import ai.intentoms.learning.model.FactKey;
import ai.intentoms.learning.model.LearnedFact;
import ai.intentoms.learning.model.Segment;
import ai.intentoms.learning.model.SubjectType;
import ai.intentoms.learning.model.TypedValue;
import ai.intentoms.learning.store.CustomerEvidenceRepository;
import ai.intentoms.learning.store.LearnedFactRepository;
import ai.intentoms.learning.store.SegmentRepository;
import ai.intentoms.platform.ids.Ids;
import ai.intentoms.platform.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * CI-02: discovers customer segments by k-means over behaviour, not by hand-made rules. Each customer is a vector of
 * split tolerance, speed-vs-cost leaning, pickup share and (log) order count, taken from the CI-01 facts and the order
 * map; features are standardised, k is chosen between 2 and 6 by silhouette, and a cluster smaller than
 * {@code oms.learning.segment-min-size} (default 30) is folded into its nearest neighbour, so no published segment is
 * small enough to single people out. Segments are named by the analyst LLM when a copilot key is configured, else by a
 * template from what sets each one apart. Every member gets a {@code SEGMENT_MEMBERSHIP} fact (the segment key), which
 * decisions read through {@code CustomerFeatures} once customer facts are promoted for live use.
 */
@Component
public class CiSegmentDiscovery implements GovernedLearner {
    public static final String KEY = "ci_segment_discovery";
    private static final Logger log = LoggerFactory.getLogger(CiSegmentDiscovery.class);
    static final long SEED = 42L;

    private final LearnedFactRepository facts;
    private final SegmentRepository segments;
    private final CustomerEvidenceRepository evidence;
    private final LearnerGovernance governance;
    private final ObjectProvider<SegmentNamer> namers;
    private final JdbcClient jdbc;

    @Value("${oms.learning.segment-min-size:30}") private int minSize = 30;

    public CiSegmentDiscovery(LearnedFactRepository facts, SegmentRepository segments, CustomerEvidenceRepository evidence, LearnerGovernance governance,
                              ObjectProvider<SegmentNamer> namers, JdbcClient jdbc) {
        this.facts = facts;
        this.segments = segments;
        this.evidence = evidence;
        this.governance = governance;
        this.namers = namers;
        this.jdbc = jdbc;
    }

    @Scheduled(fixedDelayString = "${oms.learning.segment-sweep-ms:86400000}", initialDelayString = "${oms.learning.segment-sweep-ms:86400000}")
    public void scheduled() {
        try { sweepOnce(); } catch (RuntimeException e) { log.error("Segment discovery failed: {}", e.toString()); }
    }

    public void sweepOnce() {
        List<String> tenants = TenantContext.current().map(List::of).orElseGet(() -> jdbc.sql("select tenant_id from tenant order by tenant_id").query(String.class).list());
        for (String tenant : tenants) {
            try { TenantContext.runAs(tenant, this::discoverForTenant); }
            catch (RuntimeException e) { log.warn("Segment discovery failed for tenant {}: {}", tenant, e.toString()); }
        }
    }

    /** One discovery run; returns the segments written (empty when there is too little data or the learner may not run). */
    public List<Segment> discoverForTenant() {
        if (!governance.begin(KEY).mayRun()) return List.of();
        governance.recordRun(KEY);
        Map<String, double[]> raw = customerVectors();
        if (raw.size() < 2 * minSize) {
            return nothingVersioned("too few customers with behaviour: " + raw.size() + ", need " + (2 * minSize) + " (two segments of at least " + minSize + ")", raw.size(), null);
        }
        List<String> customers = new ArrayList<>(raw.keySet());
        double[][] x = new double[customers.size()][];
        for (int i = 0; i < customers.size(); i++) x[i] = raw.get(customers.get(i)).clone();
        double[] mean = new double[SegmentNamer.FEATURES.size()], sd = new double[mean.length];
        standardise(x, mean, sd);
        int maxK = Math.min(6, customers.size() / minSize);
        KMeans.Result r = KMeans.dropSmall(x, KMeans.best(x, maxK, SEED), minSize);
        if (r.k() < 2) {
            return nothingVersioned("fewer than two clusters of at least " + minSize + " customers after folding small ones into their neighbours ("
                + customers.size() + " customers, " + r.k() + " cluster left)", customers.size(), r.k());
        }

        List<SegmentNamer.Profile> profiles = new ArrayList<>();
        Map<String, Double> overall = new LinkedHashMap<>(), spread = new LinkedHashMap<>();
        for (int f = 0; f < mean.length; f++) {
            String name = SegmentNamer.FEATURES.get(f);
            overall.put(name, rawMean(raw.values(), f));
            spread.put(name, rawSd(raw.values(), f));
        }
        for (int j = 0; j < r.k(); j++) {
            List<double[]> members = new ArrayList<>();
            for (int i = 0; i < customers.size(); i++) if (r.assignment()[i] == j) members.add(raw.get(customers.get(i)));
            Map<String, Double> means = new LinkedHashMap<>();
            for (int f = 0; f < mean.length; f++) means.put(SegmentNamer.FEATURES.get(f), rawMean(members, f));
            profiles.add(new SegmentNamer.Profile(j, members.size(), means));
        }
        SegmentNamer namer = namers.getIfAvailable();
        String namedBy = "template";
        String templateReason = namer == null ? "no analyst configured" : null;
        List<SegmentNamer.Name> names = null;
        if (namer != null) {
            try {
                Optional<List<SegmentNamer.Name>> fromAnalyst = namer.name(profiles, overall);
                if (fromAnalyst.isPresent() && fromAnalyst.get().size() == profiles.size()) { names = fromAnalyst.get(); namedBy = "analyst"; }
                else templateReason = fromAnalyst.isEmpty() ? "analyst unavailable (no key, over budget or no answer)" : "analyst returned the wrong number of names";
            } catch (RuntimeException e) {
                log.warn("Segment naming failed, using template: {}", e.toString());
                templateReason = "analyst failed: " + e.getClass().getSimpleName();
            }
        }
        if (names == null) names = SegmentNamer.template(profiles, overall, spread);

        int version = segments.nextVersion();
        Instant now = Instant.now();
        List<Segment> written = new ArrayList<>();
        Map<Integer, String> keyByCluster = new HashMap<>();
        Set<String> usedKeys = new LinkedHashSet<>();
        for (SegmentNamer.Profile p : profiles) {
            SegmentNamer.Name name = names.get(p.index());
            String key = slug(name.label());
            for (int n = 2; !usedKeys.add(key); n++) key = slug(name.label()) + "-" + n;
            keyByCluster.put(p.index(), key);
            List<String> sample = new ArrayList<>();
            for (int i = 0; i < customers.size() && sample.size() < 10; i++) if (r.assignment()[i] == p.index()) sample.add(customers.get(i));
            Map<String, Object> definition = new LinkedHashMap<>();
            definition.put("key", key);
            definition.put("method", "kmeans");
            definition.put("k", r.k());
            definition.put("silhouette", Math.round(r.silhouette() * 1000) / 1000.0);
            definition.put("features", SegmentNamer.FEATURES);
            definition.put("centroid", p.means());
            definition.put("minSize", minSize);
            definition.put("sampleMembers", sample);
            // CI-02: who actually named it, and a typed label every segment carries whoever named it.
            definition.put("namedBy", namedBy);
            if (templateReason != null) definition.put("templateReason", templateReason);
            definition.put("traits", SegmentNamer.traits(p, overall, spread));
            Segment s = new Segment(Ids.prefixed("seg"), version, name.label(), name.description(), definition, p.size(), "system:" + KEY, now);
            segments.insert(s);
            written.add(s);
        }
        int changed = 0;
        for (int i = 0; i < customers.size(); i++) {
            if (writeMembership(customers.get(i), keyByCluster.get(r.assignment()[i]), now)) changed++;
        }
        log.info("Discovered {} segments (silhouette {}) for tenant {}; {} memberships changed", r.k(), r.silhouette(), TenantContext.require(), changed);
        recordRunResult(Map.of("outcome", "versioned", "version", version, "segments", r.k(), "customers", customers.size(),
            "silhouette", Math.round(r.silhouette() * 1000) / 1000.0, "membershipsChanged", changed));
        return written;
    }

    /** Config key holding the latest run's result: whether it versioned segments and, if not, why (C-N7). */
    public static final String LAST_RUN_KEY = "learning.segments.last_run";

    /** C-N7: a run that versions nothing says why, in the log and in the stored run result. */
    private List<Segment> nothingVersioned(String reason, int customers, Integer clusters) {
        log.info("Segment discovery for tenant {} versioned nothing: {}", TenantContext.require(), reason);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("outcome", "nothing_versioned");
        result.put("reason", reason);
        result.put("customers", customers);
        if (clusters != null) result.put("clusters", clusters);
        result.put("minSize", minSize);
        recordRunResult(result);
        return List.of();
    }

    private void recordRunResult(Map<String, Object> result) {
        if (config == null) return;
        Map<String, Object> stored = new LinkedHashMap<>(result);
        stored.put("at", Instant.now().toString());
        try { config.set(LAST_RUN_KEY, stored); } catch (RuntimeException e) { log.warn("Could not record the segment discovery result: {}", e.toString()); }
    }

    /** The latest run's result (outcome, reason when nothing was versioned), empty before the first run. */
    public Map<String, Object> lastRun() { return config == null ? Map.of() : config.getMap(LAST_RUN_KEY); }

    private ai.intentoms.platform.config.TenantConfigStore config;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setConfig(ai.intentoms.platform.config.TenantConfigStore config) { this.config = config; }

    /** Raw feature vector per customer that has any CI-01 fact. */
    Map<String, double[]> customerVectors() {
        Map<String, double[]> v = new LinkedHashMap<>();
        Map<String, Integer> orders = evidence.orderCounts();
        for (LearnedFact f : facts.activeByKey(SubjectType.CUSTOMER, FactKey.SPLIT_TOLERANCE, 100_000))
            vec(v, f.subjectId())[0] = num(f.value(), 0.5);
        for (LearnedFact f : facts.activeByKey(SubjectType.CUSTOMER, FactKey.SPEED_VS_COST, 100_000))
            vec(v, f.subjectId())[1] = num(f.value(), 0.0);
        for (LearnedFact f : facts.activeByKey(SubjectType.CUSTOMER, FactKey.PICKUP_PREFERENCE, 100_000))
            vec(v, f.subjectId())[2] = f.value() instanceof TypedValue.BoolValue b && b.value() ? 1.0 : 0.0;
        v.forEach((c, arr) -> arr[3] = Math.log1p(orders.getOrDefault(c, 1)));
        return v;
    }

    private static double[] vec(Map<String, double[]> v, String customer) {
        return v.computeIfAbsent(customer, k -> new double[]{0.5, 0.0, 0.0, Math.log1p(1)});
    }

    private boolean writeMembership(String customer, String segmentKey, Instant now) {
        Optional<LearnedFact> current = facts.active(SubjectType.CUSTOMER, customer, FactKey.SEGMENT_MEMBERSHIP);
        if (current.isPresent() && current.get().value() instanceof TypedValue.TextValue t && t.value().equals(segmentKey)) return false;
        int version = current.map(f -> f.version() + 1).orElse(1);
        facts.supersede(SubjectType.CUSTOMER, customer, FactKey.SEGMENT_MEMBERSHIP, now);
        facts.insert(new LearnedFact(Ids.prefixed("fact"), SubjectType.CUSTOMER, customer, FactKey.SEGMENT_MEMBERSHIP, new TypedValue.TextValue(segmentKey),
            0.8, 1, KEY, version, now, null, now));
        return true;
    }

    static void standardise(double[][] x, double[] mean, double[] sd) {
        int d = mean.length;
        for (double[] row : x) for (int f = 0; f < d; f++) mean[f] += row[f] / x.length;
        for (double[] row : x) for (int f = 0; f < d; f++) sd[f] += (row[f] - mean[f]) * (row[f] - mean[f]) / x.length;
        for (int f = 0; f < d; f++) sd[f] = Math.sqrt(sd[f]) < 1e-9 ? 1.0 : Math.sqrt(sd[f]);
        for (double[] row : x) for (int f = 0; f < d; f++) row[f] = (row[f] - mean[f]) / sd[f];
    }

    /** Mean of feature {@code f}; the order-count feature is reported back in orders, not log units. */
    private static double rawMean(java.util.Collection<double[]> rows, int f) {
        if (rows.isEmpty()) return 0;
        double s = 0;
        for (double[] r : rows) s += f == 3 ? Math.expm1(r[f]) : r[f];
        return Math.round(s / rows.size() * 1000) / 1000.0;
    }

    private static double rawSd(java.util.Collection<double[]> rows, int f) {
        double m = rawMean(rows, f), s = 0;
        for (double[] r : rows) { double v = f == 3 ? Math.expm1(r[f]) : r[f]; s += (v - m) * (v - m); }
        return rows.isEmpty() ? 1 : Math.sqrt(s / rows.size());
    }

    private static double num(TypedValue v, double dflt) { return v instanceof TypedValue.NumberValue n ? n.value() : dflt; }

    static String slug(String label) {
        String s = label.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
        return s.isEmpty() ? "segment" : s.length() > 60 ? s.substring(0, 60) : s;
    }

    @Override
    public LearnerDescriptor descriptor() {
        return LearnerDescriptor.of(KEY, "Customer segments", "Groups customers who behave alike (by splits, speed, pickup and how often they buy) so new customers start from their group's habits.",
            LearnerDescriptor.Loop.FAST, "CI-02").features("SPLIT_TOLERANCE", "SPEED_VS_COST", "PICKUP_PREFERENCE", "order count").feeds(CustomerFeatureFamilies.PREFERENCES);
    }

    @Override public boolean runnable() { return true; }

    @Override public void runNow() { sweepOnce(); }
}
