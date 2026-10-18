package ai.intentoms.inventory.learning;

import ai.intentoms.learning.governance.GovernedLearner;
import ai.intentoms.learning.governance.LearnerDescriptor;
import ai.intentoms.learning.governance.LearnerGovernance;
import ai.intentoms.learning.model.FactKey;
import ai.intentoms.learning.model.SubjectType;
import ai.intentoms.platform.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * IU-02: learns how long a cart reservation should be held. From the last 30 days of cart reservations it takes the
 * time each converted cart needed to become an order and sets the TTL to the 95th percentile of that curve plus 10%
 * (so almost every eventual buyer is still covered) clamped to 5..1440 minutes, as {@code RESERVATION_TTL_MINUTES} on
 * segment {@code default}. Needs at least 20 conversions. Reservation callers that pass no TTL get this value only while
 * {@code inventory.reservation_ttl} is promoted for live use.
 */
@Component
public class ReservationTtlLearner implements GovernedLearner {
    public static final String KEY = "inventory_reservation_ttl";
    private static final Logger log = LoggerFactory.getLogger(ReservationTtlLearner.class);
    static final int MIN_CONVERSIONS = 20;

    private final InventoryLearningRepository repo;
    private final InventoryLearnerSupport support;
    private final LearnerGovernance governance;

    public ReservationTtlLearner(InventoryLearningRepository repo, InventoryLearnerSupport support, LearnerGovernance governance) {
        this.repo = repo;
        this.support = support;
        this.governance = governance;
    }

    @Scheduled(fixedDelayString = "${oms.inventory.learning.reservation-ttl-sweep-ms:86400000}", initialDelayString = "${oms.inventory.learning.reservation-ttl-initial-ms:1200000}")
    public void scheduled() {
        try { sweepOnce(); } catch (RuntimeException e) { log.error("Reservation TTL learner failed: {}", e.toString()); }
    }

    public void sweepOnce() {
        for (String tenant : support.tenants()) {
            try { TenantContext.runAs(tenant, this::learnForTenant); }
            catch (RuntimeException e) { log.warn("Reservation TTL learner failed for tenant {}: {}", tenant, e.toString()); }
        }
    }

    /** The learned TTL in minutes, when there was enough evidence. */
    public Optional<Double> learnForTenant() {
        if (!governance.begin(KEY).mayRun()) return Optional.empty();
        governance.recordRun(KEY);
        Instant since = Instant.now().minus(Duration.ofDays(30));
        List<Double> minutes = new ArrayList<>(repo.conversionMinutes(since));
        if (minutes.size() < MIN_CONVERSIONS) return Optional.empty();
        double ttl = ttlFor(minutes);
        long carts = Math.max(minutes.size(), repo.cartReservations(since));
        support.writeNumber(SubjectType.SEGMENT, LearnedInventoryParameters.TENANT_SEGMENT, FactKey.RESERVATION_TTL_MINUTES, ttl,
            Math.min(1.0, minutes.size() / 100.0), (int) carts, KEY, 1.0);
        return Optional.of(ttl);
    }

    /** 95th percentile of time-to-convert plus 10%, clamped to 5..1440 minutes and rounded to the minute. */
    static double ttlFor(List<Double> minutes) {
        List<Double> sorted = new ArrayList<>(minutes);
        Collections.sort(sorted);
        int idx = (int) Math.ceil(0.95 * sorted.size()) - 1;
        double p95 = sorted.get(Math.max(0, Math.min(sorted.size() - 1, idx)));
        return Math.max(5, Math.min(1440, Math.round(p95 * 1.1)));
    }

    @Override
    public LearnerDescriptor descriptor() {
        return LearnerDescriptor.of(KEY, "Cart hold time", "Learns how long to hold stock for a shopping cart, from how long real shoppers take to check out.",
            LearnerDescriptor.Loop.FAST, "IU-02").feeds(LearnedInventoryParameters.RESERVATION_TTL);
    }

    @Override public boolean runnable() { return true; }

    @Override public void runNow() { sweepOnce(); }
}
