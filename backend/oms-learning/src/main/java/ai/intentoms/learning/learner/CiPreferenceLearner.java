package ai.intentoms.learning.learner;

import ai.intentoms.learning.governance.GovernedLearner;
import ai.intentoms.learning.governance.LearnerDescriptor;
import ai.intentoms.learning.governance.LearnerGovernance;
import ai.intentoms.learning.model.FactKey;
import ai.intentoms.learning.model.LearnedFact;
import ai.intentoms.learning.model.SubjectType;
import ai.intentoms.learning.model.TypedValue;
import ai.intentoms.learning.store.CustomerEvidenceRepository;
import ai.intentoms.learning.store.LearnedFactRepository;
import ai.intentoms.platform.config.TenantConfigStore;
import ai.intentoms.platform.ids.Ids;
import ai.intentoms.platform.ledger.Event;
import ai.intentoms.platform.ledger.EventSubscriber;
import ai.intentoms.platform.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * CI-01 / CI-03 / CI-04: four typed preference facts per customer and per segment, each from positive AND negative
 * evidence.
 * <ul>
 *   <li>{@code SPLIT_TOLERANCE} (0..1): a split order (several shipments or packages) fully delivered without a WISMO or
 *       complaint contact ({@code cs.wismo}, {@code cs.complaint}) is positive; a contact on a split order is negative
 *       (and overrides an earlier positive for that order), and so is a return of a split order because part of it
 *       arrived late or never arrived ({@code return.requested} with {@code arrived_late} / {@code not_received}).</li>
 *   <li>{@code SPEED_VS_COST} (-1..1): choosing an express / next-day / two-day ship method is evidence for speed, a
 *       standard / economy one for cost; so is a later change of ship method ({@code order.modified}): an upgrade to a
 *       faster method counts for speed, a downgrade for cost. A return because the order {@code arrived_late} counts
 *       for speed.</li>
 *   <li>{@code PICKUP_PREFERENCE} (bool): a pickup order (BOPIS, curbside, ship-to-store) is positive, a home-delivery order
 *       negative.</li>
 *   <li>{@code PREFERRED_CONTACT_CHANNEL} (text): the channel the customer contacts us on (each contact is a vote).</li>
 * </ul>
 * Evidence decays with a half-life ({@code learning.ci.half_life_days}, default 90, CI-04), so a customer who changed
 * habits is not stuck with old behaviour; a daily sweep re-derives the facts of customers with no recent evidence so
 * they drift back toward their prior. Cold start (CI-03): every observation also counts for the customer's cohort
 * segment ({@code channel|basket|region}, e.g. {@code web|small|CA}), and a customer's value is their own evidence shrunk
 * toward their cohort's: {@code p = (pos + K * p_cohort) / (pos + neg + K)} with {@code K = 5}. The cohort's own
 * value is written too, as a fact for subject SEGMENT {@code cohort:<channel|basket|region>} (Laplace-smoothed share,
 * rewritten when it moves by a point or its evidence grows by a tenth).
 *
 * <p>Evidence is captured even while the learner is paused (it is data); facts are written only when the governance gate
 * lets the learner run.
 */
@Component
public class CiPreferenceLearner implements EventSubscriber, GovernedLearner {
    public static final String KEY = "ci_preference";
    public static final String LEARNER_KEY = KEY;
    private static final Logger log = LoggerFactory.getLogger(CiPreferenceLearner.class);
    static final double SHRINKAGE_K = 5.0;
    static final Set<String> SPEED_METHODS = Set.of("express", "next_day", "nextday", "overnight", "two_day", "2day", "priority", "same_day", "rush");
    static final Set<String> COST_METHODS = Set.of("standard", "economy", "ground", "free", "saver", "slow");
    static final Set<String> CHANNELS = Set.of("email", "sms", "chat", "phone", "push", "whatsapp", "voice");

