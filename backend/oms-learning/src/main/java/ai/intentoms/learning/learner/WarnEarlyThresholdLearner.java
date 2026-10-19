package ai.intentoms.learning.learner;

import ai.intentoms.decision.model.DecisionRecord;
import ai.intentoms.decision.record.DecisionRecordRepository;
import ai.intentoms.learning.governance.GovernedLearner;
import ai.intentoms.learning.governance.LearnerDescriptor;
import ai.intentoms.learning.governance.LearnerGovernance;
import ai.intentoms.learning.model.Episode;
import ai.intentoms.learning.model.FactKey;
import ai.intentoms.learning.model.OutcomeData;
import ai.intentoms.learning.model.OutcomeRecord;
import ai.intentoms.learning.model.SubjectType;
import ai.intentoms.learning.store.EpisodeRepository;
import ai.intentoms.learning.store.OutcomeRepository;
import ai.intentoms.platform.config.TenantConfigStore;
import ai.intentoms.platform.ledger.EventStore;
import ai.intentoms.platform.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * CX-03: the earliest confident breach-risk score at which to warn a customer, chosen to minimise "where is my order"
 * contacts. For every order with an {@code sla_breach_risk} episode and a delivery outcome the learner keeps the score
 * trajectory (each record's {@link #effectiveScore effective score}, the one the scorer stored and the warning gate
 * compared with the threshold, not an unconfident model answer the system did not act on), the promised date, whether
 * it arrived late, whether the customer contacted support and whether a delay
 * warning was sent. From those it measures contact rates: late orders not warned vs warned, and on-time orders not
 * warned vs warned (a false alarm can itself prompt a contact), each smoothed toward a prior when there is little
 * history ({@code cx.warn_early.contact_reduction}, default: a warning halves contacts on late orders).
 *
 * <p>A threshold t would warn an order the first time its score reached t. The value of t is the contacts it avoids on
 * late orders, each scaled by how early the warning came (full credit two days or more before the promise, none once
 * the promise has passed), minus the extra contacts its false alarms cause. The best positive threshold becomes
 * {@code WARN_EARLY_THRESHOLD} on {@code DECISION:sla_breach_risk}; the breach-risk scorer uses it through
 * {@code ParameterResolver} ({@code monitoring.warn_early_threshold}) to send the proactive notice.
 */
@Component
public class WarnEarlyThresholdLearner implements GovernedLearner {
    public static final String KEY = "warn_early";
    public static final String PARAMETER = "monitoring.warn_early_threshold";
    public static final String STATIC_KEY = "cx.warn_early_threshold";
    public static final String DECISION = "sla_breach_risk";
    public static final String DELAY_WARNING = "order.delay_warning";
    static final double FULL_CREDIT_HOURS = 48;
    private static final Logger log = LoggerFactory.getLogger(WarnEarlyThresholdLearner.class);

    private final EpisodeRepository episodes;
    private final OutcomeRepository outcomes;
    private final DecisionRecordRepository decisionRecords;
    private final FulfilmentFacts facts;
    private final TenantConfigStore config;
    private final LearnerGovernance governance;
    private final NodeAssignmentRepository assignments;
    private final EventStore events;

    @Value("${oms.learning.cx03.window-days:90}") private int windowDays = 90;
    @Value("${oms.learning.cx03.min-delivered:30}") private int minDelivered = 30;

    public WarnEarlyThresholdLearner(EpisodeRepository episodes, OutcomeRepository outcomes, DecisionRecordRepository decisionRecords, FulfilmentFacts facts,
                                     TenantConfigStore config, LearnerGovernance governance, NodeAssignmentRepository assignments, EventStore events) {
        this.episodes = episodes;
        this.outcomes = outcomes;
        this.decisionRecords = decisionRecords;
        this.facts = facts;
        this.config = config;
        this.governance = governance;
        this.assignments = assignments;
        this.events = events;
    }

    @Scheduled(fixedDelayString = "${oms.learning.cx03.sweep-ms:86400000}", initialDelayString = "${oms.learning.cx03.sweep-ms:920000}")
    public void scheduled() {
        try { sweepOnce(); } catch (RuntimeException e) { log.error("Warn-early sweep failed: {}", e.toString()); }
    }

