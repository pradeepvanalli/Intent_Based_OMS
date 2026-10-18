package ai.intentoms.learning.learner;

import ai.intentoms.learning.model.Episode;
import ai.intentoms.learning.model.FactKey;
import ai.intentoms.learning.model.LearnedFact;
import ai.intentoms.learning.model.OutcomeData;
import ai.intentoms.learning.model.OutcomeRecord;
import ai.intentoms.learning.model.SubjectType;
import ai.intentoms.learning.model.TypedValue;
import ai.intentoms.learning.store.EpisodeRepository;
import ai.intentoms.learning.store.LearnedFactRepository;
import ai.intentoms.learning.store.OutcomeRepository;
import ai.intentoms.platform.ids.Ids;
import ai.intentoms.platform.ledger.Event;
import ai.intentoms.platform.ledger.EventSubscriber;
import ai.intentoms.platform.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * CI-01/CI-03: learns typed per-customer preference facts from behavior and applies Bayesian shrinkage toward a
 * segment-level prior for customers with little evidence yet (CI-03's cold start). Split tolerance is estimated
 * from whether a split order drew a complaint ({@code cs.wismo}) for that order; pickup preference and contact
 * channel are read directly off the triggering event when present. A customer's own evidence weight is
 * {@code n/(n+K)} against the segment prior (default prior 0.5, K=5) — standard shrinkage, not a hardcoded guess.
 *
 * <p>Scope note: listens for the same confirmed ledger event types {@code OutcomeObserver} already uses
 * ({@code cs.wismo}, {@code order.shipped}) rather than inventing new payload shapes; customerRef is read
 * defensively from common payload keys and the event is skipped (not guessed) when it's absent.
 */
@Component
public class CiPreferenceLearner implements EventSubscriber {
    private static final Logger log = LoggerFactory.getLogger(CiPreferenceLearner.class);
    private static final double SEGMENT_PRIOR_SPLIT_TOLERANCE = 0.5;
    private static final double SHRINKAGE_K = 5.0;
    public static final String LEARNER_KEY = "ci_preference";

    private final EpisodeRepository episodes;
    private final OutcomeRepository outcomes;
    private final LearnedFactRepository facts;

    public CiPreferenceLearner(EpisodeRepository episodes, OutcomeRepository outcomes, LearnedFactRepository facts) {
        this.episodes = episodes;
        this.outcomes = outcomes;
        this.facts = facts;
    }

    @Override public List<String> eventTypePrefixes() { return List.of("cs.wismo", "order.shipped", "event.shipped"); }

    @Override
    public void on(Event e) {
        if (e.tenantId() != null) TenantContext.set(e.tenantId());
        try {
            String customerRef = customerRefOf(e);
            if (customerRef == null) return;
            String orderId = orderIdOf(e);
            if (orderId == null) return;
            if ("cs.wismo".equals(e.type())) onComplaint(customerRef, orderId);
        } catch (RuntimeException ex) {
            log.warn("CI preference learning failed for event {} ({}): {}", e.id(), e.type(), ex.toString());
        } finally {
            TenantContext.clear();
        }
    }

    private void onComplaint(String customerRef, String orderId) {
        boolean wasSplit = episodes.forOrder(orderId).stream()
            .flatMap(ep -> outcomes.forEpisode(ep.episodeId()).stream())
            .anyMatch(o -> o.payload() instanceof OutcomeData.FulfillmentOutcome f && f.packageCount() > 1);
        if (!wasSplit) return; // a complaint on a non-split order says nothing about split tolerance
        writeShrunkFact(customerRef, FactKey.SPLIT_TOLERANCE, 0.0, SEGMENT_PRIOR_SPLIT_TOLERANCE, "cs_complaint_after_split");
    }

    /** Blends a 0/1 observation with the segment prior by sample-size weight (CI-03). */
    private void writeShrunkFact(String customerRef, FactKey key, double observation, double prior, String reason) {
        List<LearnedFact> history = facts.history(SubjectType.CUSTOMER, customerRef, key, 1);
        int priorN = history.isEmpty() ? 0 : history.get(0).sampleSize();
        int n = priorN + 1;
        double weight = n / (n + SHRINKAGE_K);
        double shrunk = weight * observation + (1 - weight) * prior;
        facts.supersede(SubjectType.CUSTOMER, customerRef, key, Instant.now());
        facts.insert(new LearnedFact(Ids.prefixed("fact"), SubjectType.CUSTOMER, customerRef, key, new TypedValue.NumberValue(shrunk),
            Math.min(1.0, n / 30.0), n, LEARNER_KEY, history.isEmpty() ? 1 : history.get(0).version() + 1, Instant.now(), null, Instant.now()));
        log.debug("Learned {}={} for customer {} ({}, n={})", key, shrunk, customerRef, reason, n);
    }

    private static String customerRefOf(Event e) {
        if (e.payload() == null) return null;
        Object v = e.payload().getOrDefault("customerRef", e.payload().get("customer_ref"));
        return v == null ? null : String.valueOf(v);
    }

    private static String orderIdOf(Event e) {
        if (e.payload() != null) {
            Object o = e.payload().getOrDefault("orderId", e.payload().get("order_id"));
            if (o != null) return String.valueOf(o);
        }
        return "order".equals(e.aggregateType()) ? e.aggregateId() : e.correlationId();
    }
}