    private final CustomerEvidenceRepository evidence;
    private final LearnedFactRepository facts;
    private final LearnerGovernance governance;
    private final TenantConfigStore config;
    private final JdbcClient jdbc;
    private ai.intentoms.learning.store.ErasedSubjects erasedSubjects;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setErasedSubjects(ai.intentoms.learning.store.ErasedSubjects erasedSubjects) { this.erasedSubjects = erasedSubjects; }

    public CiPreferenceLearner(CustomerEvidenceRepository evidence, LearnedFactRepository facts, LearnerGovernance governance, TenantConfigStore config, JdbcClient jdbc) {
        this.evidence = evidence;
        this.facts = facts;
        this.governance = governance;
        this.config = config;
        this.jdbc = jdbc;
    }

    @Override public List<String> eventTypePrefixes() {
        return List.of("order.captured", "order.shipped", "event.shipped", "order.delivered", "event.delivered", "cs.wismo", "cs.complaint", "order.modified", "return.requested");
    }

    @Override
    public void on(Event e) {
        var previous = TenantContext.current();
        if (e.tenantId() != null) TenantContext.set(e.tenantId());
        try {
            Map<String, Object> p = e.payload() == null ? Map.of() : e.payload();
            String orderId = orderIdOf(e);
            Instant at = e.occurredAt() == null ? Instant.now() : e.occurredAt();
            switch (e.type()) {
                case "order.captured" -> onCaptured(e, p, orderId, at);
                case "order.shipped", "event.shipped" -> { if (orderId != null) evidence.recordShipment(orderId, Math.max(1, num(p.get("packageCount"), 1))); }
                case "order.delivered", "event.delivered" -> { if (!Boolean.FALSE.equals(p.get("allDelivered"))) onDelivered(e, orderId, at); }
                case "cs.wismo", "cs.complaint" -> onContact(e, p, orderId, at);
                case "order.modified" -> onModified(e, p, orderId, at);
                case "return.requested" -> onReturn(e, p, orderId, at);
                default -> {}
            }
        } catch (RuntimeException ex) {
            log.warn("CI preference learning failed for event {} ({}): {}", e.id(), e.type(), ex.toString());
        } finally {
            previous.ifPresentOrElse(TenantContext::set, TenantContext::clear);
        }
    }

    private void onCaptured(Event e, Map<String, Object> p, String orderId, Instant at) {
        String customer = str(p.get("customerRef"));
        if (customer == null || orderId == null) return;
        String cohort = cohortOf(p);
        evidence.registerOrder(orderId, customer, cohort);
        String method = str(p.get("shipMethod"));
        String speed = method == null ? null : speedEvidence(method);
        if (speed != null && evidence.claimEvent(e.id(), FactKey.SPEED_VS_COST.name())) observe(customer, cohort, FactKey.SPEED_VS_COST, speed, at);
        String orderType = str(p.get("orderType"));
        if (orderType != null && evidence.claimEvent(e.id(), FactKey.PICKUP_PREFERENCE.name())) observe(customer, cohort, FactKey.PICKUP_PREFERENCE, isPickup(orderType) ? "pos" : "neg", at);
    }

    private void onDelivered(Event e, String orderId, Instant at) {
        if (orderId == null) return;
        Optional<CustomerEvidenceRepository.OrderRef> ref = evidence.order(orderId);
        if (ref.isEmpty() || !ref.get().split() || ref.get().complained()) return;
        if (evidence.claimSplitEvidence(orderId, "pos")) observe(ref.get().customerRef(), ref.get().cohort(), FactKey.SPLIT_TOLERANCE, "pos", at);
    }