    public int sweepOnce() {
        List<String> tenants = TenantContext.current().map(List::of).orElseGet(() -> decisionRecords.tenantsWithRecords(Instant.now().minus(Duration.ofDays(windowDays))));
        int n = 0;
        for (String tenant : tenants) n += TenantContext.supplyAs(tenant, this::sweepTenant);
        return n;
    }

    /** One delivered order: when each score was seen, the end of the promised day, and what happened. */
    record Observed(List<Point> trajectory, Instant promiseDeadline, boolean late, boolean contacted, boolean warned) {
        /** When a threshold would first have warned this order, or null. */
        Instant warnAt(int threshold) {
            for (Point p : trajectory) if (p.score() >= threshold) return p.at();
            return null;
        }
    }

    record Point(Instant at, int score) {}

    /** Contact rates the value of a threshold is computed from. */
    record ContactRates(double lateUnwarned, double lateWarned, double onTimeUnwarned, double onTimeWarned) {}

    private int sweepTenant() {
        if (!governance.begin(KEY).mayRun()) return 0;
        governance.recordRun(KEY);
        List<Observed> observed = new ArrayList<>();
        for (Episode ep : episodes.forDecisionSince(DECISION, Instant.now().minus(Duration.ofDays(windowDays)), 10_000)) {
            if (ep.orderId() == null) continue;
            List<OutcomeRecord> outs = outcomes.forEpisode(ep.episodeId());
            Boolean late = late(outs);
            if (late == null) continue;
            List<Point> trajectory = decisionRecords.forSubject("order", ep.orderId()).stream().filter(r -> DECISION.equals(r.decision()))
                .map(r -> new Point(r.createdAt(), score(r))).filter(p -> p.score() >= 0).sorted(Comparator.comparing(Point::at)).toList();
            if (trajectory.isEmpty()) continue;
            boolean contacted = outs.stream().anyMatch(o -> o.payload() instanceof OutcomeData.ContactOutcome);
            boolean warned = events.load("order", ep.orderId()).stream().anyMatch(e -> DELAY_WARNING.equals(e.type()));
            observed.add(new Observed(trajectory, promiseDeadline(ep.orderId()), late, contacted, warned));
        }
        if (observed.size() < minDelivered || observed.stream().filter(Observed::late).count() < 3) return 0;
        ContactRates rates = contactRates(observed, config.getDouble("cx.warn_early.contact_reduction", 0.5));
        Integer best = bestThreshold(observed, rates);
        if (best == null) return 0;
        return facts.number(SubjectType.DECISION, DECISION, FactKey.WARN_EARLY_THRESHOLD, best, observed.size(), KEY, 0.5) ? 1 : 0;
    }

    /**
     * Smoothed contact rates (k = 5 pseudo-orders toward each prior). Without warned history the priors say a warning
     * removes {@code reduction} of the late-order contacts and adds 10 points of contact on an on-time order.
     */
    static ContactRates contactRates(List<Observed> observed, double reduction) {
        double k = 5;
        long[] lu = count(observed, true, false), lw = count(observed, true, true), ou = count(observed, false, false), ow = count(observed, false, true);
        double lateUnwarned = (lu[1] + 0.5 * k) / (lu[0] + k);
        double lateWarned = (lw[1] + lateUnwarned * (1 - reduction) * k) / (lw[0] + k);
        double onTimeUnwarned = (ou[1] + 0.05 * k) / (ou[0] + k);
        double onTimeWarned = (ow[1] + Math.min(1.0, onTimeUnwarned + 0.10) * k) / (ow[0] + k);
        return new ContactRates(lateUnwarned, lateWarned, onTimeUnwarned, onTimeWarned);
    }

    private static long[] count(List<Observed> observed, boolean late, boolean warned) {
        long n = 0, contacts = 0;
        for (Observed o : observed) if (o.late() == late && o.warned() == warned) { n++; if (o.contacted()) contacts++; }
        return new long[]{n, contacts};
    }

