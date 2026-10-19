package ai.intentoms.learning.service;

import ai.intentoms.decision.record.DecisionRecordRepository;
import ai.intentoms.platform.config.TenantConfigStore;
import ai.intentoms.platform.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Requirements §7: every reward term is scaled to [0,1] against the tenant's own baseline, so a weight means the same
 * thing in every tenant. Normalisation is never off: every term always has an explicit scale, and the stored baseline
 * ({@code learning.reward_baseline}) says per term where it came from.
 *
 * <ul>
 *   <li><b>fill, otd</b>: the identity. Both are already shares in [0,1] by definition (units shipped on the first
 *       attempt over units ordered; deliveries on time over deliveries), so a baseline cannot make them mean more.</li>
 *   <li><b>cost, split, fraud, cx, inv</b>: the 95th percentile of the term's measured (raw) value over the baseline
 *       episodes where it is positive (floor {@value #FLOOR}); a reward then uses {@code min(1, raw / scale)} and keeps the
 *       raw value as {@code <term>_raw}. Aged-stock relief is measured as the age in days of the aged stock sold, so its
 *       scale is the baseline's typical (95th percentile) aged-stock age.</li>
 * </ul>
 *
 * <p>Which episodes are the baseline, per term, in this order (the last 90 days of rewarded episodes):
 * <ol>
 *   <li><b>holdout</b>: episodes of customers in the persistent holdout, once the term has at least
 *       {@value #MIN_SAMPLES} positive values there. The holdout runs on the baseline policy, so its outcomes are the
 *       tenant's own baseline and do not drift with what learning changed;</li>
 *   <li><b>logged</b>: otherwise the tenant's own logged episodes, the replay baseline of the policy currently running,
 *       again with at least {@value #MIN_SAMPLES} positive values;</li>
 *   <li><b>default</b>: otherwise the documented default ({@link RewardCalculator#DEFAULT_SCALES}): the term's natural
 *       range, so the scaled value equals the measured one until the tenant has enough history.</li>
 * </ol>
 * Recomputed daily ({@code oms.learning.reward-baseline-cron}, 06:30 UTC) and on demand (job {@code reward-baseline}).
 * Before the first computation {@link #effective()} reports the defaults, so a fresh tenant still has a scale for every
 * term.
 */
@Component
public class RewardBaselineService {
    private static final Logger log = LoggerFactory.getLogger(RewardBaselineService.class);
    public static final String KEY = "learning.reward_baseline";
    /** Terms scaled against the baseline episodes (the rest of {@link RewardCalculator#TERMS} use the identity). */
    public static final List<String> SCALED_TERMS = List.of("cost", "split", "fraud", "cx", "inv");
    static final double FLOOR = 0.05;
    static final int MIN_SAMPLES = 30;
    static final int WINDOW_DAYS = 90;

    static final Map<String, String> IDENTITY_REASON = Map.of(
        "fill", "already a share in [0,1]: units shipped on the first attempt / units ordered",
        "otd", "already a share in [0,1]: deliveries on time / deliveries");
    static final Map<String, String> DEFAULT_REASON = Map.of(
        "cost", "the natural range: cost / order value, capped at 1",
        "split", "the natural range: 1 means three or more parcels",
        "fraud", "the natural range: 1 means a loss of the whole order value",
        "cx", "the natural range: 1 means one complaint (a WISMO counts 0.5)",
        "inv", "the age at which stock counts as aged (" + (int) RewardCalculator.DEFAULT_AGED_DAYS + " days): any aged sale is full relief");

    private final JdbcClient jdbc;
    private final TenantConfigStore config;
    private final DecisionRecordRepository decisionRecords;

    public RewardBaselineService(JdbcClient jdbc, TenantConfigStore config, DecisionRecordRepository decisionRecords) {
        this.jdbc = jdbc;
        this.config = config;
        this.decisionRecords = decisionRecords;
    }

    /** The scale of every reward term for the current tenant: the stored baseline's, else the documented default. */
    public Map<String, Double> current() {
        Map<String, Double> out = new LinkedHashMap<>(RewardCalculator.DEFAULT_SCALES);
        Object scales = config.getMap(KEY).get("scales");
        if (scales instanceof Map<?, ?> m) m.forEach((k, v) -> { if (v instanceof Number n && n.doubleValue() > 0 && out.containsKey(String.valueOf(k))) out.put(String.valueOf(k), n.doubleValue()); });
        for (String t : RewardCalculator.RATE_TERMS) out.put(t, 1.0);
        return out;
    }

    /**
     * The baseline as stored, or, before the first computation, the defaults in the same shape (every term with its
     * scale, method, source and reason), so what normalisation uses is always visible.
     */
    public Map<String, Object> effective() {
        Map<String, Object> stored = config.getMap(KEY);
        if (stored.get("terms") instanceof Map<?, ?>) return stored;
        Map<String, Double> scales = current();
        Map<String, Object> terms = new LinkedHashMap<>();
        for (String term : RewardCalculator.TERMS) {
            boolean fromStore = stored.get("scales") instanceof Map<?, ?> m && m.get(term) != null && !RewardCalculator.RATE_TERMS.contains(term);
            terms.put(term, fromStore ? entry(scales.get(term), "p95", stored.get("sources") instanceof Map<?, ?> src && src.get(term) != null ? String.valueOf(src.get(term)) : "stored", null,
                "computed by an earlier build") : defaultEntry(term, 0L));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("scales", scales);
        out.put("terms", terms);
        out.put("source", stored.isEmpty() ? "default" : "stored");
        out.put("computedAt", stored.get("computedAt"));
        out.put("note", stored.isEmpty() ? "no baseline computed yet for this tenant: documented defaults apply until the daily job (or POST /api/v1/learning/jobs/reward-baseline/run) computes one" : null);
        return out;
    }

    @Scheduled(cron = "${oms.learning.reward-baseline-cron:0 30 6 * * *}", zone = "UTC")
    public void scheduled() {
        try {
            for (String t : decisionRecords.tenantsWithRecords(Instant.now().minus(Duration.ofDays(WINDOW_DAYS)))) TenantContext.runAs(t, this::recompute);
        } catch (RuntimeException e) { log.error("Reward baseline recompute failed: {}", e.toString()); }
    }

    /** Recomputes the current tenant's scale for every term (see the class comment) and returns what was stored. */
    public Map<String, Object> recompute() {
        Map<String, Double> scales = new LinkedHashMap<>();
        Map<String, Long> samples = new LinkedHashMap<>();
        Map<String, String> sources = new LinkedHashMap<>();
        Map<String, Object> terms = new LinkedHashMap<>();
        Timestamp since = Timestamp.from(Instant.now().minus(Duration.ofDays(WINDOW_DAYS)));
        for (String term : RewardCalculator.TERMS) {
            if (RewardCalculator.RATE_TERMS.contains(term)) {
                scales.put(term, 1.0);
                sources.put(term, "identity");
                terms.put(term, entry(1.0, "identity", "identity", null, IDENTITY_REASON.get(term)));
                continue;
            }
            Object[] held = p95(term, true, since);
            Object[] logged = (long) held[0] >= MIN_SAMPLES ? held : p95(term, false, since);
            long n = (long) logged[0];
            if (n >= MIN_SAMPLES && logged[1] instanceof Number p) {
                String source = logged == held ? "holdout" : "logged";
                double floor = "inv".equals(term) ? 1.0 : FLOOR;
                double ceiling = "inv".equals(term) ? Double.MAX_VALUE : 1.0;
                double scale = Math.max(floor, Math.min(ceiling, p.doubleValue()));
                scales.put(term, round(scale));
                sources.put(term, source);
                samples.put(term, n);
                terms.put(term, entry(round(scale), "p95", source, n, "95th percentile of the positive " + term + " values of the "
                    + ("holdout".equals(source) ? "persistent holdout's episodes (the baseline policy)" : "tenant's logged episodes (the replay baseline of the current policy)")
                    + " over the last " + WINDOW_DAYS + " days" + ("inv".equals(term) ? ", in days of stock age" : "")));
            } else {
                scales.put(term, RewardCalculator.DEFAULT_SCALES.get(term));
                sources.put(term, "default");
                samples.put(term, Math.max((long) held[0], n));
                terms.put(term, defaultEntry(term, Math.max((long) held[0], n)));
            }
        }
        Map<String, Object> stored = new LinkedHashMap<>();
        stored.put("scales", scales);
        stored.put("terms", terms);
        stored.put("samples", samples);
        stored.put("sources", sources);
        List<String> baselineSources = sources.values().stream().filter(s -> !"identity".equals(s)).distinct().toList();
        stored.put("source", baselineSources.size() == 1 ? baselineSources.get(0) : "mixed");
        stored.put("holdoutEpisodes", episodes(true, since));
        stored.put("loggedEpisodes", episodes(false, since));
        stored.put("windowDays", WINDOW_DAYS);
        stored.put("minSamples", MIN_SAMPLES);
        stored.put("computedAt", Instant.now().toString());
        config.set(KEY, stored);
        log.info("Reward baseline for tenant {}: scales {} from {}", TenantContext.require(), scales, sources);
        return stored;
    }

    private static Map<String, Object> defaultEntry(String term, long samples) {
        String reason = RewardCalculator.RATE_TERMS.contains(term) ? IDENTITY_REASON.get(term)
            : "documented default (fewer than " + MIN_SAMPLES + " positive values in the holdout or the logged episodes, " + samples + " found): " + DEFAULT_REASON.get(term);
        return entry(RewardCalculator.DEFAULT_SCALES.get(term), RewardCalculator.RATE_TERMS.contains(term) ? "identity" : "default",
            RewardCalculator.RATE_TERMS.contains(term) ? "identity" : "default", samples, reason);
    }

    private static Map<String, Object> entry(Double scale, String method, String source, Long samples, String reason) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("scale", scale);
        e.put("method", method);
        e.put("source", source);
        if (samples != null) e.put("samples", samples);
        e.put("reason", reason);
        return e;
    }

    /**
     * [count of positive values, their 95th percentile] for one term, over holdout episodes only or over every logged
     * episode. Aged-stock relief is read only from episodes that recorded it in days ({@code inv_raw}); older episodes
     * stored a 0/1 flag.
     */
    private Object[] p95(String term, boolean holdoutOnly, Timestamp since) {
        String value = "inv".equals(term) ? "(e.reward_components->>'inv_raw')::double precision"
            : "coalesce((e.reward_components->>:raw)::double precision, (e.reward_components->>:term)::double precision)";
        var row = jdbc.sql("""
            select count(*) as n, percentile_cont(0.95) within group (order by v) as p95
            from (select %s as v
                  from episode e left join decision_record d on d.tenant_id = e.tenant_id and d.id = e.decision_id
                  where e.tenant_id = :t and e.reward is not null and e.created_at >= :since
                    and (cast(:held as boolean) = false or d.details->'experiment'->>'arm' = 'holdout')) x
            where v > 0
            """.formatted(value)).param("raw", term + "_raw").param("term", term).param("t", TenantContext.require()).param("since", since).param("held", holdoutOnly)
            .query().singleRow();
        return new Object[]{((Number) row.get("n")).longValue(), row.get("p95")};
    }

    private long episodes(boolean holdoutOnly, Timestamp since) {
        return jdbc.sql("""
            select count(*) from episode e left join decision_record d on d.tenant_id = e.tenant_id and d.id = e.decision_id
            where e.tenant_id = :t and e.reward is not null and e.created_at >= :since
              and (cast(:held as boolean) = false or d.details->'experiment'->>'arm' = 'holdout')
            """).param("t", TenantContext.require()).param("since", since).param("held", holdoutOnly).query(Long.class).single();
    }

    private static double round(double v) { return Math.round(v * 10_000d) / 10_000d; }
}