    private void onContact(Event e, Map<String, Object> p, String orderId, Instant at) {
        Optional<CustomerEvidenceRepository.OrderRef> ref = orderId == null ? Optional.empty() : evidence.order(orderId);
        String customer = Optional.ofNullable(str(p.get("customerRef"))).orElse(ref.map(CustomerEvidenceRepository.OrderRef::customerRef).orElse(null));
        if (customer == null) return;
        String cohort = ref.map(CustomerEvidenceRepository.OrderRef::cohort).or(() -> evidence.latestCohort(customer)).orElse("unknown|unknown|unknown");
        String channel = str(p.get("channel"));
        if (channel != null && CHANNELS.contains(channel.toLowerCase(Locale.ROOT)) && evidence.claimEvent(e.id(), FactKey.PREFERRED_CONTACT_CHANNEL.name()))
            observe(customer, cohort, FactKey.PREFERRED_CONTACT_CHANNEL, channel.toLowerCase(Locale.ROOT), at);
        if (ref.isEmpty()) return;
        evidence.recordComplaint(orderId);
        if (!ref.get().split()) return; // a contact on a single-package order says nothing about split tolerance
        splitNegative(orderId, customer, cohort, at);
    }

    /** A split order the customer was unhappy with; replaces an earlier "happy split" count for the same order. */
    private void splitNegative(String orderId, String customer, String cohort, Instant at) {
        boolean fresh = evidence.claimSplitEvidence(orderId, "neg");
        boolean flipped = !fresh && evidence.flipSplitEvidenceToNegative(orderId);
        if (fresh) observe(customer, cohort, FactKey.SPLIT_TOLERANCE, "neg", at);
        if (flipped) {
            // The order was first counted as a happy split; the complaint replaces that evidence.
            evidence.add(CustomerEvidenceRepository.CUSTOMER, customer, FactKey.SPLIT_TOLERANCE.name(), "pos", -1.0, halfLife(), at);
            evidence.add(CustomerEvidenceRepository.SEGMENT, cohort, FactKey.SPLIT_TOLERANCE.name(), "pos", -1.0, halfLife(), at);
            observe(customer, cohort, FactKey.SPLIT_TOLERANCE, "neg", at);
        }
    }

    /** A later ship-method change: an upgrade to a faster method is evidence for speed, a downgrade for cost. */
    private void onModified(Event e, Map<String, Object> p, String orderId, Instant at) {
        String to = str(p.get("shipMethod")), from = str(p.get("fromShipMethod"));
        if (orderId == null || to == null || from == null) return;
        int delta = speedRank(to) - speedRank(from);
        if (delta == 0 || speedRank(to) < 0 || speedRank(from) < 0) return;
        Optional<CustomerEvidenceRepository.OrderRef> ref = evidence.order(orderId);
        if (ref.isEmpty() || !evidence.claimEvent(e.id(), FactKey.SPEED_VS_COST.name())) return;
        observe(ref.get().customerRef(), ref.get().cohort(), FactKey.SPEED_VS_COST, delta > 0 ? "pos" : "neg", at);
    }

    /** Returns: "arrived late" says speed mattered; a split order returned because part was late or missing is a bad split. */
    private void onReturn(Event e, Map<String, Object> p, String orderId, Instant at) {
        if (orderId == null) return;
        Set<String> reasons = new java.util.HashSet<>();
        if (str(p.get("reasonCode")) != null) reasons.add(str(p.get("reasonCode")).toLowerCase(Locale.ROOT));
        if (p.get("lines") instanceof List<?> lines) for (Object l : lines) if (l instanceof Map<?, ?> m && str(m.get("reasonCode")) != null) reasons.add(str(m.get("reasonCode")).toLowerCase(Locale.ROOT));
        Optional<CustomerEvidenceRepository.OrderRef> ref = evidence.order(orderId);
        if (ref.isEmpty()) return;
        String customer = ref.get().customerRef(), cohort = ref.get().cohort();
        if (reasons.contains("arrived_late") && evidence.claimEvent(e.id(), FactKey.SPEED_VS_COST.name())) observe(customer, cohort, FactKey.SPEED_VS_COST, "pos", at);
        if (ref.get().split() && (reasons.contains("arrived_late") || reasons.contains("not_received"))) splitNegative(orderId, customer, cohort, at);
    }