    /** The threshold (1..4) with the highest contacts avoided x earliness - false-alarm contacts, or null when none is positive. */
    static Integer bestThreshold(List<Observed> observed, ContactRates r) {
        Integer best = null;
        double bestValue = 0;
        double avoided = Math.max(0, r.lateUnwarned() - r.lateWarned());
        double caused = Math.max(0, r.onTimeWarned() - r.onTimeUnwarned());
        for (int t = 1; t <= 4; t++) {
            double value = 0;
            for (Observed o : observed) {
                Instant at = o.warnAt(t);
                if (at == null) continue;
                value += o.late() ? avoided * earliness(at, o.promiseDeadline()) : -caused;
            }
            if (value > bestValue + 1e-9) { bestValue = value; best = t; }
        }
        return best;
    }

    /** Full credit for a warning two days or more before the promise, linear down to none once the promise has passed. */
    static double earliness(Instant warnAt, Instant promiseDeadline) {
        if (promiseDeadline == null) return 0.5;
        double hours = Duration.between(warnAt, promiseDeadline).toMinutes() / 60.0;
        return Math.max(0, Math.min(1, hours / FULL_CREDIT_HOURS));
    }

    /** The end of the latest promised day among the order's packages. */
    private Instant promiseDeadline(String orderId) {
        LocalDate latest = assignments.assignmentsFor(orderId).stream().map(NodeAssignmentRepository.Assignment::promisedEta).filter(Objects::nonNull)
            .max(Comparator.naturalOrder()).orElse(null);
        return latest == null ? null : latest.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    private static Boolean late(List<OutcomeRecord> outs) {
        for (OutcomeRecord o : outs) if (o.payload() instanceof OutcomeData.DeliveryOutcome d) return !d.onTime() || d.lost();
        return null;
    }

    /** The score this record made the system act on (B3-6): what the breach-risk scorer stored and the warning gate read. */
    static int score(DecisionRecord r) {
        Object answer = r.answers() == null ? null : r.answers().get("breach_risk");
        Double a = answer instanceof ai.intentoms.decision.model.Answer ans ? ans.asDouble() : null;
        return effectiveScore("act".equalsIgnoreCase(r.action()) && !r.requiresHuman(), r.value(), r.details(), a);
    }

    /**
     * CX-03 (audit B3-6): the 0-4 breach-risk score the system acts on, one rule for the scorer that stores it (and whose
     * warning gate compares it with the threshold) and for this learner. A confident answer (act band) is used as is.
     * When the decision only recommends and the runtime supplied its deterministic default ({@code confirmFallback}, the
     * confirm band on T1), the default is the score; the model's unconfident answer is not. Otherwise the value, else the
     * {@code breach_risk} answer, else 1. Rounded and kept within 0-4.
     */
    public static int effectiveScore(boolean acts, Object value, java.util.Map<String, Object> details, Double breachRiskAnswer) {
        double v = Double.NaN;
        Object fallback = details == null ? null : details.get("confirmFallback");
        if (!acts && fallback instanceof java.util.Map<?, ?> fb && fb.get("defaultValue") instanceof Number d) v = d.doubleValue();
        else if (value instanceof Number n) v = n.doubleValue();
        else if (value instanceof String s) { try { v = Double.parseDouble(s); } catch (NumberFormatException ignored) { } }
        if (Double.isNaN(v) && breachRiskAnswer != null) v = breachRiskAnswer;
        if (Double.isNaN(v)) v = 1;
        return (int) Math.max(0, Math.min(4, Math.round(v)));
    }

    @Override public boolean runnable() { return true; }

    @Override public void runNow() { sweepOnce(); }

    @Override
    public LearnerDescriptor descriptor() {
        return LearnerDescriptor.of(KEY, "Early delay warning", "Learns how early, and at what risk, to tell a customer about a likely delay so fewer of them have to ask.",
            LearnerDescriptor.Loop.FAST, "CX-03").readsOutcomesOf(DECISION).feeds(PARAMETER);
    }
}
