package ai.intentoms.sourcing.learning;

import ai.intentoms.learning.governance.ParameterResolver;
import ai.intentoms.learning.learner.LaneDeliveryLearner;
import ai.intentoms.learning.learner.NodeAssignmentRepository;
import ai.intentoms.learning.model.FactKey;
import ai.intentoms.learning.model.LearnedFact;
import ai.intentoms.learning.model.SubjectType;
import ai.intentoms.learning.model.TypedValue;
import ai.intentoms.learning.store.LearnedFactCache;
import ai.intentoms.platform.config.TenantConfigStore;
import ai.intentoms.platform.tenant.TenantContext;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;

/**
 * FS-03 / CX-01 on the live paths: what the lane learner knows about carrier transit and on-time odds, served to
 * candidate generation (so sourcing plans and the order's promise date use it) and to {@code POST /promise}.
 *
 * <p>Transit days come only from the exact lane (carrier x service x origin x destination ZIP3). A lane replaces the
 * rate card once it has {@value #MIN_SHORTEN_SAMPLES} deliveries when it says the trip is faster, but may make a
 * promise later only with {@value #MIN_LENGTHEN_SAMPLES} deliveries: a thin lane is not evidence that a nearby DC is
 * slower than its table. Calibration uses the exact lane, then the carrier x service x origin lane, with at least
 * {@value #MIN_CALIBRATION_SAMPLES} deliveries. Both reach live paths only through {@link ParameterResolver}
 * (promoted for live use, within range and max step), and fall back to the static values otherwise.
 */
@Component
public class LaneKnowledge {
    public static final int MIN_SHORTEN_SAMPLES = 8;
    public static final int MIN_LENGTHEN_SAMPLES = 20;
    public static final int MIN_CALIBRATION_SAMPLES = 10;

    private final LearnedFactCache facts;
    private final ParameterResolver parameters;
    private final NodeAssignmentRepository assignments;
    private final TenantConfigStore config;
    private final Cache<String, Optional<String>> carriers = Caffeine.newBuilder().expireAfterWrite(Duration.ofMinutes(10)).maximumSize(10_000).build();

    public LaneKnowledge(LearnedFactCache facts, ParameterResolver parameters, NodeAssignmentRepository assignments, TenantConfigStore config) {
        this.facts = facts;
        this.parameters = parameters;
        this.assignments = assignments;
        this.config = config;
    }

    /** What transit was used and why. {@code days} excludes the node's cutoff roll-over; {@code learnedP50} may be null. */
    public record Transit(int days, int staticDays, String lane, Double learnedP50, int samples, String source, String reason) {}

    /** Carrier transit days for a service from a node to a ZIP3, given the rate card's {@code staticDays}. */
    public Transit transit(String service, String nodeId, String zip3, int staticDays) {
        try {
            String carrier = carrierFor(service, nodeId);
            if (carrier == null || zip3 == null) return new Transit(staticDays, staticDays, null, null, 0, "STATIC", "no carrier or destination for this lane");
            String lane = LaneDeliveryLearner.laneKey(carrier, service, nodeId, zip3);
            Optional<LearnedFact> fact = facts.active(SubjectType.LANE, lane, FactKey.LANE_TRANSIT_DAYS_P50).filter(f -> f.value() instanceof TypedValue.NumberValue);
            if (fact.isEmpty()) return new Transit(staticDays, staticDays, lane, null, 0, "STATIC", "nothing learned for this lane yet");
            double p50 = ((TypedValue.NumberValue) fact.get().value()).value();
            int samples = fact.get().sampleSize();
            int learnedDays = (int) Math.ceil(p50 - 1e-9);
            if (samples < MIN_SHORTEN_SAMPLES) return new Transit(staticDays, staticDays, lane, p50, samples, "STATIC", "only " + samples + " deliveries on this lane");
            if (learnedDays > staticDays && samples < MIN_LENGTHEN_SAMPLES) {
                return new Transit(staticDays, staticDays, lane, p50, samples, "STATIC", "lane looks slower than the rate card but has only " + samples + " deliveries (need " + MIN_LENGTHEN_SAMPLES + ")");
            }
            ParameterResolver.Resolution r = parameters.explain(LaneDeliveryLearner.TRANSIT_PARAMETER, (double) staticDays, p50);
            int days = r.value() == null ? staticDays : Math.max(0, (int) Math.ceil(r.value() - 1e-9));
            return new Transit(days, staticDays, lane, p50, samples, r.source(), r.reason());
        } catch (RuntimeException e) {
            return new Transit(staticDays, staticDays, null, null, 0, "STATIC", "lane knowledge unavailable");
        }
    }

    /** CX-01: the served calibration offset for a node's stated on-time probability on this lane (0 when static). */
    public double calibration(String service, String nodeId, String zip3) {
        try {
            String carrier = carrierFor(service, nodeId);
            return carrier == null ? 0.0 : servedCorrection(LaneDeliveryLearner.laneKey(carrier, service, nodeId, zip3));
        } catch (RuntimeException e) {
            return 0.0;
        }
    }

    /**
     * CX-01: the correction served now on a lane ({@code carrier|service|node[|zip3]}): the exact lane's learned offset,
     * else the carrier x service x origin lane's, through {@link ParameterResolver} (0 while not promoted).
     */
    public double servedCorrection(String laneKey) {
        try {
            String[] p = laneKey.split("\\|");
            if (p.length < 3) return 0.0;
            for (String lane : LaneDeliveryLearner.lanesFor(p[0], p[1], p[2], p.length > 3 ? p[3] : null)) {
                Optional<LearnedFact> f = facts.active(SubjectType.LANE, lane, FactKey.LANE_CALIBRATION_OFFSET)
                    .filter(x -> x.sampleSize() >= MIN_CALIBRATION_SAMPLES && x.value() instanceof TypedValue.NumberValue);
                if (f.isPresent()) return parameters.resolve(LaneDeliveryLearner.CALIBRATION_PARAMETER, 0.0, ((TypedValue.NumberValue) f.get().value()).value());
            }
            return 0.0;
        } catch (RuntimeException e) {
            return 0.0;
        }
    }

    /** The carrier a service ships with from this node: tenant map {@code sourcing.service_carriers} first, then what shipments show. */
    public String carrierFor(String service, String nodeId) {
        try {
            Object mapped = config.getMap("sourcing.service_carriers").get(service);
            if (mapped != null) return String.valueOf(mapped);
            return carriers.get(TenantContext.require() + "|" + service + "|" + nodeId, k -> assignments.usualCarrier(service, nodeId)).orElse(null);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