    /** 0 economy/standard, 1 two-day/express/priority, 2 next-day/overnight/same-day; -1 unknown. */
    static int speedRank(String method) {
        String m = method.toLowerCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        if (m.contains("same_day") || m.contains("overnight") || m.contains("next_day") || m.equals("nextday") || m.equals("rush")) return 2;
        if (m.contains("two_day") || m.equals("2day") || m.contains("express") || m.contains("priority")) return 1;
        if (COST_METHODS.contains(m) || m.contains("standard") || m.contains("economy") || m.contains("ground")) return 0;
        return -1;
    }

    /** Adds one observation for the customer and their cohort, then re-derives the customer's fact. */
    void observe(String customer, String cohort, FactKey key, String option, Instant at) {
        if (erasedSubjects != null && erasedSubjects.erased(customer)) return; // GV-06 / N2: not even the cohort learns from an erased customer
        double hl = halfLife();
        evidence.add(CustomerEvidenceRepository.CUSTOMER, customer, key.name(), option, 1.0, hl, at);
        evidence.add(CustomerEvidenceRepository.SEGMENT, cohort, key.name(), option, 1.0, hl, at);
        if (!governance.begin(KEY).mayRun()) return;
        governance.recordRunThrottled(KEY);
        derive(customer, cohort, key, at);
        if (cohort != null) deriveSegment(cohort, key, at);
    }

    /** The cohort's own value (CI-01 per segment): its decayed evidence, Laplace-smoothed toward 0.5. */
    Optional<TypedValue> deriveSegment(String cohort, FactKey key, Instant at) {
        Map<String, Double> w = evidence.weights(CustomerEvidenceRepository.SEGMENT, cohort, key.name(), halfLife(), at);
        double n = w.values().stream().mapToDouble(v -> Math.max(0, v)).sum();
        if (n <= 1e-9) return Optional.empty();
        TypedValue value;
        if (key == FactKey.PREFERRED_CONTACT_CHANNEL) {
            value = new TypedValue.TextValue(preferredChannel(w, Map.of()));
        } else {
            double pos = Math.max(0, w.getOrDefault("pos", 0.0)), neg = Math.max(0, w.getOrDefault("neg", 0.0));
            double p = (pos + 1) / (pos + neg + 2);
            value = switch (key) {
                case SPEED_VS_COST -> new TypedValue.NumberValue(round(2 * p - 1));
                case PICKUP_PREFERENCE -> new TypedValue.BoolValue(p >= 0.5);
                default -> new TypedValue.NumberValue(round(p));
            };
        }
        String subject = segmentSubject(cohort);
        int samples = (int) Math.round(n);
        Optional<LearnedFact> current = facts.active(SubjectType.SEGMENT, subject, key);
        if (current.isPresent() && !moved(current.get(), value, samples)) return Optional.of(current.get().value());
        write(SubjectType.SEGMENT, subject, key, value, n / (n + SHRINKAGE_K), samples);
        return Optional.of(value);
    }

    public static String segmentSubject(String cohort) { return "cohort:" + cohort; }

    /** A segment fact is rewritten when its value moves by a point (or flips) or its evidence grows by a tenth. */
    private static boolean moved(LearnedFact current, TypedValue value, int samples) {
        if (samples >= Math.max(current.sampleSize() + 1, Math.ceil(current.sampleSize() * 1.1))) return true;
        if (current.value() instanceof TypedValue.NumberValue a && value instanceof TypedValue.NumberValue b) return Math.abs(a.value() - b.value()) >= 0.01;
        return !current.value().equals(value);
    }

    /** Recomputes one fact from decayed evidence; returns the value written, if any. */
    Optional<TypedValue> derive(String customer, String cohort, FactKey key, Instant at) {
        double hl = halfLife();
        Map<String, Double> mine = evidence.weights(CustomerEvidenceRepository.CUSTOMER, customer, key.name(), hl, at);
        Map<String, Double> prior = cohort == null ? Map.of() : evidence.weights(CustomerEvidenceRepository.SEGMENT, cohort, key.name(), hl, at);
        double n = mine.values().stream().mapToDouble(Double::doubleValue).map(v -> Math.max(0, v)).sum();
        if (n <= 1e-9) return Optional.empty();
        TypedValue value;
        if (key == FactKey.PREFERRED_CONTACT_CHANNEL) {
            value = new TypedValue.TextValue(preferredChannel(mine, prior));
        } else {
            double p = shrunkShare(mine.getOrDefault("pos", 0.0), mine.getOrDefault("neg", 0.0), prior.getOrDefault("pos", 0.0), prior.getOrDefault("neg", 0.0));
            value = switch (key) {
                case SPEED_VS_COST -> new TypedValue.NumberValue(round(2 * p - 1));
                case PICKUP_PREFERENCE -> new TypedValue.BoolValue(p >= 0.5);
                default -> new TypedValue.NumberValue(round(p));
            };
        }
        write(SubjectType.CUSTOMER, customer, key, value, n / (n + SHRINKAGE_K), (int) Math.round(n));
        return Optional.of(value);
    }

    /** {@code (pos + K * pCohort) / (pos + neg + K)}; the cohort share itself is Laplace-smoothed toward 0.5. */
    static double shrunkShare(double pos, double neg, double cohortPos, double cohortNeg) {
        double pCohort = (Math.max(0, cohortPos) + 1) / (Math.max(0, cohortPos) + Math.max(0, cohortNeg) + 2);
        pos = Math.max(0, pos);
        neg = Math.max(0, neg);
        return (pos + SHRINKAGE_K * pCohort) / (pos + neg + SHRINKAGE_K);
    }

    /** Customer votes plus {@code K} pseudo-votes spread by the cohort's distribution; the heaviest channel wins. */
    static String preferredChannel(Map<String, Double> mine, Map<String, Double> cohort) {
        double cohortTotal = cohort.values().stream().mapToDouble(v -> Math.max(0, v)).sum();
        Map<String, Double> score = new java.util.TreeMap<>();
        mine.forEach((k, v) -> score.merge(k, Math.max(0, v), Double::sum));
        if (cohortTotal > 0) cohort.forEach((k, v) -> score.merge(k, SHRINKAGE_K * Math.max(0, v) / cohortTotal, Double::sum));
        return score.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse("email");
    }

    private void write(SubjectType subjectType, String subject, FactKey key, TypedValue value, double confidence, int n) {
        Optional<LearnedFact> current = facts.active(subjectType, subject, key);
        if (current.isPresent() && current.get().value().equals(value) && current.get().sampleSize() == n) return;
        Instant now = Instant.now();
        int version = current.map(f -> f.version() + 1).orElseGet(() -> facts.history(subjectType, subject, key, 1).stream().findFirst().map(f -> f.version() + 1).orElse(1));
        facts.supersede(subjectType, subject, key, now);
        facts.insert(new LearnedFact(Ids.prefixed("fact"), subjectType, subject, key, value, Math.min(1.0, confidence), n, KEY, version, now, null, now));
    }

    // ---- CI-04 decay sweep

    @Scheduled(fixedDelayString = "${oms.learning.ci-decay-sweep-ms:86400000}", initialDelayString = "${oms.learning.ci-decay-initial-ms:3600000}")
    public void scheduled() {
        try { sweepOnce(); } catch (RuntimeException ex) { log.error("CI decay sweep failed: {}", ex.toString()); }
    }

    public void sweepOnce() {
        List<String> tenants = TenantContext.current().map(List::of).orElseGet(() -> jdbc.sql("select tenant_id from tenant order by tenant_id").query(String.class).list());
        for (String tenant : tenants) {
            try { TenantContext.runAs(tenant, this::refreshDecayed); }
            catch (RuntimeException ex) { log.warn("CI decay sweep failed for tenant {}: {}", tenant, ex.toString()); }
        }
    }

    /**
     * Re-derives the facts of customers with no evidence for a quarter of the half-life (CI-04 decay), and of customers
     * whose evidence is newer than their facts: evidence is stored even while the learner is switched off or paused, and
     * this is where it becomes facts once it may run again (A8, within one sweep, daily by default). Returns how many
     * customers were refreshed.
     */
    public int refreshDecayed() {
        if (!governance.begin(KEY).mayRun()) return 0;
        governance.recordRun(KEY);
        Instant now = Instant.now();
        java.util.LinkedHashSet<String> stale = new java.util.LinkedHashSet<>(evidence.staleCustomers(now.minus(Duration.ofSeconds((long) (halfLife() * 86400 / 4))), 5000));
        stale.addAll(evidence.customersWithUnderivedEvidence(5000));
        for (String customer : stale) {
            String cohort = evidence.latestCohort(customer).orElse(null);
            for (String k : evidence.factKeysFor(customer)) {
                try { derive(customer, cohort, FactKey.valueOf(k), now); } catch (IllegalArgumentException ignored) { /* unknown key from an older build */ }
            }
        }
        return stale.size();
    }

    // ---- helpers

    double halfLife() { return Math.max(1, config.getDouble("learning.ci.half_life_days", 90)); }

    /**
     * Cohort segment for cold start: channel | basket size (single, small 2-3 units, large 4+) | ship-to state. Pickup
     * orders keep the customer's state when the order carries one, so a customer's pickup and delivery orders share a prior.
     */
    static String cohortOf(Map<String, Object> p) {
        String channel = Optional.ofNullable(str(p.get("channel"))).orElse("unknown").toLowerCase(Locale.ROOT);
        int units = 0;
        if (p.get("lines") instanceof List<?> lines) for (Object l : lines) if (l instanceof Map<?, ?> m && m.get("qty") instanceof Number q) units += q.intValue();
        String basket = units <= 1 ? "single" : units <= 3 ? "small" : "large";
        String orderType = str(p.get("orderType"));
        String state = p.get("shipTo") instanceof Map<?, ?> st ? str(st.get("state")) : null;
        String region = state != null && !"null".equals(state) ? state.toUpperCase(Locale.ROOT) : orderType != null && isPickup(orderType) ? "pickup" : "unknown";
        return channel + "|" + basket + "|" + region;
    }

    static boolean isPickup(String orderType) {
        String t = orderType.toLowerCase(Locale.ROOT);
        return t.contains("bopis") || t.contains("pickup") || t.contains("ropis") || t.contains("curbside") || t.contains("ship_to_store") || t.contains("sts");
    }

    static String speedEvidence(String method) {
        String m = method.toLowerCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        if (SPEED_METHODS.contains(m) || m.contains("express") || m.contains("overnight") || m.contains("next_day")) return "pos";
        if (COST_METHODS.contains(m) || m.contains("standard") || m.contains("economy") || m.contains("ground")) return "neg";
        return null;
    }

    private static String orderIdOf(Event e) {
        if (e.payload() != null) {
            Object o = e.payload().getOrDefault("orderId", e.payload().get("order_id"));
            if (o != null) return String.valueOf(o);
        }
        return "order".equals(e.aggregateType()) ? e.aggregateId() : e.correlationId();
    }

    private static String str(Object o) { return o == null || String.valueOf(o).isBlank() ? null : String.valueOf(o); }
    private static int num(Object o, int dflt) { return o instanceof Number n ? n.intValue() : dflt; }
    private static double round(double v) { return Math.round(v * 10_000) / 10_000.0; }

    @Override
    public LearnerDescriptor descriptor() {
        return LearnerDescriptor.of(KEY, "Customer preferences",
                "Learns each customer's and each segment's split tolerance, speed-versus-cost leaning, pickup preference and contact channel from what they do (complaints after splits, upgrades, returns), fading old habits over time.",
                LearnerDescriptor.Loop.FAST, "CI-01, CI-03, CI-04")
            .features("order.captured", "order.shipped", "order.delivered", "cs.wismo", "cs.complaint", "order.modified", "return.requested").feeds(CustomerFeatureFamilies.PREFERENCES);
    }

    @Override public boolean runnable() { return true; }

    @Override public void runNow() { sweepOnce(); }
}
